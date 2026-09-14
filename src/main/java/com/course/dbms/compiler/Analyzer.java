package com.course.dbms.compiler;

import com.course.dbms.common.Error;
import com.course.dbms.common.ErrorCode;
import com.course.dbms.compiler.ast.ColRef;
import com.course.dbms.compiler.ast.Cond;
import com.course.dbms.compiler.ast.CreateIndexStmt;
import com.course.dbms.compiler.ast.CreateStmt;
import com.course.dbms.compiler.ast.InsertStmt;
import com.course.dbms.compiler.ast.DeleteStmt;
import com.course.dbms.compiler.ast.UpdateStmt;
import com.course.dbms.compiler.ast.SelectItem;
import com.course.dbms.compiler.ast.SelectStmt;
import com.course.dbms.compiler.ast.ShowStmt;
import com.course.dbms.compiler.ast.Stmt;
import com.course.dbms.compiler.ast.TableRef;
import com.course.dbms.compiler.ast.TxnStmt;
import com.course.dbms.engine.storage.StorageEngine;
import com.course.dbms.engine.table.Catalog;
import com.course.dbms.engine.table.Column;
import com.course.dbms.engine.table.CombinedSchema;
import com.course.dbms.engine.table.Constraint;
import com.course.dbms.engine.table.ConstraintChecker;
import com.course.dbms.engine.table.Schema;
import com.course.dbms.engine.table.Table;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 语义分析器：以 AST + 系统目录为依据，做静态校验（不落地、不改数据）。
 * 对应图片"① SQL编译器 - 语义分析：存在性 / 类型 / 列数检查"。
 *
 * 检查项（错误码以 SE- 开头，每个码只有一种含义，详见 {@link ErrorCode}）：
 *   - CREATE：至少一列（SE-0001）、列名不重复（SE-0002）；约束名不重复（SE-0015）、
 *             至多一个 PRIMARY KEY（SE-0016）、约束引用的列存在（SE-0004）、
 *             DEFAULT 与列类型相容（SE-0026）、NOT NULL 列的默认值不为 NULL（SE-0027）、
 *             CHECK 的列存在且字面量可 cast；表名唯一由 Catalog 在落地时校验（CT-0002）。
 *   - CREATE INDEX：表存在（TB-0001）、列存在（SE-0004）、索引名未被占用（SE-0007）。
 *   - INSERT：表存在（TB-0001）；列清单里的列存在（SE-0004）/不重复（SE-0014）；
 *             值的个数与列数（SE-0003）或列清单个数（SE-0029）一致；每个值可被 cast 到对应列类型（SE-0005）。
 *
 * 【边界】这里只做"看 AST 就能判定"的静态检查。NOT NULL / UNIQUE / CHECK 是否真的成立
 * 依赖表中的实际数据，由权威写入点 {@link com.course.dbms.engine.table.ConstraintChecker}
 * 在 Insert / Mutate 算子写盘前判定 —— 预检是为了快速失败和友好报错，不是为了正确性。
 *   - SELECT：表存在；SELECT 列与 WHERE 列必须存在（SE-0004）、ORDER BY 列必须存在（SE-0020）、
 *             分组查询的排序键必须是输出列（SE-0030）、非聚合列必须在 GROUP BY 里（SE-0006）。
 *   - SHOW：show table <x> 时该表必须存在。
 *
 * 校验通过后即返回，无副作用；Page 级执行引擎在 execute 时直接从 Catalog 取表。
 */
public class Analyzer {

    /** 支持的聚合函数名（小写）。校验与报错提示共用同一份清单，避免两处漂移。 */
    static final List<String> AGG_FUNCS =
            java.util.Collections.unmodifiableList(java.util.Arrays.asList("count", "sum", "avg", "min", "max"));

    private final Catalog catalog;

    public Analyzer(Catalog catalog) {
        this.catalog = catalog;
    }

    public void analyze(Stmt stmt) {
        if (stmt instanceof CreateStmt) analyzeCreate((CreateStmt) stmt);
        else if (stmt instanceof CreateIndexStmt) analyzeCreateIndex((CreateIndexStmt) stmt);
        else if (stmt instanceof InsertStmt) analyzeInsert((InsertStmt) stmt);
        else if (stmt instanceof DeleteStmt) {
            DeleteStmt d = (DeleteStmt) stmt;
            analyzeMutation(d.tableName, d.where, java.util.Collections.emptyMap());
        } else if (stmt instanceof UpdateStmt) {
            UpdateStmt u = (UpdateStmt) stmt;
            analyzeMutation(u.tableName, u.where, u.assignments);
        }
        else if (stmt instanceof SelectStmt) analyzeSelect((SelectStmt) stmt);
        else if (stmt instanceof ShowStmt) analyzeShow((ShowStmt) stmt);
        else if (stmt instanceof TxnStmt) { /* 事务控制语句由 Session 拦截，无静态校验 */ }
        else throw new Error(ErrorCode.SE_UNKNOWN_STATEMENT, "未知的语句类型: " + stmt.getClass().getSimpleName());
    }

    private void analyzeCreate(CreateStmt c) {
        if (c.columns.isEmpty()) {
            throw new Error(ErrorCode.SE_TABLE_NEEDS_COLUMN,
                    "表至少需要一列: " + c.tableName + " 的列定义为空");
        }
        Set<String> seen = new HashSet<>();
        for (Column col : c.columns) {
            if (!seen.add(col.name().toLowerCase())) {
                throw new Error(ErrorCode.SE_DUPLICATE_COLUMN,
                        "列名重复: " + col.name() + "（表 " + c.tableName + " 里出现了两次）");
            }
        }
        // 表还没落地，先按列定义拼一个临时 Schema 来校验约束
        Schema schema = new Schema();
        for (Column col : c.columns) schema.add(col.name(), col.type());
        checkConstraints(schema, c.tableName, c.constraints);
    }

    /** CREATE TABLE 约束的静态检查（不碰数据）。 */
    private void checkConstraints(Schema schema, String tableName, List<Constraint> cons) {
        Set<String> names = new HashSet<>();
        boolean hasPrimaryKey = false;
        for (Constraint con : cons) {
            if (!con.name().isEmpty() && !names.add(con.name().toLowerCase())) {
                throw new Error(ErrorCode.SE_CONSTRAINT_NAME_DUPLICATE,
                        "约束名重复: " + con.name() + "（表 " + tableName + " 里出现了两次）");
            }
            if (con.kind() == Constraint.Kind.PRIMARY_KEY) {
                if (hasPrimaryKey) {
                    throw new Error(ErrorCode.SE_MULTIPLE_PRIMARY_KEY,
                            "一张表只能有一个 PRIMARY KEY: " + tableName + " 定义了多个");
                }
                hasPrimaryKey = true;
            }
            for (String col : con.columns()) {
                if (schema.indexOf(col) < 0) {
                    throw new Error(ErrorCode.SE_COLUMN_NOT_FOUND,
                            "约束引用的列不存在: " + col + "（表 " + tableName + " 是新建的，该表列为 "
                                    + schema.columnNames() + "）");
                }
            }
            switch (con.kind()) {
                case DEFAULT: {
                    String col = con.columns().get(0);
                    // parseLiteral 而不是 cast：cast 会把 1.5 静默截断成 1，1.5 这种默认值必须报错
                    Object v = schema.column(schema.indexOf(col)).type().parseLiteral(con.detail());
                    if (v == null && notNullColumns(schema, cons).contains(col.toLowerCase())) {
                        throw new Error(ErrorCode.SE_DEFAULT_NULL_ON_NOT_NULL,
                                "列 " + col + " 是 NOT NULL，默认值不能为 NULL（表 " + tableName + "）");
                    }
                    break;
                }
                case CHECK: {
                    CombinedSchema cs = new CombinedSchema();
                    for (Column col : schema.columns()) cs.add(tableName, col.name(), col.type());
                    // 顺带验证 Cond → toSql → 重新解析这条往返链没走样（cond() 就是重新解析）
                    checkCondRich(cs, con.cond());
                    break;
                }
                default:
                    break;
            }
        }
    }

    /** 该表所有 NOT NULL 列（含 PRIMARY KEY 隐含的 NOT NULL），小写列名。 */
    private static Set<String> notNullColumns(Schema schema, List<Constraint> cons) {
        Set<String> out = new HashSet<>();
        for (Constraint con : cons) {
            if (con.kind() != Constraint.Kind.NOT_NULL && con.kind() != Constraint.Kind.PRIMARY_KEY) continue;
            for (String col : con.columns()) out.add(col.toLowerCase());
        }
        return out;
    }

    /**
     * CREATE INDEX：目标表存在、目标列存在、索引名未被占用。
     * 索引名冲突用新错误码 SE-0007（与"列不存在"的 SE-0004 区分开）。
     */
    private void analyzeCreateIndex(CreateIndexStmt c) {
        Table table = catalog.getTable(c.tableName);             // TB-0001 if missing
        if (table.schema().indexOf(c.columnName) < 0) {
            throw new Error(ErrorCode.SE_COLUMN_NOT_FOUND,
                    "索引的列不存在: " + c.columnName + "（表 " + table.name() + "；该表列为 "
                            + table.schema().columnNames() + "）");
        }
        if (catalog.hasIndex(c.indexName)) {
            throw new Error(ErrorCode.SE_INDEX_EXISTS,
                    "索引名已存在: " + c.indexName + "（表 " + table.name() + "）");
        }
    }

    private void analyzeInsert(InsertStmt ins) {
        Table table = catalog.getTable(ins.tableName);       // TB-0001 if missing
        // 与 PlanBuilder 共用同一套解析：列清单解析、DEFAULT/NULL 补全、逐个 cast。
        // 这样"预检报的错"和"落地会报的错"不会对不上。
        ConstraintChecker.resolveInsert(table.name(), table.schema(), ins.columns, ins.values);
    }

    private void analyzeMutation(String name, Cond where, java.util.Map<String, Object> assignments) {
        Table table = catalog.getTable(name);
        CombinedSchema cs = new CombinedSchema();
        for (Column column : table.schema().columns()) cs.add(table.name(), column.name(), column.type());
        if (where != null) checkCondRich(cs, where);
        for (java.util.Map.Entry<String, Object> entry : assignments.entrySet()) {
            requireColumn(table, entry.getKey());
            StorageEngine.cast(table.schema(), table.schema().indexOf(entry.getKey()), entry.getValue());
        }
    }

    private void analyzeSelect(SelectStmt sel) {
        if (sel.isRich()) { analyzeSelectRich(sel); return; }
        // 原有单表路径
        Table table = catalog.getTable(sel.tableName);
        if (!sel.all) {
            for (String col : sel.columns) requireColumn(table, col);
        }
        if (sel.where != null) checkCond(table, sel.where);
        if (sel.orderBy != null) requireOrderColumn(table, sel.orderBy);
    }

    /** 多表联查 / 聚合 / 别名 / 限定列的语义校验。 */
    private void analyzeSelectRich(SelectStmt sel) {
        List<TableRef> from = sel.from;
        List<Table> tables = new ArrayList<>();
        Set<String> eff = new HashSet<>();
        for (TableRef tr : from) {
            Table t = catalog.getTable(tr.name);          // TB-0001 if missing
            tables.add(t);
            if (!eff.add(tr.effective().toLowerCase())) {
                throw new Error(ErrorCode.SE_DUPLICATE_TABLE_ALIAS,
                        "表名或别名重复: " + tr.effective() + "（同一个 FROM 子句里出现了两次）");
            }
        }
        // 逐表接入 CombinedSchema，并对每个 JOIN 的 ON 校验（只能引用到此为止的表）
        CombinedSchema cs = new CombinedSchema();
        for (int k = 0; k < from.size(); k++) {
            Table t = tables.get(k);
            String q = from.get(k).effective();
            for (int c = 0; c < t.schema().columnCount(); c++) {
                cs.add(q, t.schema().column(c).name(), t.schema().column(c).type());
            }
            if (k > 0) {
                Cond on = sel.joinOn.get(k - 1);
                if (on != null) checkCondRich(cs, on);
            }
        }
        // SELECT 项
        if (!sel.all) {
            for (SelectItem it : sel.items) {
                if (it.isAgg()) {
                    if (it.arg != null) resolveCol(cs, it.arg);   // COUNT(*) 无参数列
                    if (!isAggFunc(it.func)) {
                        throw new Error(ErrorCode.SE_UNKNOWN_AGGREGATE,
                                "未知的聚合函数: " + it.func + "（可用: " + String.join(", ", AGG_FUNCS) + "）");
                    }
                } else {
                    resolveCol(cs, it.col);
                }
            }
        }
        if (sel.where != null) checkCondRich(cs, sel.where);
        if (sel.groupBy != null) {
            for (ColRef c : sel.groupBy) resolveCol(cs, c);
        }
        boolean grouped = hasAggregate(sel.items) || (sel.groupBy != null && !sel.groupBy.isEmpty());
        if (sel.orderBy != null) {
            // 分组/聚合查询里，排序键只能是输出列（分组键或聚合别名/函数名）；否则可为源列
            if (grouped) {
                if (aggOrderIndex(sel.items, sel.orderBy) < 0) {
                    // 分组查询里排序键必须是输出列——和"列不存在"是两回事，修复动作也不同
                    throw new Error(ErrorCode.SE_ORDER_BY_NOT_OUTPUT,
                            "ORDER BY 的列必须是输出列: " + sel.orderBy
                                    + "（分组或带聚合的查询里，排序键只能是分组键或聚合结果）");
                }
            } else if (!isOrderResolvable(cs, sel.items, sel.orderBy)) {
                throw new Error(ErrorCode.SE_ORDER_BY_COLUMN_NOT_FOUND,
                        "ORDER BY 列不存在: " + sel.orderBy + "（" + cs.columnNames() + "）");
            }
        }
        // 分组规则：有分组/聚合时，非聚合列必须出现在 GROUP BY 中
        if (grouped) {
            for (SelectItem it : sel.items) {
                if (it.isAgg()) continue;
                if (!inGroupBy(sel.groupBy, it.col)) {
                    throw new Error(ErrorCode.SE_GROUP_BY_MISSING,
                            "非聚合列未出现在 GROUP BY: " + it.col.display()
                                    + "（有聚合或分组时，其余列必须都写进 GROUP BY）");
                }
            }
        }
    }

    private void checkCondRich(CombinedSchema cs, Cond cond) {
        if (cond.kind == Cond.Kind.CMP) {
            int i1 = cs.resolve(cond.qualifier, cond.column);     // 左列存在
            if (cond.column2 != null) {
                cs.resolve(cond.qualifier2, cond.column2);         // 右列存在
            } else {
                StorageEngine.cast(cs.toSchema(), i1, cond.value); // 字面量可 cast 到列类型
            }
        } else if (cond.children != null) {
            for (Cond child : cond.children) checkCondRich(cs, child);
        }
    }

    private void resolveCol(CombinedSchema cs, ColRef c) {
        cs.resolve(c.qualifier, c.name);                          // 抛 SE-0004
    }

    /** ORDER BY 键：要么是输出列名（别名/聚合名），要么是源列表引用。 */
    private boolean isOrderResolvable(CombinedSchema cs, List<SelectItem> items, String key) {
        if (isOutputName(items, key)) return true;
        int dot = key.indexOf('.');
        if (dot < 0) return tryResolve(cs, new ColRef(null, key));
        return tryResolve(cs, new ColRef(key.substring(0, dot), key.substring(dot + 1)));
    }

    private boolean tryResolve(CombinedSchema cs, ColRef c) {
        try { cs.resolve(c.qualifier, c.name); return true; }
        catch (Error e) { return false; }
    }

    private boolean isOutputName(List<SelectItem> items, String name) {
        if (items == null) return false;
        for (SelectItem it : items) if (it.outputName().equalsIgnoreCase(name)) return true;
        return false;
    }

    /** 分组/聚合查询的排序键 → 输出列下标（匹配输出名或分组键的限定显示）。 */
    private int aggOrderIndex(List<SelectItem> items, String key) {
        if (items == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            SelectItem it = items.get(i);
            if (it.outputName().equalsIgnoreCase(key)) return i;
            if (!it.isAgg() && it.col.qualified() && it.col.display().equalsIgnoreCase(key)) return i;
        }
        return -1;
    }

    private boolean hasAggregate(List<SelectItem> items) {
        if (items == null) return false;
        for (SelectItem it : items) if (it.isAgg()) return true;
        return false;
    }

    private boolean inGroupBy(List<ColRef> groupBy, ColRef col) {
        if (groupBy == null) return false;
        for (ColRef c : groupBy) if (sameCol(c, col)) return true;
        return false;
    }

    private boolean sameCol(ColRef a, ColRef b) {
        if (a.qualifier == null ^ b.qualifier == null) return false;
        if (a.qualifier != null && !a.qualifier.equalsIgnoreCase(b.qualifier)) return false;
        return a.name.equalsIgnoreCase(b.name);
    }

    private boolean isAggFunc(String lower) {
        return AGG_FUNCS.contains(lower);
    }

    private void analyzeShow(ShowStmt s) {
        if (s.indexes) {                                     // show indexes [on <表>]：表名可空
            if (s.tableName != null) catalog.getTable(s.tableName);
            return;
        }
        if (!s.list) catalog.getTable(s.tableName);          // 验证表存在
    }

    private void checkCond(Table table, Cond cond) {
        if (cond.kind == Cond.Kind.CMP) {
            requireColumn(table, cond.column);
            // 比较字面量也要能 cast 到该列类型，避免 age > 'abc' 之类的运行时炸
            int idx = table.schema().indexOf(cond.column);
            StorageEngine.cast(table.schema(), idx, cond.value);
        } else if (cond.children != null) {
            for (Cond child : cond.children) checkCond(table, child);
        }
    }

    private void requireColumn(Table table, String col) {
        if (table.schema().indexOf(col) < 0) {
            throw new Error(ErrorCode.SE_COLUMN_NOT_FOUND,
                    "列不存在: " + col + "（表 " + table.name() + "；该表列为 "
                            + table.schema().columnNames() + "）");
        }
    }

    /**
     * ORDER BY 的列检查。与 {@link #requireColumn} 分开，是因为错误码不同：
     * ORDER BY 写错列名要报 SE-0020，而 SELECT/WHERE 里写错报 SE-0004。
     * 两者归到同一个码，用户就没法从码上区分该改哪里。
     */
    private void requireOrderColumn(Table table, String col) {
        if (table.schema().indexOf(col) < 0) {
            throw new Error(ErrorCode.SE_ORDER_BY_COLUMN_NOT_FOUND,
                    "ORDER BY 列不存在: " + col + "（表 " + table.name() + "；该表列为 "
                            + table.schema().columnNames() + "）");
        }
    }
}
