package com.course.dbms.engine;

import com.course.dbms.common.Error;
import com.course.dbms.engine.storage.StorageEngine;
import com.course.dbms.engine.table.FieldType;
import com.course.dbms.engine.table.Row;
import com.course.dbms.engine.table.Schema;
import com.course.dbms.engine.table.Table;
import com.course.dbms.storage.DiskManager;
import com.course.dbms.storage.LruCache;
import com.course.dbms.storage.PageManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;

public class StorageTest {

    private StorageEngine se;
    private PageManager pm;
    private final String dir = "target/tmp-storage";

    @Before public void setup() {
        wipe(dir);
        pm = new PageManager(new DiskManager(dir), new LruCache(32));
        se = new StorageEngine(pm);
    }
    @After public void teardown() { pm.close(); }

    private static void wipe(String d) {
        File f = new File(d);
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) x.delete();
    }

    private Table users() {
        return new Table(10, "users", new Schema()
                .add("id", FieldType.INT32)
                .add("name", FieldType.STRING)
                .add("age", FieldType.INT32)
                .add("score", FieldType.FLOAT64));
    }

    @Test public void insertAndScan() {
        Table t = users();
        se.insert(t, Row.of(1, "alice", 23, 95.5));
        se.insert(t, Row.of(2, "bob", 30, 88.0));
        List<Row> rows = se.scan(t);
        assertEquals(2, rows.size());
        assertEquals(1, rows.get(0).get(0));
        assertEquals("alice", rows.get(0).get(1));
        assertEquals(23, rows.get(0).get(2));
        assertEquals(95.5, rows.get(0).get(3));
        assertEquals("bob", rows.get(1).get(1));
    }

    @Test public void crossPageWithLongStrings() {
        Table t = new Table(11, "docs", new Schema().add("id", FieldType.INT32).add("body", FieldType.STRING));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2000; i++) big.append('x'); // 长字符串撑满一页
        se.insert(t, Row.of(1, big.toString()));
        se.insert(t, Row.of(2, "short"));
        List<Row> rows = se.scan(t);
        assertEquals(2, rows.size());
        assertEquals(big.toString(), rows.get(0).get(1));
        assertEquals("short", rows.get(1).get(1));
    }

    @Test public void persistenceAcrossReopen() {
        Table t = users();
        se.insert(t, Row.of(1, "alice", 23, 95.5));
        pm.close();
        // 重新打开：DiskManager 重读文件
        pm = new PageManager(new DiskManager(dir), new LruCache(32));
        se = new StorageEngine(pm);
        // 关键：把表元信息（schema）重建后再扫描，数据仍在
        Table t2 = users();
        List<Row> rows = se.scan(t2);
        assertEquals(1, rows.size());
        assertEquals("alice", rows.get(0).get(1));
    }
}
