package com.course.dbms.protocol;

import com.course.dbms.engine.Result;
import com.course.dbms.engine.table.Row;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ProtocolTest {

    @Test public void resultRoundTrip() throws Exception {
        List<String> cols = Arrays.asList("id", "name", "score");
        List<Row> rows = Arrays.asList(Row.of(1, "alice", 95.5), Row.of(2, "bob", 88.0));
        Result r = new Result(cols, rows);

        byte[] enc = Encoder.encodeResult(r);
        Result back = Decoder.decodeResult(enc);

        assertEquals(cols, back.columns);
        assertEquals(2, back.rows.size());
        assertEquals("alice", back.rows.get(0).get(1));
        assertEquals("95.5", back.rows.get(0).get(2)); // 值以文本传输
    }

    @Test public void emptyResultRoundTrip() throws Exception {
        Result r = new Result(Arrays.asList("x"), Arrays.asList());
        Result back = Decoder.decodeResult(Encoder.encodeResult(r));
        assertEquals(1, back.columns.size());
        assertEquals(0, back.rows.size());
    }

    /**
     * 单元格里的 NULL 必须原样过线：走 String.valueOf 会把空值变成文本 "null"，
     * 客户端于是既不能按空值渲染，也分不清它和字符串 'null'。
     */
    @Test public void nullValueRoundTrip() throws Exception {
        List<String> cols = Arrays.asList("id", "v", "s");
        List<Row> rows = Arrays.asList(Row.of(1, null, "null"));
        Result back = Decoder.decodeResult(Encoder.encodeResult(new Result(cols, rows)));

        assertEquals(1, back.rows.size());
        assertEquals("1", back.rows.get(0).get(0));
        assertNull(back.rows.get(0).get(1));                   // 真 NULL 读回来还是 null
        assertEquals("null", back.rows.get(0).get(2));         // 字符串 'null' 仍是四个字符
    }

    /** 空串（长度 0）不能被当成 NULL（长度 -1）。 */
    @Test public void emptyStringIsNotNullOnTheWire() throws Exception {
        Result r = new Result(Arrays.asList("s"), Arrays.asList(Row.of("")));
        Object back = Decoder.decodeResult(Encoder.encodeResult(r)).rows.get(0).get(0);
        assertNotNull(back);
        assertEquals("", back);
    }

    @Test public void packagerFrameRoundTrip() throws Exception {
        byte[] body = "select * from t".getBytes("UTF-8");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Packager.write(new DataOutputStream(bos), new Package(false, body));

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
        Package pkg = Packager.read(in);

        assertFalse(pkg.error);
        assertEquals("select * from t", new String(pkg.payload, "UTF-8"));
    }
}
