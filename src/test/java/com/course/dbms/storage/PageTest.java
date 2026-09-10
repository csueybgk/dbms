package com.course.dbms.storage;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 步骤1/2：页式存储与缓存机制测试。 */
public class PageTest {

    /** 清理测试目录，避免多次运行累加残留页。 */
    private static void wipe(String dir) {
        File f = new File(dir);
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) x.delete();
    }

    @Test
    public void pageAddAndReadRecords() {
        Page p = new Page(1, 0);
        byte[] a = "alice".getBytes(StandardCharsets.UTF_8);
        byte[] b = "a very long string that spans more than a few bytes".getBytes(StandardCharsets.UTF_8);
        int ia = p.addRecord(a);
        int ib = p.addRecord(b);
        assertEquals(0, ia);
        assertEquals(1, ib);
        assertEquals(2, p.recordCount());
        assertArrayEquals(a, p.getRecord(0));
        assertArrayEquals(b, p.getRecord(1));
    }

    @Test
    public void pageRoundTripThroughBytes() {
        Page p = new Page(3, 7);
        p.addRecord("hello".getBytes(StandardCharsets.UTF_8));
        p.addRecord("world".getBytes(StandardCharsets.UTF_8));
        // 模拟落盘再读回
        Page back = new Page(p.data());
        assertEquals(3, back.tableId());
        assertEquals(7, back.pageNo());
        assertEquals(2, back.recordCount());
        assertEquals("hello", new String(back.getRecord(0), StandardCharsets.UTF_8));
        assertEquals("world", new String(back.getRecord(1), StandardCharsets.UTF_8));
    }

    @Test
    public void pageFullReturnMinusOne() {
        Page p = new Page(1, 0);
        byte[] big = new byte[Page.SIZE]; // 塞满
        // 先放一条很小的，再放超大 => 应失败
        p.addRecord(new byte[10]);
        int r = p.addRecord(big);
        assertEquals(-1, r);
    }

    @Test
    public void diskPersistAndReload() {
        String dir = "target/tmp-disk";
        wipe(dir);
        DiskManager disk = new DiskManager(dir);
        int tid = 42;
        Page p = new Page(tid, 0);
        p.addRecord("persist me".getBytes(StandardCharsets.UTF_8));
        disk.writePage(tid, 0, p.data());
        // 重新建一个 DiskManager 读回（模拟重启）
        DiskManager disk2 = new DiskManager(dir);
        byte[] raw = disk2.readPage(tid, 0);
        assertNotNull(raw);
        Page back = new Page(raw);
        assertEquals("persist me", new String(back.getRecord(0), StandardCharsets.UTF_8));
        assertEquals(1, disk2.pageCount(tid));
        disk.close();
        disk2.close();
    }

    @Test
    public void lruEviction() {
        LruCache cache = new LruCache(3);
        for (int i = 0; i < 3; i++) cache.put(i, new Page(0, i));
        // 访问 id=1 使其成为最新，再插入 id=3 应淘汰最久未用的 id=0
        cache.get(1);
        cache.put(3, new Page(0, 3));
        assertNull(cache.get(0));
        assertNotNull(cache.get(1));
        assertNotNull(cache.get(2));
        assertNotNull(cache.get(3));
    }

    @Test
    public void pageManagerAllocPersistFree() {
        String dir = "target/tmp-pm";
        wipe(dir);
        DiskManager disk = new DiskManager(dir);
        PageManager pm = new PageManager(disk, new LruCache(16));
        int tid = 9;
        int p0 = pm.newPage(tid);
        int p1 = pm.newPage(tid);
        assertEquals(0, p0);
        assertEquals(1, p1);
        Page page = pm.getPage(tid, 0); // miss
        page.addRecord("abc".getBytes(StandardCharsets.UTF_8));
        pm.persist(page);
        assertEquals(1, page.recordCount());
        // 释放最后一页 => 页数减半
        assertEquals(2, pm.pageCount(tid));
        pm.freeLastPage(tid);
        assertEquals(1, pm.pageCount(tid));
        pm.cache().logStats();
        pm.close();
    }

    @Test
    public void cacheHitMissStats() {
        LruCache cache = new LruCache(4);
        Page p = new Page(0, 0);
        cache.put(1L, p);
        cache.get(1L); // hit
        cache.get(2L); // miss
        assertEquals(1, cache.hits());
        assertEquals(1, cache.misses());
    }
}
