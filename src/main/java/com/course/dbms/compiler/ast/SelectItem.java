package com.course.dbms.compiler.ast;

/**
 * SELECT 列表中的一项：要么是普通列，要么是聚合函数调用。
 *   COL：col 为列引用（可限定）。
 *   AGG：func ∈ count/sum/avg/min/max，arg 为参数列（COUNT(*) 时为 null），alias 为输出别名。
 * 输出列名在无别名时：COL→列名；AGG→函数名（count/sum/...）。
 */
public class SelectItem {

    public enum Kind { COL, AGG }

    public final Kind kind;
    public final ColRef col;    // COL：列引用（可限定）；AGG 时为 null
    public final String func;   // AGG：count/sum/avg/min/max；COL 时为 null
    public final ColRef arg;    // AGG：参数列（COUNT(*) 时为 null）
    public final String alias;  // 输出别名（可空）

    private SelectItem(Kind kind, ColRef col, String func, ColRef arg, String alias) {
        this.kind = kind;
        this.col = col;
        this.func = func;
        this.arg = arg;
        this.alias = alias;
    }

    public static SelectItem col(ColRef col, String alias) {
        return new SelectItem(Kind.COL, col, null, null, alias);
    }

    public static SelectItem agg(String func, ColRef arg, String alias) {
        return new SelectItem(Kind.AGG, null, func, arg, alias);
    }

    public boolean isAgg() { return kind == Kind.AGG; }

    /** 最终输出列名（无别名时的默认名）。 */
    public String outputName() {
        if (alias != null) return alias;
        return kind == Kind.AGG ? func : col.name;
    }

    @Override public String toString() {
        if (kind == Kind.AGG) return func + "(" + (arg != null ? arg : "*") + ")" + (alias != null ? " AS " + alias : "");
        return col.toString() + (alias != null ? " AS " + alias : "");
    }
}
