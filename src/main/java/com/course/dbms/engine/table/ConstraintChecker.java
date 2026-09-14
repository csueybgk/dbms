package com.course.dbms.engine.table;

import com.course.dbms.common.Error;
import com.course.dbms.common.ErrorCode;
import com.course.dbms.engine.exec.op.CondEval;
import com.course.dbms.engine.storage.StorageEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 完整性约束的检查器。
 *
 * 分工：{@link com.course.dbms.compiler.Analyzer} 只做"能静态看出来的"检查
 * （列数、列名、类型可 cast），真正的约束判定在这里 —— 因为唯一性/非空性依赖
 * 表中的实际数据，只有持数据的一方说了算。这与项目一贯的原则一致：预检为了
 * 快速失败 + 友好报错，正确性由权威写入点保证。
 *
 * 调用点只有两个写算子：{@code Insert.execute} 与 {@code Mutate.execute}，
 * 都在真正写盘之前调用。
 *
 * 并发说明：全表扫描判重期间不需要额外加锁 —— Session 对 INSERT/UPDATE/DELETE
 * 已经拿了目标表的 X 锁并持有到提交，检查与写入之间不会有并发写入插进冲突行。
 */
public final class ConstraintChecker {

    /**
     * 本次语句里已经接受过的唯一键。用于挡住"同一条语句把多行改成同一个值"这种
     * 自比较看不到的冲突：update t set u = 1 命中两行时，每行只跳过自己，都会
     * 看到对方的【旧】值而双双通过。放在实例上，一个语句一个 checker。
     */
    private final Set<List<Object>> accepted = new HashSet<>();

    /**
     * 把 (可选的列清单, 字面量) 规整成与表结构等宽的完整行：
     *   - 没写列清单：值的个数必须等于列数，按位置对应；
     *   - 写了列清单：值只覆盖清单里的列，其余列取 DEFAULT，没有 DEFAULT 则填 NULL。
     * Analyzer 与 PlanBuilder 都调它，保证"预检"和"落地"用同一套解析口径。
     */
    public static Row resolveInsert(String tableName, Schema schema, List<String> insertCols, List<Object> values) {
        int n = schema.columnCount();
        if (insertCols == null) {
            if (values.size() != n) {
                throw new Error(ErrorCode.SE_INSERT_VALUE_COUNT,
                        "插入值个数与列数不一致：表 " + tableName + " 有 " + n
                                + " 列，给了 " + values.size() + " 个值");
            }
            List<Object> vals = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                vals.add(StorageEngine.cast(schema, i, values.get(i)));   // 类型不符抛 SE-0005
            }
            return new Row(vals);
        }

        if (insertCols.size() != values.size()) {
            throw new Error(ErrorCode.SE_INSERT_COLUMN_COUNT,
                    "插入值个数与列清单个数不一致：表 " + tableName + " 的列清单写了 "
                            + insertCols.size() + " 个列，却给了 " + values.size() + " 个值");
        }
        Object[] slots = new Object[n];
        boolean[] provided = new boolean[n];
        Set<String> seen = new HashSet<>();
        for (int k = 0; k < insertCols.size(); k++) {
            String name = insertCols.get(k);
            int idx = schema.indexOf(name);
            if (idx < 0) {
                throw new Error(ErrorCode.SE_COLUMN_NOT_FOUND,
                        "列不存在: " + name + "（表 " + tableName + "；该表列为 "
                                + schema.columnNames() + "）");
            }
            if (!seen.add(name.toLowerCase())) {
                throw new Error(ErrorCode.SE_INSERT_COLUMN_DUPLICATE,
                        "INSERT 列清单里有重复列: " + name + "（表 " + tableName + " 的列清单里出现了两次）");
            }
            slots[idx] = StorageEngine.cast(schema, idx, values.get(k));
            provided[idx] = true;
        }
        // 未提供的列：先看 DEFAULT，再退化为 NULL。显式给的 NULL 不动（provided 为 true）
        for (int i = 0; i < n; i++) {
            if (provided[i]) continue;
            Constraint d = schema.defaultOf(i);
            slots[i] = d == null ? null : d.defaultValue(schema.column(i).type());
        }
        return new Row(Arrays.asList(slots));
    }

    /**
     * 写入前的约束校验，顺序：非空 → CHECK → 唯一性。
     *
     * @param row      待写入的完整行
     * @param self     被本行替换掉的那一行（UPDATE 用；INSERT 传 null）—— 判重要跳过它自己
     * @param snapshot 该表在本语句开始前的全表快照（调用方已扫描好的，避免逐行重扫）
     */
    public void check(Table table, Row row, StorageEngine.Located self, List<StorageEngine.Located> snapshot) {
        Schema schema = table.schema();
        checkNotNull(table, row);
        checkChecks(table, schema, row);
        checkUnique(table, row, self, snapshot);
    }

    /** NOT NULL / PRIMARY KEY 的非空性（PRIMARY KEY 隐含 NOT NULL）。 */
    private void checkNotNull(Table table, Row row) {
        Schema schema = table.schema();
        for (Constraint c : schema.constraints()) {
            if (c.kind() != Constraint.Kind.NOT_NULL && c.kind() != Constraint.Kind.PRIMARY_KEY) continue;
            for (String col : c.columns()) {
                int i = schema.indexOf(col);
                if (i >= 0 && row.get(i) == null) {
                    throw new Error(ErrorCode.SE_NOT_NULL_VIOLATION,
                            "列不能为 NULL: " + col + "（表 " + table.name() + " 的 " + c.describe() + "）");
                }
            }
        }
    }

    /**
     * CHECK：只在【明确求值为 FALSE】时拒绝。
     * 求值为 UNKNOWN（表达式里出现 NULL）按 SQL 语义是放行的 —— 例如
     * check (score >= 0) 遇到 score 为 NULL 的行必须通过。
     */
    private void checkChecks(Table table, Schema schema, Row row) {
        CombinedSchema cs = null;
        for (Constraint c : schema.constraints()) {
            if (c.kind() != Constraint.Kind.CHECK) continue;
            if (cs == null) {
                cs = new CombinedSchema();
                for (Column col : schema.columns()) cs.add(table.name(), col.name(), col.type());
            }
            if (Boolean.FALSE.equals(CondEval.evalTri(c.cond(), cs.toSchema(), row, cs::resolve))) {
                throw new Error(ErrorCode.SE_CHECK_VIOLATION,
                        "违反 CHECK 约束: " + c.detail() + "（表 " + table.name() + "）");
            }
        }
    }

    /** UNIQUE / PRIMARY KEY 的唯一性。键里含 NULL 时按 SQL 语义跳过（NULL 互不相等）。 */
    private void checkUnique(Table table, Row row, StorageEngine.Located self,
                             List<StorageEngine.Located> snapshot) {
        Schema schema = table.schema();
        for (Constraint c : schema.constraints()) {
            if (c.kind() != Constraint.Kind.UNIQUE && c.kind() != Constraint.Kind.PRIMARY_KEY) continue;
            List<Object> key = keyOf(schema, row, c);
            if (key == null) continue;                                   // 含 NULL，不参与判重
            if (!accepted.add(key)) {
                throw new Error(ErrorCode.SE_UNIQUE_VIOLATION,
                        "违反唯一性约束 " + c.describe() + " (" + String.join(", ", c.columns())
                                + "): 同一语句内出现重复值 " + key + "（表 " + table.name() + "）");
            }
            if (snapshot == null) continue;
            for (StorageEngine.Located other : snapshot) {
                if (self != null && other.pageNo() == self.pageNo() && other.slot() == self.slot()) continue;
                List<Object> otherKey = keyOf(schema, other.row(), c);
                if (otherKey != null && otherKey.equals(key)) {
                    throw new Error(ErrorCode.SE_UNIQUE_VIOLATION,
                            "违反唯一性约束 " + c.describe() + " (" + String.join(", ", c.columns())
                                    + "): 已存在相同的值 " + key + "（表 " + table.name() + "）");
                }
            }
        }
    }

    /** 取约束涉及的列值作为键；任一列为 NULL 时返回 null（该键不参与唯一性判定）。 */
    private static List<Object> keyOf(Schema schema, Row row, Constraint c) {
        List<Object> key = new ArrayList<>(c.columns().size());
        for (String col : c.columns()) {
            int i = schema.indexOf(col);
            if (i < 0) return null;                                       // 列已不存在（防御性）
            Object v = row.get(i);
            if (v == null) return null;
            key.add(v);
        }
        return key.isEmpty() ? null : key;
    }
}
