package com.course.dbms.storage;

import com.course.dbms.common.Consts;
import com.course.dbms.common.Error;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * 磁盘管理器：负责页的落盘与读回。
 *
 * 磁盘组织：一个数据库一个目录，目录下每个表一个文件 `table-{tableId}.db`。
 * 文件就是定长页数组：页 pageNo 位于偏移 pageNo*PAGE_SIZE。
 * 表文件长度 / PAGE_SIZE 即该表当前页数（追加页/截断尾页即可增删页）。
 */
public class DiskManager {

    private final String dir;
    private final Map<Integer, RandomAccessFile> files = new HashMap<>();

    /** 用数据库目录初始化；目录不存在会创建。 */
    public DiskManager(String dir) {
        this.dir = dir;
        File d = new File(dir);
        if (!d.exists() && !d.mkdirs()) {
            throw Error.st("cannot create db dir: " + dir);
        }
        addShutdownHook();
    }

    /** 事务性：注册关闭钩子，进程结束前把所有文件关闭，防止脏数据。 */
    private void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "db-disk-close"));
    }

    /** 数据库目录（WAL 日志放同一目录）。 */
    public String dir() { return dir; }

    private File file(int tableId) {
        return new File(dir, Consts.TABLE_FILE + "-" + tableId + ".db");
    }

    /** 线程安全：文件句柄的获取必须串行（多连接共享同一 DiskManager）。 */
    private synchronized RandomAccessFile raf(int tableId) {
        RandomAccessFile f = files.get(tableId);
        if (f != null) return f;
        try {
            File path = file(tableId);
            // createNewFile 不覆盖已有文件
            if (!path.exists()) {
                if (!path.createNewFile()) throw Error.st("cannot create file: " + path);
            }
            f = new RandomAccessFile(path, "rw");
            files.put(tableId, f);
            return f;
        } catch (IOException e) {
            throw new Error("ST-0010", "open file failed: " + file(tableId), e);
        }
    }

    /** 当前某表的页数。 */
    public synchronized int pageCount(int tableId) {
        try {
            return (int) (raf(tableId).length() / Page.SIZE);
        } catch (IOException e) {
            throw new Error("ST-0011", "read length failed", e);
        }
    }

    public synchronized int[] knownTableIds() {
        File[] fs = new File(dir).listFiles((d, n) -> n.startsWith(Consts.TABLE_FILE + "-") && n.endsWith(".db"));
        if (fs == null) return new int[0];
        int[] ids = new int[fs.length];
        for (int i = 0; i < fs.length; i++) {
            ids[i] = Integer.parseInt(fs[i].getName().replaceAll("\\D", ""));
        }
        return ids;
    }

    /** 读一页，返回 PAGE_SIZE 字节；页号越界返回 null。 */
    public synchronized byte[] readPage(int tableId, int pageNo) {
        try {
            RandomAccessFile f = raf(tableId);
            long off = (long) pageNo * Page.SIZE;
            if (off + Page.SIZE > f.length()) return null;
            byte[] buf = new byte[Page.SIZE];
            f.seek(off);
            f.readFully(buf);
            return buf;
        } catch (IOException e) {
            throw new Error("ST-0012", "read page failed: " + tableId + "#" + pageNo, e);
        }
    }

    /** 写一页（覆盖）。RandomAccessFile 在"超出 EOF 处写"会自动扩展文件，供事务提交写新页。 */
    public synchronized void writePage(int tableId, int pageNo, byte[] data) {
        if (data.length != Page.SIZE) throw Error.st("page size mismatch on write");
        try {
            RandomAccessFile f = raf(tableId);
            f.seek((long) pageNo * Page.SIZE);
            f.write(data);
        } catch (IOException e) {
            throw new Error("ST-0013", "write page failed: " + tableId + "#" + pageNo, e);
        }
    }

    /** 追加一页到表末尾，返回新页号（= 当前页数）。 */
    public synchronized int appendPage(int tableId, byte[] data) {
        int pageNo = pageCount(tableId);
        writePage(tableId, pageNo, data);
        return pageNo;
    }

    /** 截断表尾页（回收最后一页的磁盘空间），实现"页释放"。 */
    public synchronized void truncateLastPage(int tableId) {
        try {
            RandomAccessFile f = raf(tableId);
            long newLen = f.length() - Page.SIZE;
            if (newLen < 0) throw Error.st("cannot truncate below zero");
            f.setLength(newLen);
        } catch (IOException e) {
            throw new Error("ST-0014", "truncate failed", e);
        }
    }

    /** 把已打开的所有文件 fsync 到磁盘（WAL 重放后调用，保证刷盘后再清日志）。 */
    public synchronized void force() {
        for (RandomAccessFile f : files.values()) {
            try { f.getFD().sync(); } catch (IOException ignored) {}
        }
    }

    /** 关闭所有文件。 */
    public synchronized void close() {
        for (RandomAccessFile f : files.values()) {
            try { f.close(); } catch (IOException ignored) {}
        }
        files.clear();
    }
}
