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
        switch (c.kind) {
            case CMP: {
                int i1 = r.resolve(c.qualifier, c.column);
                Object v1 = row.get(i1);
                if (c.column2 != null) {
                    int i2 = r.resolve(c.qualifier2, c.column2);
                    return Compare.apply(c.op, v1, row.get(i2));
                }
                // 把 WHERE 字面量规整到左列类型，保证比较口径一致（已在语义层验证可 cast）
                Object lit = StorageEngine.cast(castSchema, i1, c.value);
                return Compare.apply(c.op, v1, lit);
            }
            case AND:
                for (Cond child : c.children) if (!eval(child, castSchema, row, r)) return false;
                return true;
            case OR:
                for (Cond child : c.children) if (eval(child, castSchema, row, r)) return true;
                return false;
            default:
                return false;
        }
    }
}
