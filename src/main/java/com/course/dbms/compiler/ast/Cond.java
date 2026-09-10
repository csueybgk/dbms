package com.course.dbms.compiler.ast;

import java.util.List;

/**
 * WHERE / ON 条件节点。
 * CMP  ：单条比较。两种形态：
 *          column op value            —— 列 vs 字面量（value 为 truthy/数值/字符串/布尔）
 *          [q.]column op [q2.]column2 —— 列 vs 列（JOIN ON，column2!=null 时）
 * AND/OR：逻辑组合，children 为其子条件。
 * 执行引擎的 Filter / Join 算子据此逐行判定。
 */
public class Cond {

    public enum Kind { CMP, AND, OR }

    public final Kind kind;
    public final String column;   // CMP 用：左列名（裸名）
    public final String op;       // CMP 用：= <> < > <= >=
    public final Object value;    // CMP 用：列 vs 字面量 的右值（列 vs 列时为 null）
    public final List<Cond> children; // AND/OR 用
    public final String qualifier;   // CMP：左列限定符（可空）
    public final String column2;     // CMP 列-列：右列名（可空=与字面量比较）
    public final String qualifier2;  // CMP 列-列：右列限定符（可空）

    private Cond(Kind kind, String column, String op, Object value, List<Cond> children,
                 String qualifier, String column2, String qualifier2) {
        this.kind = kind;
        this.column = column;
        this.op = op;
        this.value = value;
        this.children = children;
        this.qualifier = qualifier;
        this.column2 = column2;
        this.qualifier2 = qualifier2;
    }

    /** 列 vs 字面量（WHERE 常用）。 */
    public static Cond cmp(String column, String op, Object value) {
        return new Cond(Kind.CMP, column, op, value, null, null, null, null);
    }

    /** 限定列 vs 字面量。 */
    public static Cond cmp(String qualifier, String column, String op, Object value) {
        return new Cond(Kind.CMP, column, op, value, null, qualifier, null, null);
    }

    /** 列 vs 列（多表联查 ON / 两列比较）。 */
    public static Cond cmp2(String q1, String col1, String op, String q2, String col2) {
        return new Cond(Kind.CMP, col1, op, null, null, q1, col2, q2);
    }

    public static Cond and(List<Cond> cs) { return new Cond(Kind.AND, null, null, null, cs, null, null, null); }
    public static Cond or(List<Cond> cs)  { return new Cond(Kind.OR, null, null, null, cs, null, null, null); }

    @Override public String toString() {
        if (kind == Kind.CMP) {
            String left = qualifier != null ? qualifier + "." + column : column;
            if (column2 != null) {
                String right = qualifier2 != null ? qualifier2 + "." + column2 : column2;
                return left + " " + op + " " + right;
            }
            return left + " " + op + " " + value;
        }
        return kind.name() + " " + children;
    }
}
