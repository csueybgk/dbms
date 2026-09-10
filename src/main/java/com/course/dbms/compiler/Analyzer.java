package com.course.dbms.compiler;

import com.course.dbms.common.Error;
import com.course.dbms.compiler.ast.ColRef;
import com.course.dbms.compiler.ast.Cond;
import com.course.dbms.compiler.ast.CreateIndexStmt;
import com.course.dbms.compiler.ast.CreateStmt;
import com.course.dbms.compiler.ast.InsertStmt;
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
 * 检查项（错误码以 SE- 开头）：
 *   - CREATE：列名不可重复、至少一列；表名唯一由 Catalog 在落地时校验。
 *   - CREATE INDEX：表存在、列存在（SE-0004）、索引名未被占用（SE-0007）。
 *   - INSERT：表存在（TB-0001）；值的个数必须等于列数；每个值可被 cast 到对应列类型（SE-0005）。
 *   - SELECT：表存在；SELECT 列、WHERE 列、ORDER BY 列都必须存在于表结构中。
 *   - SHOW：show table <x> 时该表必须存在。
 *
 * 校验通过后即返回，无副作用；Page 级执行引擎在 execute 时直接从 Catalog 取表。
 */
public class Analyzer {

    private final Catalog catalog;

    public Analyzer(Catalog catalog) {
        this.catalog = catalog;
    }

    public void analyze(Stmt stmt) {
        if (stmt instanceof CreateStmt) analyzeCreate((CreateStmt) stmt);
        else if (stmt instanceof CreateIndexStmt) analyzeCreateIndex((CreateIndexStmt) stmt);
        else if (stmt instanceof InsertStmt) analyzeInsert((InsertStmt) stmt);
        else if (stmt instanceof SelectStmt) analyzeSelect((SelectStmt) stmt);
        else if (stmt instanceof ShowStmt) analyzeShow((ShowStmt) stmt);
        else if (stmt instanceof TxnStmt) { /* 事务控制语句由 Session 拦截，无静态校验 */ }
        else throw new Error("SE-0000", "unknown statement: " + stmt);
    }

    private void analyzeCreate(CreateStmt c) {
        if (c.columns.isEmpty()) {
            throw new Error("SE-0001", "表至少需要一列");
        }
        Set<String> seen = new HashSet<>();
        for (Column col : c.columns) {
            if (!seen.add(col.name().toLowerCase())) {
                throw new Error("SE-0002", "列名重复: " + col.name());
            }
        }
    }

    /**
     * CREATE INDEX：目标表存在、目标列存在、索引名未被占用。
     * 索引名冲突用新错误码 SE-0007（与"列不存在"的 SE-0004 区分开）。
     */
    private void analyzeCreateIndex(CreateIndexStmt c) {
        Table table = catalog.getTable(c.tableName);             // TB-0001 if missing
        if (table.schema().indexOf(c.columnName) < 0) {
            throw new Error("SE-0004", "列不存在: " + c.columnName + "（表 " + table.name() + "）");
        }
        if (catalog.hasIndex(c.indexName)) {
            throw new Error("SE-0007", "索引名已存在: " + c.indexName);
        }
    }

    private void analyzeInsert(InsertStmt ins) {
        Table table = catalog.getTable(ins.tableName);       // TB-0001 if missing
        Schema schema = table.schema();
        if (ins.values.size() != schema.columnCount()) {
            throw new Error("SE-0003", "值个数与列数不一致: 列 " + schema.columnCount() + " 个, 给了 " + ins.values.size() + " 个");
        }
        for (int i = 0; i < schema.columnCount(); i++) {
            StorageEngine.cast(schema, i, ins.values.get(i)); // SE-0005 on type mismatch
        }
    }

    private void analyzeSelect(SelectStmt sel) {
        if (sel.isRich()) { analyzeSelectRich(sel); return; }
        // 原有单表路径
        Table table = catalog.getTable(sel.tableName);
        Schema schema = table.schema();
        if (!sel.all) {
            for (String col : sel.columns) requireColumn(table, col);
        }
        if (sel.where != null) checkCond(table, sel.where);
        if (sel.orderBy != null) requireColumn(table, sel.orderBy);
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
                throw new Error("SE-0004", "表/别名重复: " + tr.effective());
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
                    if (!isAggFunc(it.func)) throw new Error("SE-0004", "unknown aggregate: " + it.func);
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
                    throw new Error("SE-0004", "ORDER BY 列不存在: " + sel.orderBy);
                }
            } else if (!isOrderResolvable(cs, sel.items, sel.orderBy)) {
                throw new Error("SE-0004", "ORDER BY 列不存在: " + sel.orderBy);
            }
        }
        // 分组规则：有分组/聚合时，非聚合列必须出现在 GROUP BY 中
        if (grouped) {
            for (SelectItem it : sel.items) {
                if (it.isAgg()) continue;
                if (!inGroupBy(sel.groupBy, it.col)) {
                    throw new Error("SE-0006", "非聚合列 " + it.col.display() + " 必须在 GROUP BY 中");
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
        return lower.equals("count") || lower.equals("sum") || lower.equals("avg")
                || lower.equals("min") || lower.equals("max");
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
            throw new Error("SE-0004", "列不存在: " + col + "（表 " + table.name() + "）");
        }
    }
}
