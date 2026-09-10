package com.course.dbms.storage;

/**
 * 页缓存接口。数据库上层模块（存储引擎、目录）只依赖此接口取页，
 * 不关心具体淘汰策略——对应图片"② 缓存机制：提供接口供数据库模块调用"。
 */
public interface Cache {

    /** 取页；命中返回页，未命中返回 null（由调用方落盘加载）。 */
    Page get(long key);

    /** 放入页；若超出容量按策略淘汰。 */
    void put(long key, Page page);

    /** 移除某页（例如页被释放时）。 */
    void remove(long key);

    /** 当前缓存页数。 */
    int size();

    /** 策略名（LRU / FIFO），用于打印与演示。 */
    String policy();

    /** 缓存命中率等统计。 */
    String stats();

    /** 打印命中率日志。 */
    void logStats();
}
