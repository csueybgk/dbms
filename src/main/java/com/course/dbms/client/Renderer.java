package com.course.dbms.client;

import com.course.dbms.engine.Result;
import com.course.dbms.engine.table.Row;

/**
 * 把结构化的结果渲染成 ASCII 表格（列头 + 数据行 + 边框），
 * 并在末尾标注行数。纯文本，避免依赖终端特性。
 */
public final class Renderer {

    private Renderer() {}

    public static void render(Result r) {
        if (r.columns.isEmpty()) return;

        int n = r.columns.size();
        int[] w = new int[n];
        for (int i = 0; i < n; i++) w[i] = r.columns.get(i).length();
        for (Row row : r.rows) {
            for (int i = 0; i < n; i++) {
                if (i < row.size()) w[i] = Math.max(w[i], cell(row.get(i)).length());
            }
        }

        border(w);
        header(r, w);
        border(w);
        for (Row row : r.rows) {
            StringBuilder sb = new StringBuilder("|");
            for (int i = 0; i < n; i++) {
                String v = i < row.size() ? cell(row.get(i)) : "";
                sb.append(" ").append(pad(v, w[i])).append(" |");
            }
            System.out.println(sb.toString());
        }
        border(w);
        System.out.println(r.rows.size() + " row(s)");
    }

    private static void header(Result r, int[] w) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < w.length; i++) {
            sb.append(" ").append(pad(r.columns.get(i), w[i])).append(" |");
        }
        System.out.println(sb.toString());
    }

    private static void border(int[] w) {
        StringBuilder sb = new StringBuilder("+");
        for (int width : w) {
            for (int i = 0; i < width + 2; i++) sb.append("-");
            sb.append("+");
        }
        System.out.println(sb.toString());
    }

    /** NULL 渲染为空串（LEFT JOIN 的右列常为 NULL，避免出现字面 "null"）。 */
    private static String cell(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s;
        StringBuilder sb = new StringBuilder(s);
        for (int i = s.length(); i < width; i++) sb.append(" ");
        return sb.toString();
    }
}
