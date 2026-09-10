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
import com.course.dbms.engine.exec.op.Aggregate;
import com.course.dbms.engine.exec.op.CondEval;
import com.course.dbms.engine.exec.op.CreateIndex;
import com.course.dbms.engine.exec.op.CreateTable;
import com.course.dbms.engine.exec.op.Filter;
import com.course.dbms.engine.exec.op.IndexScan;
import com.course.dbms.engine.exec.op.Insert;
import com.course.dbms.engine.exec.op.Join;
import com.course.dbms.engine.exec.op.Operator;
import com.course.dbms.engine.exec.op.Project;
import com.course.dbms.engine.exec.op.SeqScan;
import com.course.dbms.engine.exec.op.ShowIndexes;
import com.course.dbms.engine.exec.op.ShowTable;
import com.course.dbms.engine.exec.op.ShowTables;
import com.course.dbms.engine.exec.op.Sort;
import com.course.dbms.engine.index.Index;
import com.course.dbms.engine.storage.StorageEngine;
import com.course.dbms.engine.table.Catalog;
import com.course.dbms.engine.table.Column;
import com.course.dbms.engine.table.CombinedSchema;
import com.course.dbms.engine.table.FieldType;
import com.course.dbms.engine.table.Row;
import com.course.dbms.engine.table.Schema;
import com.course.dbms.engine.table.Table;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 执行计划生成：把语义校验通过的 AST 转换成逻辑算子树。
 * 对应图片"① SQL编译器 - 执行计划生成：逻辑执行计划"。
 *
 * SELECT → [IndexScan | SeqScan](表) → [Filter(WHERE)] → [Sort(ORDER BY)] → Project(列)
 * CREATE / CREATE INDEX / INSERT / SHOW → 各自的叶子动作算子。
 * 这里也做最简单的【物理计划选择】：WHERE 的合取项命中索引就走 IndexScan，否则全表扫。
 * 注：本方法假定 {@link Analyzer} 已经跑过（否则非法列名会让 indexOf 返回 -1），
 *     均由上层 Database.execute 保证执行顺序。
 */
public class PlanBuilder {

    private final Catalog catalog;
    private final StorageEngine se;

    public PlanBuilder(Catalog catalog, StorageEngine se) {
        this.catalog = catalog;
        this.se = se;
    }

    public Plan build(Stmt stmt) {
        if (stmt instanceof CreateStmt) return buildCreate((CreateStmt) stmt);
        if (stmt instanceof CreateIndexStmt) return buildCreateIndex((CreateIndexStmt) stmt);
        if (stmt instanceof InsertStmt) return buildInsert((InsertStmt) stmt);
        if (stmt instanceof SelectStmt) return buildSelect((SelectStmt) stmt);
        if (stmt instanceof ShowStmt) return buildShow((ShowStmt) stmt);
        if (stmt instanceof TxnStmt) {
            // 事务控制语句应由 Session 拦截，不应进入执行计划
            throw new Error("PL-0002", "transaction control is handled by Session, not the plan");
        }
        throw new Error("PL-0001", "unknown statement: " + stmt);
    }

    private Plan buildCreate(CreateStmt c) {
        Schema schema = new Schema();
        for (Column col : c.columns) schema.add(col.name(), col.type());
        Operator op = new CreateTable(catalog, c.tableName, schema);
        return new Plan(op, Collections.singletonList("result"));
    }

    private Plan buildCreateIndex(CreateIndexStmt c) {
        Operator op = new CreateIndex(catalog, c.indexName, c.tableName, c.columnName);
        return new Plan(op, Collections.singletonList("result"));
    }

    private Plan buildInsert(InsertStmt ins) {
        Table table = catalog.getTable(ins.tableName);
        Schema schema = table.schema();
        List<Object> vals = new ArrayList<>();
        for (int i = 0; i < schema.columnCount(); i++) {
            vals.add(StorageEngine.cast(schema, i, ins.values.get(i)));
        }
        Operator op = new Insert(se, table, new Row(vals), catalog.indexManager());
        return new Plan(op, Collections.singletonList("affected"));
    }

    private Plan buildSelect(SelectStmt sel) {
        if (sel.isRich()) return buildSelectRich(sel);
        Table table = catalog.getTable(sel.tableName);
        Schema schema = table.schema();

        // 物理计划选择：WHERE 的某个合取项上若有索引，就把它变成一次范围确定的行取，
        // 否则保持全表扫描。Filter 一律保留在上层，所以索引只是"少读页"，不影响正确性。
        Operator root = indexScanFor(table, sel.where);
        if (root == null) root = new SeqScan(se, table);
        if (sel.where != null) root = new Filter(root, schema, sel.where);

        int[] cols;
        List<String> names = new ArrayList<>();
        if (sel.all) {
            cols = new int[schema.columnCount()];
            for (int i = 0; i < schema.columnCount(); i++) {
                cols[i] = i;
                names.add(schema.column(i).name());
            }
        } else {
            cols = new int[sel.columns.size()];
            for (int i = 0; i < sel.columns.size(); i++) {
                cols[i] = schema.indexOf(sel.columns.get(i));
                names.add(schema.column(cols[i]).name());
            }
        }

        if (sel.orderBy != null) {
            int orderIdx = schema.indexOf(sel.orderBy);
            root = new Sort(root, orderIdx, sel.orderDesc);
        }

        root = new Project(root, cols);
        return new Plan(root, names);
    }

    /**
     * 多表联查 / 聚合 / 别名 / 限定列的 rich 管线：
     *   SeqScan(t0) → [Join(...)] → [Filter(WHERE)] → [Aggregate | Project] → [Sort(ORDER BY)]
     * 假定 {@link Analyzer} 已校验（列存在、无歧义、分组规则）。
     */
    private Plan buildSelectRich(SelectStmt sel) {
        List<TableRef> from = sel.from;
        List<Table> tables = new ArrayList<>();
        CombinedSchema cs = new CombinedSchema();
        for (TableRef tr : from) {
            Table t = catalog.getTable(tr.name);
            tables.add(t);
            String q = tr.effective();
            for (int c = 0; c < t.schema().columnCount(); c++) {
                cs.add(q, t.schema().column(c).name(), t.schema().column(c).type());
            }
        }
        final CombinedSchema csF = cs;
        CondEval.Resolver resolver = csF::resolve;

        // 左深连接链
        Operator root = new SeqScan(se, tables.get(0));
        int leftWidth = tables.get(0).schema().columnCount();
        for (int k = 1; k < tables.size(); k++) {
            int rightWidth = tables.get(k).schema().columnCount();
            root = new Join(root, new SeqScan(se, tables.get(k)),
                    sel.joinOn.get(k - 1), csF, resolver,
                    sel.joinOuter.get(k - 1), leftWidth, rightWidth);
            leftWidth += rightWidth;
        }
        if (sel.where != null) {
            root = new Filter(root, csF.toSchema(), resolver, sel.where);
        }

        boolean hasAgg = hasAggregate(sel.items);
        boolean hasGroup = sel.groupBy != null && !sel.groupBy.isEmpty();

        if (hasAgg || hasGroup) {
            int[] groupIdx = groupIndices(sel.groupBy, csF);
            List<Aggregate.Item> aggItems = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (SelectItem it : sel.items) {
                if (it.isAgg()) {
                    int argIdx = -1;
                    FieldType argType = null;
                    if (it.arg != null) {
                        argIdx = csF.resolve(it.arg.qualifier, it.arg.name);
                        argType = csF.typeAt(argIdx);
                    }
                    aggItems.add(new Aggregate.Item(true, -1, it.func, argIdx, it.arg == null, argType));
                    names.add(it.outputName());
                } else {
                    int gp = groupKeyPos(sel.groupBy, csF, it.col);
                    aggItems.add(new Aggregate.Item(false, gp, null, -1, false, null));
                    names.add(it.outputName());
                }
            }
            root = new Aggregate(root, groupIdx, aggItems);
            if (sel.orderBy != null) {
                int oi = aggOutputIndex(sel.items, sel.orderBy);
                if (oi < 0) throw new Error("SE-0004", "ORDER BY 列不存在: " + sel.orderBy);
                root = new Sort(root, oi, sel.orderDesc);
            }
            return new Plan(root, names);
        }

        // 非聚合：投影（sel.all 表示输出全部拼接列）
        int[] projCols;
        List<String> names = new ArrayList<>();
        if (sel.all) {
            projCols = new int[csF.width()];
            for (int i = 0; i < projCols.length; i++) { projCols[i] = i; names.add(csF.nameAt(i)); }
        } else {
            projCols = new int[sel.items.size()];
            for (int i = 0; i < sel.items.size(); i++) {
                SelectItem it = sel.items.get(i);
                projCols[i] = csF.resolve(it.col.qualifier, it.col.name);
                names.add(it.outputName());
            }
        }
        if (sel.orderBy != null) {
            int oi = outputIndex(names, sel.orderBy);
            if (oi >= 0) {                       // 排序键就是输出列（别名/列名）
                root = new Project(root, projCols);
                root = new Sort(root, oi, sel.orderDesc);
            } else {                             // 排序键是源列，须在投影前排序
                root = new Sort(root, resolveOrder(csF, sel.orderBy), sel.orderDesc);
                root = new Project(root, projCols);
            }
        } else {
            root = new Project(root, projCols);
        }
        return new Plan(root, names);
    }

    /**
     * 尝试为单表查询选一个索引扫描；没有可用索引时返回 null（调用方退回 SeqScan）。
     *
     * 规则保守但安全：
     *   - 只在 WHERE 的【合取项】里挑（顶层 CMP，或 AND 树里的 CMP）；不钻进 OR
     *     —— OR 的任一支不成立就不能缩小范围，硬拆会漏行；
     *   - 只认 列 op 字面量 且 op ∈ {= < > <= >=}；<>. 列 vs 列 不适用；
     *   - 列上得有索引，且比较值能 cast 到列类型（Analyzer 已校验过）。
     * 命中后用 op 换算出扫描区间，例如 age >= 24 → [24, +∞)。
     */
    private Operator indexScanFor(Table table, Cond where) {
        if (where == null) return null;
        List<Cond> conjuncts = new ArrayList<>();
        collectConjuncts(where, conjuncts);
        for (Cond c : conjuncts) {
            if (c.column2 != null) continue;                     // 列 vs 列，与索引无关
            if (!isRangeOp(c.op)) continue;                      // <> 用不上 B+ 树的范围性
            Index idx = catalog.indexManager().findFor(table, c.column);
            if (idx == null) continue;                           // 该列没建索引

            Object v = StorageEngine.cast(table.schema(), idx.colIndex(), c.value);
            Object lo = null, hi = null;
            boolean loInc = false, hiInc = false;
            switch (c.op) {
                case "=":  lo = v; loInc = true;  hi = v; hiInc = true;  break;
                case ">":  lo = v; loInc = false;                        break;
                case ">=": lo = v; loInc = true;                         break;
                case "<":  hi = v; hiInc = false;                        break;
                case "<=": hi = v; hiInc = true;                         break;
                default:   continue;
            }
            return new IndexScan(se, table, idx.tree(), idx.colIndex(), lo, loInc, hi, hiInc);
        }
        return null;
    }

    /** 收集 WHERE 的合取项：CMP 直接收下，AND 递归展开，OR 整体跳过（不能缩小范围）。 */
    private void collectConjuncts(Cond cond, List<Cond> out) {
        if (cond == null) return;
        if (cond.kind == Cond.Kind.CMP) {
            out.add(cond);
        } else if (cond.kind == Cond.Kind.AND && cond.children != null) {
            for (Cond child : cond.children) collectConjuncts(child, out);
        }
    }

    /** B+ 树的范围查找只对有序比较运算符有意义。 */
    private boolean isRangeOp(String op) {
        return op.equals("=") || op.equals("<") || op.equals(">")
                || op.equals("<=") || op.equals(">=");
    }

    private boolean hasAggregate(List<SelectItem> items) {
        if (items == null) return false;
        for (SelectItem it : items) if (it.isAgg()) return true;
        return false;
    }

    /** GROUP BY 源列下标（null 或空 → 空数组 = 全局聚合）。 */
    private int[] groupIndices(List<ColRef> groupBy, CombinedSchema cs) {
        if (groupBy == null) return new int[0];
        int[] idx = new int[groupBy.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = cs.resolve(groupBy.get(i).qualifier, groupBy.get(i).name);
        }
        return idx;
    }

    /** 非聚合列必须是分组键，返回其在分组键中的位次（供 Aggregate 直接取键值）。 */
    private int groupKeyPos(List<ColRef> groupBy, CombinedSchema cs, ColRef col) {
        for (int i = 0; i < groupBy.size(); i++) {
            if (sameCol(groupBy.get(i), col)) return i;
        }
        throw new Error("SE-0006", "非聚合列 " + col.display() + " 必须出现在 GROUP BY 中");
    }

    private boolean sameCol(ColRef a, ColRef b) {
        if (a.qualifier == null ^ b.qualifier == null) return false;
        if (a.qualifier != null && !a.qualifier.equalsIgnoreCase(b.qualifier)) return false;
        return a.name.equalsIgnoreCase(b.name);
    }

    /** 把 ORDER BY 源列键（"amount" 或 "o.amount"）解析成拼接行下标。 */
    private int resolveOrder(CombinedSchema cs, String key) {
        int dot = key.indexOf('.');
        if (dot < 0) return cs.resolve(null, key);
        return cs.resolve(key.substring(0, dot), key.substring(dot + 1));
    }

    private int outputIndex(List<String> names, String key) {
        for (int i = 0; i < names.size(); i++) if (names.get(i).equalsIgnoreCase(key)) return i;
        return -1;
    }

    /** 聚合/分组查询的排序键映射到输出列下标：匹配输出名（别名/函数名）或分组键的限定显示（u.name）。 */
    private int aggOutputIndex(List<SelectItem> items, String key) {
        for (int i = 0; i < items.size(); i++) {
            SelectItem it = items.get(i);
            if (it.outputName().equalsIgnoreCase(key)) return i;
            if (!it.isAgg() && it.col.qualified() && it.col.display().equalsIgnoreCase(key)) return i;
        }
        return -1;
    }

    private Plan buildShow(ShowStmt s) {
        if (s.indexes) {
            return new Plan(new ShowIndexes(catalog, s.tableName), Arrays.asList("index", "table", "column"));
        }
        if (s.list) {
            return new Plan(new ShowTables(catalog), Arrays.asList("table", "columns"));
        }
        return new Plan(new ShowTable(catalog, s.tableName), Arrays.asList("column", "type"));
    }
}
