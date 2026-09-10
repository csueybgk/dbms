package com.course.dbms.txn;

import com.course.dbms.common.Error;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 表级锁管理器：共享读锁(S) / 排他写锁(X) 表，支持多共享持有者、等待队列、超时。
 *
 * 锁归属：{@link com.course.dbms.db.Database} 持有【一个共享实例】，所有会话靠它协调；
 * 同一表上：
 *   - SHARED 可与多个 SHARED 共存（读-读不互斥）；
 *   - EXCLUSIVE 与其他任何锁互斥（写-写、读-写互斥）；
 *   - 同一事务重入直接成功；唯一 SHARED 持有者可升级为 EXCLUSIVE。
 *
 * 死锁规避：调用方( Session )对一条语句/事务涉及的多张表一律按 tableId 升序 acquire；
 * 这里再以超时(TX-0003)兜底。
 */
public class LockManager {

    private final long timeoutMs;
    private final Map<Integer, Grant> grants = new HashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    public LockManager(long timeoutMs) { this.timeoutMs = timeoutMs; }

    /** 生成一个唯一的事务/持锁 id（autocommit 每条语句、显式事务各一个）。 */
    public long nextId() { return idSeq.getAndIncrement(); }

    /** 加锁（使用默认超时）。 */
    public void acquire(long txn, int table, Lock mode) { acquire(txn, table, mode, timeoutMs); }

    public synchronized void acquire(long txn, int table, Lock mode, long timeout) {
        Grant g = grants.get(table);
        if (g == null) { g = new Grant(); grants.put(table, g); }

        // 重入：已是排他持有者，任意模式都直接给（事务内读自己写的行）
        if (g.xTxn != null && g.xTxn.longValue() == txn) return;
        // 重入：已是共享持有者且请求共享
        if (mode == Lock.SHARED && g.sTxns.contains(txn)) return;

        long deadline = System.currentTimeMillis() + timeout;
        Waiter mine = new Waiter(txn, toInt(mode));
        g.waiters.add(mine);
        try {
            while (true) {
                if (canGrant(g, txn, toInt(mode))) {
                    g.waiters.remove(mine);
                    applyGrant(g, txn, toInt(mode));
                    notifyAll();
                    return;
                }
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    g.waiters.remove(mine);
                    throw new Error("TX-0003", "lock timeout on table " + table + " want " + mode);
                }
                wait(remain);
            }
        } catch (InterruptedException e) {
            g.waiters.remove(mine);
            Thread.currentThread().interrupt();
            throw new Error("TX-0003", "lock interrupted on table " + table, e);
        }
    }

    /** 释放一个 txn 持有的全部锁（提交/回滚、autocommit 语句结束）。 */
    public synchronized void releaseTxn(long txn) {
        boolean any = false;
        Iterator<Map.Entry<Integer, Grant>> it = grants.entrySet().iterator();
        while (it.hasNext()) {
            Grant g = it.next().getValue();
            boolean touched = false;
            if (g.xTxn != null && g.xTxn.longValue() == txn) { g.xTxn = null; touched = true; }
            if (g.sTxns.remove(txn)) touched = true;
            // 移除该 txn 的等待项
            for (Iterator<Waiter> w = g.waiters.iterator(); w.hasNext();) {
                if (w.next().txn == txn) { w.remove(); touched = true; }
            }
            if (touched) { any = true; if (empty(g)) it.remove(); }
        }
        if (any) notifyAll();
    }

    public synchronized boolean heldBy(long txn, int table) {
        Grant g = grants.get(table);
        if (g == null) return false;
        return (g.xTxn != null && g.xTxn.longValue() == txn) || g.sTxns.contains(txn);
    }

    /** 该表是否被某排他持有者占用（日志/断言）。 */
    public synchronized boolean isHeldX(int table) {
        Grant g = grants.get(table);
        return g != null && g.xTxn != null;
    }

    public synchronized int lockCount() { return grants.size(); }

    public synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Grant> e : grants.entrySet()) {
            Grant g = e.getValue();
            sb.append("table#").append(e.getKey())
              .append(g.xTxn != null ? " X(txn " + g.xTxn + ")" : "")
              .append(g.sTxns.isEmpty() ? "" : " S" + g.sTxns)
              .append("\n");
        }
        return sb.length() == 0 ? "<no locks>" : sb.toString();
    }

    // ---- 内部 ----

    private static final int S = 0, X = 1;
    private static int toInt(Lock m) { return m == Lock.EXCLUSIVE ? X : S; }

    private boolean canGrant(Grant g, long txn, int mode) {
        if (mode == X) {
            if (g.xTxn != null && g.xTxn.longValue() != txn) return false; // 已被他人持有
            if (g.xTxn != null) return true;                             // 重入
            if (!g.sTxns.isEmpty()) {
                // 唯一共享持有者(即自己)才可升级；否则等所有读者退散
                if (g.sTxns.size() == 1 && g.sTxns.contains(txn)) return true;
                return false;
            }
            return true;
        } else { // S
            if (g.xTxn != null) return false;                            // 有写者则不可
            // 避免写者饥饿：若已有排他等待者(独占先到者优先)，共享不让路
            for (Waiter w : g.waiters) if (w.mode == X) return false;
            return true;
        }
    }

    private void applyGrant(Grant g, long txn, int mode) {
        if (mode == X) {
            g.sTxns.remove(txn);
            g.xTxn = txn;
        } else {
            g.sTxns.add(txn);
        }
    }

    private boolean empty(Grant g) {
        return g.xTxn == null && g.sTxns.isEmpty() && g.waiters.isEmpty();
    }

    /** 单个表的锁状态。 */
    private static final class Grant {
        Long xTxn = null;                              // 排他持有者 txnId（null = 无）
        final Set<Long> sTxns = new HashSet<>();        // 共享持有者 txnId（可多个）
        final List<Waiter> waiters = new ArrayList<>();
    }

    private static final class Waiter {
        final long txn; final int mode;
        Waiter(long txn, int mode) { this.txn = txn; this.mode = mode; }
    }
}
