package com.course.dbms.protocol;

import com.course.dbms.engine.Result;
import com.course.dbms.engine.table.Row;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 解码器：把字节负载还原成 {@link Result}（对应 {@link Encoder} 的布局）。 */
public final class Decoder {

    private Decoder() {}

    public static Result decodeResult(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        int nc = in.readInt();
        List<String> cols = new ArrayList<>();
        for (int i = 0; i < nc; i++) cols.add(readString(in));
        int nr = in.readInt();
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < nr; i++) {
            List<Object> vals = new ArrayList<>();
            for (int j = 0; j < nc; j++) vals.add(readValue(in));
            rows.add(new Row(vals));
        }
        return new Result(cols, rows);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    /**
     * 单元格：长度 -1 表示 NULL（与 {@link Encoder#writeValue} 对应）。
     * 不能走 readString —— new byte[-1] 会抛 NegativeArraySizeException，
     * 那是 RuntimeException，会越过 {@code catch (IOException)} 掐断连接。
     */
    private static Object readValue(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) return null;
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
