package com.course.dbms.server;

import com.course.dbms.db.Database;
import com.course.dbms.engine.Result;
import com.course.dbms.protocol.Decoder;
import com.course.dbms.protocol.Package;
import com.course.dbms.protocol.Packager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.Socket;

import static org.junit.Assert.*;

public class ServerClientTest {

    private Database db;
    private Server server;
    private final String dir = "target/tmp-server";

    @Before public void setup() throws Exception {
        wipe(dir);
        db = new Database(dir);
        server = new Server(db, 0);   // 端口 0：系统分配一个可用端口
        server.start();
    }
    @After public void teardown() throws Exception {
        server.close();
        db.close();
    }

    private static void wipe(String d) {
        File f = new File(d);
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) x.delete();
    }

    private Result exec(int port, String sql) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port);
             DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()))) {
            Packager.write(out, new Package(false, sql.getBytes("UTF-8")));
            Package pkg = Packager.read(in);
            assertFalse("should not error: " + (pkg.error ? new String(pkg.payload, "UTF-8") : ""), pkg.error);
            return Decoder.decodeResult(pkg.payload);
        }
    }

    @Test public void roundTripCreateInsertSelect() throws IOException {
        int port = server.port();
        Result r = exec(port, "create table users (id int32, name string, age int32, score float64)");
        assertEquals(1, r.rows.size());

        exec(port, "insert into users values (1, 'alice', 23, 95.5)");
        exec(port, "insert into users values (2, 'bob', 30, 88.0)");

        r = exec(port, "select id, name from users where age > 24 order by score desc");
        assertEquals(1, r.rows.size());
        assertEquals("id", r.columns.get(0));
        assertEquals("bob", r.rows.get(0).get(1));
    }

    @Test public void errorFlagRoundTrip() throws IOException {
        int port = server.port();
        try (Socket s = new Socket("127.0.0.1", port);
             DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()))) {
            Packager.write(out, new Package(false, "select * from ghost".getBytes("UTF-8")));
            Package pkg = Packager.read(in);
            assertTrue(pkg.error);
            String msg = new String(pkg.payload, "UTF-8");
            assertTrue(msg.contains("TB-0001"));
        }
    }
}
