package com.course.dbms.engine;

import com.course.dbms.engine.index.Index;
import com.course.dbms.engine.index.IndexManager;
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

/**
 * NULL 的存储层往返测试。
 *
 * 记录格式里每列是 {@code [4字节长度][字节]}，长度 {@code -1} 是空值标记
 * （没有后续字节）。这里要锁住三条容易被改坏的边界：
 *   1) 空值【不调用】FieldType.encode —— 那边对 null 会拆箱 NPE；
 *   2) 读回时 {@code len < 0} 必须直接给 null —— 否则 {@code new byte[-1]} 抛的
 *      NegativeArraySizeException 会越过 {@code catch (IOException)} 掐断客户端连接；
 *   3) 空串（len = 0）与 NULL（len = -1）不是一回事。
 *
 * 另有一个索引侧的前提：NULL 键不能进 B+ 树，否则 {@code search(null)} 会命中整棵树。
 */
public class NullStorageTest {

    private StorageEngine se;
    private PageManager pm;
    private final String dir = "target/tmp-nullstore";

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

    /** 覆盖全部 6 种类型，列序固定，断言按下标写。 */
    private static Table allTypes() {
        return new Table(10, "t", new Schema()
                .add("i32", FieldType.INT32)
                .add("i64", FieldType.INT64)
                .add("f64", FieldType.FLOAT64)
                .add("b", FieldType.BOOL)
                .add("s", FieldType.STRING)
                .add("dt", FieldType.DATETIME));
    }

    @Test public void nullRoundTripsForEveryType() {
        Table t = allTypes();
        se.insert(t, Row.of(null, null, null, null, null, null));

        List<Row> rows = se.scan(t);
        assertEquals(1, rows.size());
        for (int i = 0; i < 6; i++) {
            assertNull("column " + i + " should be NULL", rows.get(0).get(i));
        }
    }

    @Test public void nullAndNonNullValuesCoexist() {
        Table t = allTypes();
        se.insert(t, Row.of(1, null, 2.5, null, "x", null));
        se.insert(t, Row.of(null, 2L, null, Boolean.TRUE, null, 7L));

        List<Row> rows = se.scan(t);
        assertEquals(2, rows.size());

        Row a = rows.get(0);
        assertEquals(1, a.get(0));
        assertNull(a.get(1));
        assertEquals(2.5, (Double) a.get(2), 1e-9);
        assertNull(a.get(3));
        assertEquals("x", a.get(4));
        assertNull(a.get(5));

        Row b = rows.get(1);
        assertNull(b.get(0));
        assertEquals(2L, b.get(1));
        assertNull(b.get(2));
        assertEquals(Boolean.TRUE, b.get(3));
        assertNull(b.get(4));
        assertEquals(7L, b.get(5));
    }

    /** 空串是 len = 0，NULL 是 len = -1：两者必须能区分。 */
    @Test public void emptyStringIsNotNull() {
        Table t = allTypes();
        se.insert(t, Row.of(null, null, null, null, "", null));

        List<Row> rows = se.scan(t);
        assertEquals(1, rows.size());
        assertNotNull(rows.get(0).get(4));
        assertEquals("", rows.get(0).get(4));
        assertNull(rows.get(0).get(0));
    }

    /** 非空值的编码字节完全没变：老方法读新写的行不受影响。 */
    @Test public void nonNullRoundTripUnchanged() {
        Table t = allTypes();
        se.insert(t, Row.of(42, 99L, 1.5, Boolean.FALSE, "hello", 1234L));

        Row r = se.scan(t).get(0);
        assertEquals(42, r.get(0));
        assertEquals(99L, r.get(1));
        assertEquals(1.5, (Double) r.get(2), 1e-9);
        assertEquals(Boolean.FALSE, r.get(3));
        assertEquals("hello", r.get(4));
        assertEquals(1234L, r.get(5));
    }

    /** NULL 标记必须真的落进页里，重新打开页面管理器后仍读得回来。 */
    @Test public void nullsSurviveReopen() {
        Table t = allTypes();
        se.insert(t, Row.of(null, null, null, null, null, null));
        se.insert(t, Row.of(5, 6L, 7.0, Boolean.TRUE, "s", 8L));
        pm.close();

        pm = new PageManager(new DiskManager(dir), new LruCache(32));
        se = new StorageEngine(pm);

        List<Row> rows = se.scan(t);
        assertEquals(2, rows.size());
        for (int i = 0; i < 6; i++) assertNull(rows.get(0).get(i));
        assertEquals(5, rows.get(1).get(0));
        assertEquals("s", rows.get(1).get(4));
    }

    /** 全表都是 NULL 的行也一样能扫描出来（记录长度固定，只是每列少 4 字节）。 */
    @Test public void scanLocatedSeesNullRows() {
        Table t = allTypes();
        se.insert(t, Row.of(null, null, null, null, null, null));
        List<StorageEngine.Located> loc = se.scanLocated(t);
        assertEquals(1, loc.size());
        assertNull(loc.get(0).row().get(0));
    }

    /**
     * 索引侧前提：NULL 键不入 B+ 树。
     * 反证 —— {@code search(null)} 在 B+ 树里表示"该侧无界"，会返回【整棵树】。
     * 一旦把 NULL 也插进去，`where k = null` 就会命中所有含 NULL 的行。
     */
    @Test public void nullKeyIsSkippedByIndex() {
        Table t = new Table(11, "ti", new Schema()
                .add("k", FieldType.INT32)
                .add("s", FieldType.STRING));
        se.insert(t, Row.of(1, "a"));
        se.insert(t, Row.of(null, "b"));
        se.insert(t, Row.of(2, "c"));

        IndexManager im = new IndexManager(se);
        Index idx = im.createIndex("idx_k", t, "k");

        assertEquals(2, idx.size());                    // 3 行里只有 2 个非 NULL 键入树
        assertEquals(2, idx.search(null).size());       // search(null) = 全树，故 NULL 绝不能入树
        assertEquals(1, idx.search(1).size());
        assertEquals(1, idx.search(2).size());

        im.onInsert(t, Row.of(null, "d"), 0, 99);       // 插入钩子同样跳过
        assertEquals(2, idx.size());
    }

    /** 建索引时表里已经有 NULL 行（重建索引的路径）也不能带上 NULL 键。 */
    @Test public void rebuildSkipsNullKeys() {
        Table t = new Table(12, "tr", new Schema()
                .add("k", FieldType.INT32)
                .add("s", FieldType.STRING));
        se.insert(t, Row.of(null, "a"));
        se.insert(t, Row.of(7, "b"));

        IndexManager im = new IndexManager(se);
        im.createIndex("idx_r", t, "k");
        assertEquals(1, im.findFor(t, "k").size());

        im.rebuild(t);                                  // 重建走的是同一条扫描路径
        assertEquals(1, im.findFor(t, "k").size());
        assertEquals(1, im.findFor(t, "k").search(7).size());
    }
}
