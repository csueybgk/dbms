package com.course.dbms.txn;

import com.course.dbms.common.Error;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * 表级锁管理器单测：验证 S 共存、S/X 互斥、重入、S→X 升级、超时抛 TX-0003。
 */
public class LockManagerTest {

    @Test public void sharedCoexist() {
        LockManager lm = new LockManager(500);
        long a = lm.nextId(), b = lm.nextId();
        lm.acquire(a, 2, Lock.SHARED);
        lm.acquire(b, 2, Lock.SHARED);        // 读-读不互斥：不阻塞
        assertTrue(lm.heldBy(a, 2));
        assertTrue(lm.heldBy(b, 2));
        lm.releaseTxn(a);
        lm.releaseTxn(b);
        assertFalse(lm.heldBy(a, 2));
    }

    @Test public void exclusiveConflictTimesOut() {
        LockManager lm = new LockManager(150);
        long a = lm.nextId(), b = lm.nextId();
        lm.acquire(a, 7, Lock.EXCLUSIVE);
        long t0 = System.currentTimeMillis();
        try {
            lm.acquire(b, 7, Lock.EXCLUSIVE);
            fail("写-写应互斥，应当超时");
        } catch (Error e) {
            assertEquals("TX-0003", e.code());
        }
        assertTrue(System.currentTimeMillis() - t0 >= 100); // 确实等了
        lm.releaseTxn(a);
    }

    @Test public void sharedBlocksThenReleases() throws Exception {
        LockManager lm = new LockManager(5000);
        long a = lm.nextId(), b = lm.nextId();
        lm.acquire(a, 5, Lock.EXCLUSIVE);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread th = new Thread(() -> {
            try { lm.acquire(b, 5, Lock.SHARED); }
            catch (Throwable e) { err.set(e); }
            finally { done.countDown(); }
        });
        th.start();
        assertFalse("读在写持有期间必须等待", done.await(200, TimeUnit.MILLISECONDS));
        lm.releaseTxn(a);                       // 释放写锁
        assertTrue("写释放后读应被授予", done.await(1000, TimeUnit.MILLISECONDS));
        lm.releaseTxn(b);
        assertNull(err.get());
    }

    @Test public void reentrantSameTxn() {
        LockManager lm = new LockManager(500);
        long a = lm.nextId();
        lm.acquire(a, 4, Lock.SHARED);
        lm.acquire(a, 4, Lock.SHARED);          // 同事务重入共享：不阻塞
        assertTrue(lm.heldBy(a, 4));
        lm.acquire(a, 4, Lock.EXCLUSIVE);       // 唯一共享持有者升级为排他
        assertTrue(lm.isHeldX(4));
        lm.acquire(a, 4, Lock.EXCLUSIVE);       // 已是排他，任意重入
        assertTrue(lm.heldBy(a, 4));
        lm.releaseTxn(a);
    }

    @Test public void upgradeBlockedByOtherReader() throws Exception {
        LockManager lm = new LockManager(200);
        long a = lm.nextId(), b = lm.nextId();
        lm.acquire(a, 6, Lock.SHARED);
        lm.acquire(b, 6, Lock.SHARED);          // 两个读者
        try {
            lm.acquire(a, 6, Lock.EXCLUSIVE);   // a 想升级，但 b 也在读 → 应超时
            fail("他人共享持有时应无法升级为排他");
        } catch (Error e) {
            assertEquals("TX-0003", e.code());
        }
        lm.releaseTxn(a);
        lm.releaseTxn(b);
    }

    @Test public void releaseTxnReleasesAllTables() {
        LockManager lm = new LockManager(500);
        long a = lm.nextId();
        lm.acquire(a, 3, Lock.EXCLUSIVE);
        lm.acquire(a, 4, Lock.SHARED);
        assertTrue(lm.isHeldX(3));
        assertTrue(lm.heldBy(a, 4));
        lm.releaseTxn(a);
        assertFalse(lm.heldBy(a, 3));
        assertFalse(lm.heldBy(a, 4));
    }
}
