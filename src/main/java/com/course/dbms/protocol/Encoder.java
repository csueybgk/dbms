package com.course.dbms.protocol;

import com.course.dbms.common.Error;
import com.course.dbms.engine.Result;
import com.course.dbms.engine.table.Row;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 编码器：把结果/错误序列化成字节负载。
 *
 * Result 的二进制布局：
 *   [int 列数] { [int 长度][utf8 列名] }*列数
 *   [int 行数] { [int 长度][utf8 值文本] }*（行数*列数）
 * 值取 String.valueOf，布尔/数值/字符串统一转文本传输，客户端只负责展示。
 */
public final class Encoder {

    private Encoder() {}

    public static byte[] encodeResult(Result r) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(r.columns.size());
        for (String c : r.columns) writeString(out, c);
        out.writeInt(r.rows.size());
        for (Row row : r.rows) {
            for (int i = 0; i < r.columns.size(); i++) {
                writeString(out, String.valueOf(row.get(i)));
            }
        }
        out.flush();
        return bos.toByteArray();
    }

    public static byte[] encodeError(Error e) {
        return e.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }
}
