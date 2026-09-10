package com.course.dbms.common;

/**
 * 全局常量。集中管理，避免魔法数字散落各处。
 */
public final class Consts {

    private Consts() {}

    /** 页大小（字节）。经典的数据库页尺寸，便于讲解。 */
    public static final int PAGE_SIZE = 4096;

    /** 客户端/服务端默认端口。 */
    public static final int DEFAULT_PORT = 9999;

    /** 服务端默认监听地址。 */
    public static final String DEFAULT_HOST = "127.0.0.1";

    /** 数据库页文件默认名（所有页顺序写入此文件）。 */
    public static final String DB_FILE = "db.db";

    /** 每个表的数据文件前缀。表 XX 的数据文件为 {prefix}-{tableId}.db */
    public static final String TABLE_FILE = "table";

    /** 崩溃恢复用的预写日志(WAL)文件名，放在数据库目录下。 */
    public static final String WAL_FILE = "wal.log";

    /** 目录里保留的页号：0 号页用于记录全局状态（当前最大页号、页头）。 */
    public static final int META_PAGE = 0;

    /** 页头魔法数（0xDB30，即 "DB0"）。 */
    public static final short PAGE_MAGIC = (short) 0xDB30;

    /** 页版本。 */
    public static final byte PAGE_VERSION = 1;

    /** 等锁上限（毫秒），防止并发下无限阻塞；超时抛 TX-0003。 */
    public static final long LOCK_TIMEOUT_MS = 5000;
}
