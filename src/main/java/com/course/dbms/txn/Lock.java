package com.course.dbms.txn;

/**
 * 锁模式：共享读锁 / 排他写锁。
 * SHARED   允许多个持有者并发读（读-读不互斥）；不允许存在写锁持有者。
 * EXCLUSIVE只允许一个持有者；与任何其他锁互斥（写-写、读-写都互斥）。
 */
public enum Lock {
    SHARED,
    EXCLUSIVE;

    public boolean isShared() { return this == SHARED; }
    public boolean isExclusive() { return this == EXCLUSIVE; }
}
