package com.course.dbms.db;

import com.course.dbms.common.Error;
import com.course.dbms.engine.Result;
import com.course.dbms.engine.table.Row;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;

public class DatabaseTest {

    private Database db;
    private final String dir = "target/tmp-db";

    @Before public void setup() {
        wipe(dir);
        db = new Database(dir);
    }
    @After public void teardown() { db.close(); }

    private static void wipe(String d) {
        File f = new File(d);
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) x.delete();
    }

    private void seed() {
        db.execute("create table users (id int32, name string, age int32, score float64)");
        db.execute("insert into users values (1, 'alice', 23, 95.5)");
        db.execute("insert into users values (2, 'bob', 30, 88.0)");
        db.execute("insert into users values (3, 'carol', 22, 91.5)");
    }

    @Test public void createInsertSelectShow() {
        seed();

        Result r = db.execute("select * from users");
        assertEquals(3, r.rows.size());
        assertEquals(4, r.columns.size());
        assertEquals("id", r.columns.get(0));
        assertEquals("score", r.columns.get(3));

        // 投影 + WHERE + 排序：score 降序 → alice(95.5) carol(91.5) bob(88.0)
        r = db.execute("select id, name from users order by score desc");
        assertEquals(3, r.rows.size());
        assertEquals("id", r.columns.get(0));
        assertEquals("name", r.columns.get(1));
        assertEquals(1L, ((Number) r.rows.get(0).get(0)).longValue());
        assertEquals(3L, ((Number) r.rows.get(1).get(0)).longValue());
        assertEquals(2L, ((Number) r.rows.get(2).get(0)).longValue());

        // WHERE：age>24 只有 bob
        r = db.execute("select id from users where age > 24");
        assertEquals(1, r.rows.size());
        assertEquals(2L, ((Number) r.rows.get(0).get(0)).longValue());

        // show tables
        r = db.execute("show tables");
        assertEquals(1, r.rows.size());
        assertEquals("users", r.rows.get(0).get(0));

        // show table users
        r = db.execute("show table users");
        assertEquals(4, r.rows.size());
        assertEquals("id", r.rows.get(0).get(0));
        assertEquals("int32", r.rows.get(0).get(1));
        assertEquals("float64", r.rows.get(3).get(1));
    }

    @Test public void whereAndOr() {
        seed();
        // age>20 AND score>90 → alice(23,95.5), carol(22,91.5)
        Result r = db.execute("select id from users where age > 20 and score > 90");
        assertEquals(2, r.rows.size());
        assertEquals(1L, ((Number) r.rows.get(0).get(0)).longValue());
        assertEquals(3L, ((Number) r.rows.get(1).get(0)).longValue());
    }

    @Test public void persistenceAcrossReopen() {
        db.execute("create table t (a int32, b string)");
        db.execute("insert into t values (1, 'x')");
        db.execute("insert into t values (2, 'y')");
        db.close();

        db = new Database(dir);
        Result r = db.execute("select * from t");
        assertEquals(2, r.rows.size());
        assertEquals("x", r.rows.get(0).get(1));
        assertEquals("y", r.rows.get(1).get(1));

        // 目录也恢复
        Result sr = db.execute("show tables");
        assertEquals(1, sr.rows.size());
    }

    @Test public void semicolonTolerance() {
        db.execute("create table s (a int32)");
        Result r = db.execute("insert into s values (1);");
        assertEquals(1, r.rows.size());
        r = db.execute("select * from s;;");
        assertEquals(1, r.rows.size());
    }

    @Test public void errorPropagates() {
        seed();
        try {
            db.execute("select * from ghost");
            fail("should throw");
        } catch (Error e) {
            assertTrue(e.code().startsWith("TB"));
        }
        try {
            db.execute("select * from users where nosuch > 1");
            fail("should throw");
        } catch (Error e) {
            assertTrue(e.code().startsWith("SE"));
        }
    }
}
