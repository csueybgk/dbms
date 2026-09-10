package com.course.dbms.storage;

import com.course.dbms.common.Consts;
import com.course.dbms.common.Error;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 预写日志(WAL)：为"已提交事务"提供崩溃后的持久性/原子性保证。
 *
 * 提交协议：{@link PageManager#commitTxn} 先把事务工作集的所有页后像 + COMMIT 标记
 * 追加到本日志并 fsync，再把页写到数据文件。若进程在这两步之间崩溃，数据文件可能是
 * 撕裂态（有的页新、有的页旧），重启时用本日志【重放】已提交事务即可恢复到提交后的状态。
 *
 * 记录格式（大端，长度前缀定界，天然容忍写日志时崩溃留下的撕裂尾部）：
 *   [4-byte recordLen][1-byte type][8-byte txnId]
 *     type=0 BEGIN   —— 无更多载荷
 *     type=1 PUT     —— [4-byte tableId][4-byte pageNo][PAGE_SIZE 字节后像]
 *     type=2 COMMIT  —— 无更多载荷
 *
 * 恢复时：只重放【有 COMMIT 标记】的事务的 PUT（未提交事务被丢弃）；成功后清空日志。
 */
public class Log {

    private static final byte TYPE_BEGIN = 0;
    private static final byte TYPE_PUT = 1;
    private static final byte TYPE_COMMIT = 2;

    private final File file;
    private RandomAccessFile raf;   // 惰性打开，只在真正写日志时创建

    public Log(String dir) {
        this.file = new File(dir, Consts.WAL_FILE);
    }

    // ---- 提交：BEGIN + 每个页一条 PUT + COMMIT，然后 fsync ----

    public void writeCommit(long txnId, Map<Long, Page> pages) {
        try {
            writeRecord(TYPE_BEGIN, txnId, 0, 0, null);
            for (Page p : pages.values()) {
                writeRecord(TYPE_PUT, txnId, p.tableId(), p.pageNo(), p.data());
            }
            writeRecord(TYPE_COMMIT, txnId, 0, 0, null);
            sync();
        } catch (IOException e) {
            throw new Error("ST-0015", "wal append failed for txn " + txnId, e);
        }
    }

    // ---- 包私有原语（供 LogTest 构造"未提交/撕裂"的日志场景）----

    void writeBegin(long txnId) throws IOException { writeRecord(TYPE_BEGIN, txnId, 0, 0, null); }
    void writePut(long txnId, Page page) throws IOException { writeRecord(TYPE_PUT, txnId, page.tableId(), page.pageNo(), page.data()); }
    void writeCommitRecord(long txnId) throws IOException { writeRecord(TYPE_COMMIT, txnId, 0, 0, null); }
    void sync() throws IOException { if (raf != null) raf.getFD().sync(); }

    // ---- 崩溃恢复：重放已提交事务，丢弃未提交事务，成功后清空日志 ----

    public void recover(DiskManager disk) {
        byte[] all = readAll();
        if (all == null || all.length == 0) return;

        Set<Long> committed = new HashSet<>();
        List<PutRec> puts = new ArrayList<>();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(all));
        try {
            while (in.available() >= 4) {
                int len = in.readInt();
                if (in.available() < len) break;          // 撕裂尾部：不完整记录，停止解析
                byte type = in.readByte();
                long txnId = in.readLong();
                if (type == TYPE_PUT) {
                    int tableId = in.readInt();
                    int pageNo = in.readInt();
                    byte[] img = new byte[Page.SIZE];
                    in.readFully(img);
                    puts.add(new PutRec(txnId, tableId, pageNo, img));
                } else if (type == TYPE_COMMIT) {
                    committed.add(txnId);
                }
                // TYPE_BEGIN 仅作分界标记，恢复无需特别处理
            }
        } catch (EOFException e) {
            // 到文件尾自然结束
        } catch (IOException e) {
            throw new Error("ST-0016", "wal parse failed", e);
        }

        for (PutRec p : puts) {
            if (committed.contains(p.txnId)) {
                disk.writePage(p.tableId, p.pageNo, p.image);
            }
        }
        disk.force();
        truncate();
    }

    // ---- 内部 ----

    /** 写一条长度前缀记录；每次追加都 seek 到文件末尾。 */
    private void writeRecord(byte type, long txnId, int tableId, int pageNo, byte[] image) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        int payload = 1 + 8 + (image == null ? 0 : 8 + image.length);
        out.writeInt(payload);
        out.writeByte(type);
        out.writeLong(txnId);
        if (image != null) {
            out.writeInt(tableId);
            out.writeInt(pageNo);
            out.write(image);
        }
        out.flush();
        RandomAccessFile f = raf();
        f.seek(f.length());
        f.write(bos.toByteArray());
    }

    private RandomAccessFile raf() throws IOException {
        if (raf == null) {
            if (file.getParentFile() != null && !file.getParentFile().exists()) {
                if (!file.getParentFile().mkdirs()) throw Error.st("cannot create wal dir: " + file.getParentFile());
            }
            raf = new RandomAccessFile(file, "rw");
        }
        return raf;
    }

    private byte[] readAll() {
        if (!file.exists()) return null;
        try {
            RandomAccessFile f = new RandomAccessFile(file, "r");
            byte[] all = new byte[(int) f.length()];
            f.readFully(all);
            f.close();
            return all;
        } catch (IOException e) {
            throw new Error("ST-0017", "read wal failed", e);
        }
    }

    private void truncate() {
        try {
            RandomAccessFile f = raf();
            f.setLength(0);
            f.getFD().sync();
        } catch (IOException e) {
            throw new Error("ST-0018", "truncate wal failed", e);
        }
    }

    public void close() {
        if (raf != null) {
            try { raf.close(); } catch (IOException ignored) {}
            raf = null;
        }
    }

    private static final class PutRec {
        final long txnId;
        final int tableId;
        final int pageNo;
        final byte[] image;
        PutRec(long txnId, int tableId, int pageNo, byte[] image) {
            this.txnId = txnId; this.tableId = tableId; this.pageNo = pageNo; this.image = image;
        }
    }
}
