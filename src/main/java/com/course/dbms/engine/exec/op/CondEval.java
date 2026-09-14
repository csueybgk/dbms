package com.course.dbms.engine.exec.op;

import com.course.dbms.compiler.ast.Cond;
import com.course.dbms.engine.storage.StorageEngine;
import com.course.dbms.engine.table.Row;
import com.course.dbms.engine.table.Schema;

/**
 * 条件求值：供 Filter（WHERE 逐行判定）与 Join（ON 逐对判定）共用。
 * 支持两种 CMP 分支：
 *   - 列 vs 字面量（WHERE 常用）：字面量按左列类型 cast 后再比较。
 *   - 列 vs 列（JOIN ON / 两列比较）：直接取两列值比较，无需 cast。
 * 列引用经 {@link Resolver} 解析成行下标（可处理限定名 / 裸名歧义）。
 */
public final class CondEval {

    /** 把 (限定符,列名) 解析成行下标；未找到/歧义抛 Error。 */
    public interface Resolver {
        int resolve(String qualifier, String name);
    }

    private CondEval() {}

    public static boolean eval(Cond c, Schema castSchema, Row row, Resolver r) {
        return Boolean.TRUE.equals(evalTri(c, castSchema, row, r));
    }

    /**
     * 三值逻辑求值：TRUE / FALSE / null（UNKNOWN）。
     *
     * 为什么需要它：CHECK 约束里"求值为 UNKNOWN"必须【放行】（SQL 语义如此），
     * 而 {@link #eval} 把 UNKNOWN 压成了 false，用它判 CHECK 会把
     * `check (score >= 0)` 在 score 为 NULL 的行上误判成违反约束。
     *
     * 与 eval 的等价性：CMP 遇 NULL 操作数时 Compare.apply 返回 false，evalTri 返回 null，
     * TRUE.equals(null) 为 false —— 一致；AND/OR 的短路结果也一致（apply 对 NULL 只会返回
     * false）。所以 eval 退化成本方法的包装不改变任何既有行为。
     */
    public static Boolean evalTri(Cond c, Schema castSchema, Row row, Resolver r) {
        switch (c.kind) {
            case CMP: {
                int i1 = r.resolve(c.qualifier, c.column);
                Object v1 = row.get(i1);
                if (c.column2 != null) {
                    int i2 = r.resolve(c.qualifier2, c.column2);
                    return tri(Compare.apply(c.op, v1, row.get(i2)), v1, row.get(i2));
                }
                // 把 WHERE 字面量规整到左列类型，保证比较口径一致（已在语义层验证可 cast）
                Object lit = StorageEngine.cast(castSchema, i1, c.value);
                return tri(Compare.apply(c.op, v1, lit), v1, lit);
            }
            case AND:
                for (Cond child : c.children) {
                    Boolean b = evalTri(child, castSchema, row, r);
                    if (Boolean.FALSE.equals(b)) return Boolean.FALSE;
                    if (b == null) return null;                  // 有 UNKNOWN 且无 FALSE → UNKNOWN
                }
                return Boolean.TRUE;
            case OR:
                for (Cond child : c.children) {
                    Boolean b = evalTri(child, castSchema, row, r);
                    if (Boolean.TRUE.equals(b)) return Boolean.TRUE;
                    if (b == null) return null;                  // 有 UNKNOWN 且无 TRUE → UNKNOWN
                }
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    /** 操作数含 NULL 时比较结果是 UNKNOWN（null），否则就是比较结果的 TRUE/FALSE。 */
    private static Boolean tri(boolean result, Object a, Object b) {
        if (a == null || b == null) return null;
        return result ? Boolean.TRUE : Boolean.FALSE;
    }
}
