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
