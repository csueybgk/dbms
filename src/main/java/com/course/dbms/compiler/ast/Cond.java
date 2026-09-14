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

    /**
     * 还原成【可重新解析】的 SQL 文本（CHECK 约束落盘后再读回来用）。
     * 与 {@link #toString()} 的区别只在字面量：toString 不加引号，'alice' 会渲染成
     * alice，重新解析时走成"列 vs 列"分支，静默变成另一个条件。toString 被
     * Operator.name() / Tracer 依赖，不能改，故另开一个方法。
     *
     * 子条件平铺、不加括号（parseCmp 以 parseColRef 开头，本就解析不了 `(a > 1)`）：
     * 文法里 AND 比 OR 结合更紧，本解析器只会产出 "OR of ANDs of CMP" 这一种形状，
     * 平铺即可精确还原。
     */
    public String toSql() {
        if (kind == Kind.CMP) {
            String left = qualifier != null ? qualifier + "." + column : column;
            String right = column2 != null
                    ? (qualifier2 != null ? qualifier2 + "." + column2 : column2)
                    : literalSql(value);
            return left + " " + op + " " + right;
        }
        StringBuilder sb = new StringBuilder();
        if (children != null) {
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) sb.append(kind == Kind.AND ? " and " : " or ");
                sb.append(children.get(i).toSql());
            }
        }
        return sb.toString();
    }

    private static String literalSql(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return "'" + v + "'";   // 词法器不支持转义，值里不可能含 '
        return String.valueOf(v);
    }
}
