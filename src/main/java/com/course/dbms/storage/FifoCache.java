package com.course.dbms.storage;

import com.course.dbms.common.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FIFO 缓存：最早插入的页先被淘汰（与访问频率无关），用作与 LRU 对照。
 */
public class FifoCache extends AbstractCache {

    private final LinkedHashMap<Long, Page> map;

    public FifoCache(int capacity) {
        super(capacity);
        this.map = new LinkedHashMap<Long, Page>(16, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, Page> eldest) {
                boolean evict = size() > FifoCache.this.capacity;
                if (evict) Log.debug("FIFO evict page#" + eldest.getKey());
                return evict;
            }
        };
    }

    /** 线程安全：所有缓存操作加锁（服务端多连接共享同一缓存）。 */
    @Override public synchronized Page get(long key) {
        Page p = map.get(key);
        if (p != null) recordHit(); else recordMiss();
        return p;
    }

    @Override public synchronized void put(long key, Page page) { map.put(key, page); }

    @Override public synchronized void remove(long key) { map.remove(key); }
    @Override public synchronized int size() { return map.size(); }
    @Override public synchronized String policy() { return "FIFO"; }
}
