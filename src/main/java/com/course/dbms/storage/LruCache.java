package com.course.dbms.storage;

import com.course.dbms.common.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU 缓存：最近最少使用页被淘汰。
 * 用 LinkedHashMap(accessOrder=true) 实现，访问会移动到链表尾部（最新），
 * 头部为最久未使用；插入超容量时淘汰头部。
 */
public class LruCache extends AbstractCache {

    private final LinkedHashMap<Long, Page> map;

    public LruCache(int capacity) {
        super(capacity);
        this.map = new LinkedHashMap<Long, Page>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, Page> eldest) {
                boolean evict = size() > LruCache.this.capacity;
                if (evict) {
                    Log.debug("LRU evict page#" + eldest.getKey());
                }
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

    @Override public synchronized void put(long key, Page page) {
        if (map.size() >= capacity && !map.containsKey(key)) {
            // 预提示一次淘汰前统计，便于演示
            Log.debug("LRU insert full -> evict one");
        }
        map.put(key, page);
    }

    @Override public synchronized void remove(long key) { map.remove(key); }
    @Override public synchronized int size() { return map.size(); }
    @Override public synchronized String policy() { return "LRU"; }
}
