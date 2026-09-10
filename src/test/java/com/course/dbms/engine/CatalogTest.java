package com.course.dbms.engine;

import com.course.dbms.common.Error;
import com.course.dbms.engine.storage.StorageEngine;
import com.course.dbms.engine.table.Catalog;
import com.course.dbms.engine.table.FieldType;
import com.course.dbms.engine.table.Schema;
import com.course.dbms.engine.table.Table;
import com.course.dbms.storage.DiskManager;
import com.course.dbms.storage.LruCache;
import com.course.dbms.storage.PageManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

public class CatalogTest {

    private Catalog cat;
    private PageManager pm;
    private final String dir = "target/tmp-catalog";

    @Before public void setup() {
        wipe(dir);
        pm = new PageManager(new DiskManager(dir), new LruCache(32));
        cat = new Catalog(new StorageEngine(pm));
    }
    @After public void teardown() { pm.close(); }

    private static void wipe(String d) {
        File f = new File(d);
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) x.delete();
    }

    @Test public void createAndRetrieve() {
        Table t = cat.createTable("users", new Schema()
                .add("id", FieldType.INT32)
                .add("name", FieldType.STRING)
                .add("age", FieldType.INT32));
        assertTrue(cat.exists("users"));
        Table got = cat.getTable("users");
        assertEquals("users", got.name());
        assertEquals(3, got.schema().columnCount());
        assertEquals(FieldType.INT32, got.schema().typeOf("id"));
        assertEquals(FieldType.STRING, got.schema().typeOf("name"));
        assertEquals(1, cat.tableCount());
    }

    @Test public void createDuplicateFails() {
        cat.createTable("t1", new Schema().add("id", FieldType.INT32));
        try {
            cat.createTable("T1", new Schema().add("id", FieldType.INT32)); // 大小写不敏感
            fail("should throw");
        } catch (Error e) {
            assertTrue(e.code().startsWith("CT"));
        }
    }

    @Test public void getMissingFails() {
        try {
            cat.getTable("nope");
            fail("should throw");
        } catch (Error e) {
            assertTrue(e.code().startsWith("TB"));
        }
    }

    @Test public void catalogPersistenceReopen() {
        cat.createTable("students", new Schema().add("id", FieldType.INT32).add("gpa", FieldType.FLOAT64));
        cat.createTable("courses", new Schema().add("cid", FieldType.INT32));
        pm.close();
        pm = new PageManager(new DiskManager(dir), new LruCache(32));
        cat = new Catalog(new StorageEngine(pm));
        assertEquals(2, cat.tableCount());
        assertEquals(FieldType.FLOAT64, cat.getTable("students").schema().typeOf("gpa"));
        assertEquals(FieldType.INT32, cat.getTable("courses").schema().typeOf("cid"));
    }
}
