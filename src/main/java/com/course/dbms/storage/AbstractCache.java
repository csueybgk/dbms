package com.course.dbms.storage;

import com.course.dbms.common.Log;

/**
 * 缓存基类：统计命中/未命中，及命中率日志。淘汰策略交给子类。
 */
public abstract class AbstractCache implements Cache {

    protected final int capacity;
    private long hits;
    private long misses;

    protected AbstractCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("cache capacity must be > 0");
        this.capacity = capacity;
    }

    public int capacity() { return capacity; }
    public long hits() { return hits; }
    public long misses() { return misses; }

    /** 供子类在 get 命中/未命中时调用。 */
    protected void recordHit() { hits++; }
    protected void recordMiss() { misses++; }

    @Override public String stats() {
        long total = hits + misses;
        double rate = total == 0 ? 0 : (double) hits / total * 100;
        return String.format("%s capacity=%d hits=%d misses=%d hitRate=%.1f%%",
                policy(), capacity, hits, misses, rate);
    }

    @Override public void logStats() {
        Log.info("CACHE[" + stats() + "]");
    }
}
