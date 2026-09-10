package com.course.dbms.txn;

import com.course.dbms.storage.Page;
import com.course.dbms.storage.PageManager;

import java.util.HashMap;
import java.util.Map;

/**
 * 一次事务：会话内可选的"影子页工作集"。
 *
 * 它通过 {@link ThreadLocal} 挂在"当前执行线程"上（每连接一个线程），
 * 深层存储 {@link PageManager} 在执行时读 {@link #current()} 判断：
 *   - 无活动事务 → 走原有路径（直接读写共享缓存/磁盘），行为与之前完全一致；
 *   - 有活动事务 → getPage 返回【深拷贝】进工作集；persist/newPage 只记进工作集
 *                   不动共享缓存/磁盘。COMMIT 才把工作集写盘；ROLLBACK 直接丢弃。
 *
 * 由此实现：事务内改动对其他会话不可见(read-your-writes + 隔离)，回滚干净。
 */
public class Txn {

    public enum State { ACTIVE, COMMITTED, ABORTED }

    private static final ThreadLocal<Txn> CURRENT = new ThreadLocal<>();

    private final long id;                            // 锁管理器里的 txnId（每次事务唯一）
    private State state = State.ACTIVE;
    // 影子页：key=(tableId<<32)|pageNo -> Page 深拷贝
    private final Map<Long, Page> pages = new HashMap<>();
    // 事务内新增页计数：tableId -> 已分配条数（commit 时写盘, pageNo = 旧页数 + 计数）
    private final Map<Integer, Integer> newPages = new HashMap<>();

    public Txn(long id) { this.id = id; }

    public long id() { return id; }
    public State state() { return state; }
    public void setState(State s) { state = s; }
    public boolean active() { return state == State.ACTIVE; }

    // ---- ThreadLocal ----
    public static Txn current() { return CURRENT.get(); }
    public static void setCurrent(Txn t) { CURRENT.set(t); }
    public static void clearCurrent() { CURRENT.remove(); }

    // ---- 工作集 ----
    public Page get(int tableId, int pageNo) { return pages.get(key(tableId, pageNo)); }
    public void put(int tableId, int pageNo, Page p) { pages.put(key(tableId, pageNo), p); }
    public Map<Long, Page> pages() { return pages; }

    public int newCount(int tableId) { Integer c = newPages.get(tableId); return c == null ? 0 : c; }
    public void bumpNewCount(int tableId) { newPages.put(tableId, newCount(tableId) + 1); }

    public void reset() { pages.clear(); newPages.clear(); }

    private static long key(int tableId, int pageNo) {
        return ((long) tableId << 32) | (pageNo & 0xffffffffL);
    }
}
