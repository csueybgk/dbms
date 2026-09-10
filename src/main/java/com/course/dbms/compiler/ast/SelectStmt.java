package com.course.dbms.compiler.ast;

import java.util.List;

/**
 * SELECT 语句。
 * 旧字段（all/columns/tableName/where/orderBy/orderDesc）用于纯单表无特性查询，
 * 与新字段（from/joinOn/joinOuter/items/groupBy）共同保存；二者由 {@link #isRich()} 区分。
 * 纯单表无特性 → isRich()==false，执行计划走原单表路径；否则走多表/聚合路径。
 */
public class SelectStmt extends Stmt {

    public final boolean all;          // true 表示 select *
    public final List<String> columns; // all=false 时的列名列表（旧路径用）
    public final String tableName;     // 第一个表的表名（旧路径用）
    public final Cond where;           // 可为 null
    public final String orderBy;       // 可为 null（旧路径：裸列名）
    public final boolean orderDesc;

    // —— 多表联查 / 聚合（rich 路径）——
    public final List<TableRef> from;      // FROM 列表（≥1 个表）
    public final List<Cond> joinOn;        // 每个 JOIN 的 ON（长度 = from.size()-1；null=笛卡尔积）
    public final List<Boolean> joinOuter;  // 每个 JOIN 是否 LEFT OUTER（长度 = from.size()-1）
    public final List<SelectItem> items;   // SELECT 项（all=true 时为空）
    public final List<ColRef> groupBy;     // GROUP BY 列（可为 null）

    /** 旧式构造：纯单表无特性。 */
    public SelectStmt(boolean all, List<String> columns, String tableName, Cond where, String orderBy, boolean orderDesc) {
        this(all, columns, tableName, where, orderBy, orderDesc, null, null, null, null, null);
    }

    /** 完整构造。 */
    public SelectStmt(boolean all, List<String> columns, String tableName, Cond where, String orderBy, boolean orderDesc,
                      List<TableRef> from, List<Cond> joinOn, List<Boolean> joinOuter,
                      List<SelectItem> items, List<ColRef> groupBy) {
        this.all = all;
        this.columns = columns;
        this.tableName = tableName;
        this.where = where;
        this.orderBy = orderBy;
        this.orderDesc = orderDesc;
        this.from = from;
        this.joinOn = joinOn;
        this.joinOuter = joinOuter;
        this.items = items;
        this.groupBy = groupBy;
    }

    /** 是否走"多表/别名/限定列/聚合/GROUP BY"新管线。false=纯单表无特性（旧路径）。 */
    public boolean isRich() {
        if (from == null || from.isEmpty()) return false;
        if (from.size() > 1) return true;
        if (from.get(0).alias != null) return true;
        if (joinOn != null && !joinOn.isEmpty()) return true;
        if (groupBy != null && !groupBy.isEmpty()) return true;
        if (items != null) {
            for (SelectItem it : items) {
                if (it.isAgg()) return true;
                if (it.col != null && it.col.qualified()) return true;
                if (it.alias != null) return true;
            }
        }
        return false;
    }
}
