package com.course.dbms.storage;

import com.course.dbms.common.Error;
import com.course.dbms.txn.Txn;

import java.util.Arrays;
import java.util.Map;

/**
 * 页管理器：页式存储的对外接口（页分配/释放/读/写）。
 * 内部用 {@link Cache} 缓存页、用 {@link DiskManager} 落盘。
 *
 * 事务感知：通过 {@link Txn#current()} 判断当前线程是否处于活动事务。
 *   - 无事务 → 与之前完全一样（直接读写共享缓存/磁盘），供裸调用/测试路径；
 *   - 有事务 → getPage 返回【深拷贝】进事务工作集；persist/newPage 只进工作集，
 *              不动共享缓存/磁盘；COMMIT 才把工作集写盘，ROLLBACK 直接丢弃。
 *
 * 对应图片"② 存储系统 - 页式存储：页分配/释放/读写"，并作为"提供给数据库模块调用"的接口。
 */
public class PageManager {

    private final DiskManager disk;
    private final Cache cache;
    private final Log log;

    public PageManager(DiskManager disk, Cache cache) {
        this.disk = disk;
        this.cache = cache;
        this.log = new Log(disk.dir());
        // 对外服务前先做崩溃恢复：重放已提交事务（redo），丢弃未提交事务
        this.log.recover(disk);
    }

    /** 复合键：高 32 位 tableId，低 32 位 pageNo。 */
    private static long key(int tableId, int pageNo) {
        return ((long) tableId << 32) | (pageNo & 0xffffffffL);
    }

    /** 取页：命中缓存直接返回，未命中从磁盘加载并放入缓存。事务内返回工作集深拷贝。 */
    public Page getPage(int tableId, int pageNo) {
        long k = key(tableId, pageNo);
        Txn t = Txn.current();
        if (t != null && t.active()) {
            Page inTxn = t.get(tableId, pageNo);
            if (inTxn != null) return inTxn;                        // read-your-writes
            Page shared = cache.get(k);
            byte[] raw = shared != null ? shared.data() : disk.readPage(tableId, pageNo);
            if (raw == null) return null;
            // 关键：深拷贝。Page(byte[]) 不拷贝底层数组，浅拷贝会改到共享缓存里的页。
            Page copy = new Page(Arrays.copyOf(raw, Page.SIZE));
            t.put(tableId, pageNo, copy);                            // 只进工作集，不进共享缓存
            return copy;
        }
        Page p = cache.get(k);
        if (p != null) return p;
        byte[] raw = disk.readPage(tableId, pageNo);
        if (raw == null) return null;
        Page pg = new Page(raw);
        cache.put(k, pg);
        return pg;
    }

    /** 分配一页：追加到表末尾，返回新页号。事务内只进工作集，不写盘。 */
    public int newPage(int tableId) {
        Txn t = Txn.current();
        if (t != null && t.active()) {
            int pageNo = disk.pageCount(tableId) + t.newCount(tableId);
            Page pg = new Page(tableId, pageNo);
            t.put(tableId, pageNo, pg);
            t.bumpNewCount(tableId);
            return pageNo;
        }
        int pageNo = disk.pageCount(tableId);
        Page pg = new Page(tableId, pageNo);
        disk.writePage(tableId, pageNo, pg.data());
        cache.put(key(tableId, pageNo), pg);
        return pageNo;
    }

    /** 把某页的最新内容刷盘（引擎改写页后调用）。事务内只记进工作集，不落盘。 */
    public void persist(Page page) {
        Txn t = Txn.current();
        if (t != null && t.active()) {
            t.put(page.tableId(), page.pageNo(), page);               // 只进工作集
            return;
        }
        disk.writePage(page.tableId(), page.pageNo(), page.data());
        cache.put(key(page.tableId(), page.pageNo()), page);
    }

    /** 释放表末尾一页（回收其磁盘空间）。事务内不支持（SQL 语法不会触发，防御性报错）。 */
    public void freeLastPage(int tableId) {
        Txn t = Txn.current();
        if (t != null && t.active()) {
            throw new Error("TX-0004", "freeLastPage cannot run inside a transaction");
        }
        int last = disk.pageCount(tableId) - 1;
        if (last < 0) return;
        cache.remove(key(tableId, last));
        disk.truncateLastPage(tableId);
    }

    /** 某表当前页数。事务内计入本事务新增页，使 insert/scan 能读到自己的新页。 */
    public int pageCount(int tableId) {
        Txn t = Txn.current();
        if (t != null && t.active()) {
            return disk.pageCount(tableId) + t.newCount(tableId);
        }
        return disk.pageCount(tableId);
    }

    // ---- 事务提交/回滚（由 Session 在 COMMIT/ROLLBACK 时调用） ----

    /** 提交：先把工作集写进 WAL（fsync），再把页写盘 + 刷进共享缓存，然后清空工作集。 */
    public void commitTxn(Txn t) {
        if (t == null) return;
        // 先写日志并 fsync：此刻提交已持久且原子（有完整 COMMIT 标记）；之后崩在落页也能重放
        log.writeCommit(t.id(), t.pages());
        for (Map.Entry<Long, Page> e : t.pages().entrySet()) {
            long k = e.getKey();
            Page pg = e.getValue();
            // 超出 EOF 处写会自动扩展文件 → 新页即使乱序写也能正确落盘
            disk.writePage(pg.tableId(), pg.pageNo(), pg.data());
            cache.put(k, pg);
        }
        t.reset();
    }

    /** 回滚：丢弃事务工作集。从未碰共享缓存/磁盘，无需恢复。 */
    public void rollbackTxn(Txn t) {
        if (t == null) return;
        t.reset();
    }

    public Cache cache() { return cache; }
    public DiskManager disk() { return disk; }

    /** 关闭：落盘并关闭所有文件。 */
    public void close() {
        log.close();
        disk.close();
    }
}
