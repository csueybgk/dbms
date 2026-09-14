package com.course.dbms.engine.exec.op;

import com.course.dbms.common.Error;
import com.course.dbms.common.ErrorCode;

/**
 * 值比较与谓词判定。数值类型统一按 double 比较，布尔按布尔序，其余按字符串序。
 *
 * NULL 有两条【相反】的规则，因此拆成两个方法，不能共用一个实现：
 *   compare —— 排序（Sort）与索引定位（BPlusTree）用。NULL 必须有确定位置，
 *              这里排在最后（NULLS LAST，同 PostgreSQL；Sort 降序时取反，
 *              NULL 自然落到最前，与 PostgreSQL 的 NULLS FIRST 一致）。
 *   apply   —— 谓词判定（WHERE / ON）用。SQL 三值逻辑下与 NULL 的任何比较都是
 *              UNKNOWN，而 WHERE 只保留 TRUE，故一律返回 false。
 *
 * 注：表的列值可以为 NULL（SQL 有 NULL 字面量，磁盘用 len=-1 标记）。NULL 来自
 * 显式插入、DEFAULT NULL，或未被 DEFAULT 覆盖的缺省列；Join 为 LEFT JOIN 未匹配
 * 行合成的右列同样是 NULL。索引键不含 NULL —— IndexManager 建树/追加时会跳过。
 */
public final class Compare {

    private Compare() {}

    /** 返回 a 与 b 的比较符号（-1 左小，0 相等，1 左大）。NULL 视为最大。 */
    public static int compare(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;                        // NULL 排最后
        if (b == null) return -1;
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (a instanceof Boolean && b instanceof Boolean) {
            return ((Boolean) a).compareTo((Boolean) b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    /**
     * 按 op 判定 a op b 是否成立。op 取值：= <> < > <= >=
     * 任一操作数为 NULL 时返回 false（对应 SQL 的 UNKNOWN，WHERE/ON 不保留该行）。
     */
    public static boolean apply(String op, Object a, Object b) {
        if (a == null || b == null) return false;
        int c = compare(a, b);
        switch (op) {
            case "=":  return c == 0;
            case "<>": return c != 0;
            case "<":  return c < 0;
            case ">":  return c > 0;
            case "<=": return c <= 0;
            case ">=": return c >= 0;
            default: throw new Error(ErrorCode.EX_UNKNOWN_OPERATOR,
                    "unknown operator: " + op + "（未知的比较运算符：可用的只有 = <> < > <= >=）");
        }
    }
}
