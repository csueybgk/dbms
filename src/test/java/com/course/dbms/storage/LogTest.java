package com.course.dbms.storage;

import com.course.dbms.common.Consts;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 崩溃恢复（WAL redo）白盒测试：直接在 Log + DiskManager 层构造"崩溃场景"，
 * 验证已提交事务被重放、未提交事务被丢弃、撕裂尾部容错、重放幂等。
 */
public class LogTest {

    private final String dir = "target/tmp-log";

    @Before public void setup() {
        File d = new File(dir);
        File[] fs = d.listFiles();
        if (fs != null) for (File x : fs) x.delete();
    }

    private static long key(int tableId, int pageNo) {
        return ((long) tableId << 32) | (pageNo & 0xffffffffL);
    }

    /** 核心：事务已 COMMIT（日志有完整 PUT+COMMIT），但页尚未写到数据文件就崩 —— 恢复应把页重放出来。 */
    @Test public void recoveryAppliesCommittedTxnThatNeverHitDataFile() throws Exception {
        int tableId = 42;
        Page p0 = new Page(tableId, 0); p0.addRecord(new byte[]{1, 2, 3, 4});
        Page p1 = new Page(tableId, 1); p1.addRecord(new byte[]{5, 6, 7, 8});
        Map<Long, Page> pages = new HashMap<>();
        pages.put(key(tableId, 0), p0);
        pages.put(key(tableId, 1), p1);

        Log log = new Log(dir);
        log.writeCommit(7L, pages);   // 日志已 fsync，但下面磁盘页从未写入（模拟落页前崩溃）

        DiskManager disk = new DiskManager(dir);
        assertNull("落页前崩溃，数据文件不应有该页", disk.readPage(tableId, 0));
        assertNull(disk.readPage(tableId, 1));
        disk.close();

        // 重启：新的 Log + DiskManager 走恢复 → 重放已提交事务
        Log log2 = new Log(dir);
        DiskManager disk2 = new DiskManager(dir);
        log2.recover(disk2);
        assertArrayEquals(p0.data(), disk2.readPage(tableId, 0));
        assertArrayEquals(p1.data(), disk2.readPage(tableId, 1));
        disk2.close();
    }

    /** 未提交事务（日志只有 BEGIN+PUT 而无 COMMIT）在恢复时应被丢弃。 */
    @Test public void discardsUncommittedTxnOnRecover() throws Exception {
        int tableId = 9;
        Page p = new Page(tableId, 0); p.addRecord(new byte[]{3, 3});
        Log log = new Log(dir);
        log.writeBegin(5L);
        log.writePut(5L, p);
        log.sync();                 // 未写 COMMIT 就结束（模拟提交前崩溃）

        DiskManager disk = new DiskManager(dir);
        assertNull(disk.readPage(tableId, 0));
        disk.close();

        Log log2 = new Log(dir);
        DiskManager disk2 = new DiskManager(dir);
        log2.recover(disk2);
        assertNull("未提交事务不应被重放", disk2.readPage(tableId, 0));
        disk2.close();
    }

    /** 日志尾部是不完整记录（撕裂）：应应用其之前已合法提交的事务，并在尾部停止，不抛错。 */
    @Test public void tornTailStopsGracefully() throws Exception {
        int tableId = 3;
        Page p = new Page(tableId, 0); p.addRecord(new byte[]{9});
        Map<Long, Page> pages = new HashMap<>();
        pages.put(key(tableId, 0), p);
        Log log = new Log(dir);
        log.writeCommit(1L, pages);

        // 追加一个声称很长但实际没写全的"伪记录"，模拟写日志时在记录中间崩溃
        RandomAccessFile f = new RandomAccessFile(new File(dir, Consts.WAL_FILE), "rw");
        f.seek(f.length());
        f.writeInt(5000);           // 声称 recordLen=5000
        f.writeByte(1);             // type=PUT
        f.writeLong(2L);            // txnId=2（无 COMMIT）
        f.close();

        DiskManager disk = new DiskManager(dir);
        Log log2 = new Log(dir);
        log2.recover(disk);          // 应只重放 txn=1，停在撕裂缝，不抛错
        assertArrayEquals(p.data(), disk.readPage(tableId, 0));
        disk.close();
    }

    /** 重放幂等：同一个 WAL 恢复两次结果一致（重复重启不产生重复效果）。 */
    @Test public void replayIsIdempotent() throws Exception {
        int tableId = 7;
        Page p0 = new Page(tableId, 0); p0.addRecord(new byte[]{7});
        Map<Long, Page> pages = new HashMap<>();
        pages.put(key(tableId, 0), p0);
        Log log = new Log(dir);
        log.writeCommit(3L, pages);

        DiskManager disk = new DiskManager(dir);
        Log log2 = new Log(dir);
        log2.recover(disk);
        byte[] after1 = disk.readPage(tableId, 0);
        disk.close();

        DiskManager disk2 = new DiskManager(dir);
        Log log3 = new Log(dir);
        log3.recover(disk2);
        byte[] after2 = disk2.readPage(tableId, 0);
        disk2.close();

        assertArrayEquals(p0.data(), after1);
        assertArrayEquals(after1, after2);
    }
}
