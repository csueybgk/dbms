package com.course.dbms.storage;

import com.course.dbms.common.Consts;

import java.util.Arrays;

/**
 * 槽位化页（slotted page）。
 *
 * 布局（页大小 PAGE_SIZE = 4096，大端序）：
 *  ┌────────────────────────────── 偏移 0 ──────────────────────────────┐
 *  │ 0-1   magic(short)   常量 0xDB30                                   │
 *  │ 2-5   pageNo(int)    本页页号                                       │
 *  │ 6-9   tableId(int)   所属表 id                                     │
 *  │ 10-13 recordCount(int) 当前记录（槽位）数量                          │
 *  │ 14-15 freeStart(short) 记录区末尾 / 空闲区起点                        │
 *  ├────────────────────────────── 记录区 ─────────────────────────────┤
 *  │ 记录0 [bytes] 记录1 [bytes] ...   ← 从 HEADER_SIZE 向后连续存放       │
 *  ├────────────────────────────── 槽位目录 ────────────────────────────┤
 *  │ ... 槽位1 [offset,length] 槽位0 [offset,length]  ← 从页尾向前生长     │
 *  └───────────────────────────────────────────────────────────────────┘
 *  每个槽位 4 字节：[2-byte offset][2-byte length]；槽位 i（记录 i）位于
 *  PAGE_SIZE - (i+1)*SLOT_SIZE。空闲区 = [freeStart, PAGE_SIZE - recordCount*SLOT_SIZE)。
 *
 *  这种布局就是经典的 "行与页映射"：一行（记录）落在页内的一个槽位，
 *  槽位记录其在页内的偏移和长度。天然支持变长字符串。
 */
public class Page {

    /** 页大小，取全局常量再校验。 */
    public static final int SIZE = Consts.PAGE_SIZE;
    /** 每个槽位大小（offset + length）。 */
    public static final int SLOT_SIZE = 4;
    /** 页头固定长度。 */
    public static final int HEADER_SIZE = 16;

    private static final int OFF_MAGIC = 0;
    private static final int OFF_PAGE_NO = 2;
    private static final int OFF_TABLE_ID = 6;
    private static final int OFF_REC_COUNT = 10;
    private static final int OFF_FREE_START = 14;

    private final byte[] data;
    private final int tableId;
    private final int pageNo;

    /** 创建一个空页。 */
    public Page(int tableId, int pageNo) {
        this.data = new byte[SIZE];
        this.tableId = tableId;
        this.pageNo = pageNo;
        putShort(OFF_MAGIC, Consts.PAGE_MAGIC);
        putInt(OFF_PAGE_NO, pageNo);
        putInt(OFF_TABLE_ID, tableId);
        putInt(OFF_REC_COUNT, 0);
        putShort(OFF_FREE_START, (short) HEADER_SIZE);
    }

    /** 从磁盘字节反序列化构造。header 里存有 tableId/pageNo。 */
    public Page(byte[] data) {
        if (data.length != SIZE) {
            throw new IllegalArgumentException("page size mismatch: " + data.length);
        }
        this.data = data;
        this.tableId = getInt(OFF_TABLE_ID);
        this.pageNo = getInt(OFF_PAGE_NO);
    }

    public int tableId() { return tableId; }
    public int pageNo() { return pageNo; }
    public int recordCount() { return getInt(OFF_REC_COUNT); }
    public int freeStart() { return getShort(OFF_FREE_START) & 0xFFFF; }
    public byte[] data() { return data; }

    /** 槽位 i 的起始地址（从页尾往前）。 */
    private int slotAddr(int i) { return SIZE - (i + 1) * SLOT_SIZE; }

    /** 槽位 i 记录的字节偏移。 */
    private int slotOffset(int i) { return getShort(slotAddr(i)) & 0xFFFF; }
    /** 槽位 i 记录的字节长度。 */
    private int slotLength(int i) { return getShort(slotAddr(i) + 2) & 0xFFFF; }

    /** 当前剩余可用字节数（记录区与槽位区之间的空隙）。 */
    public int freeSpace() {
        int recEnd = freeStart();
        int slotBottom = SIZE - recordCount() * SLOT_SIZE;
        return slotBottom - recEnd;
    }

    /**
     * 追加一条记录，返回其槽位下标；若空间不足返回 -1（调用方应另开新页）。
     */
    public int addRecord(byte[] rec) {
        int count = recordCount();
        int start = freeStart();
        int slot = 0;
        while (slot < count && slotLength(slot) > 0) slot++;
        int newCount = slot == count ? count + 1 : count;
        int needSlot = newCount * SLOT_SIZE;
        if (start + rec.length + needSlot > SIZE) {
            return -1;
        }
        // 写入记录数据
        System.arraycopy(rec, 0, data, start, rec.length);
        // 写入槽位 [offset, length]
        int addr = slotAddr(slot);
        putShort(addr, (short) start);
        putShort(addr + 2, (short) rec.length);
        // 更新头
        putInt(OFF_REC_COUNT, newCount);
        putShort(OFF_FREE_START, (short) (start + rec.length));
        return slot;
    }

    /** 删除并压缩记录区，保留其他记录的槽位编号。 */
    public boolean deleteRecord(int slot) {
        if (getRecord(slot) == null) return false;
        replaceRecord(slot, new byte[0]);
        return true;
    }

    /** 原槽位更新；空间不足不改页，由上层迁移记录。空数组代表删除。 */
    public boolean replaceRecord(int slot, byte[] record) {
        byte[] old = getRecord(slot);
        if (old == null || record.length > freeSpace() + old.length) return false;
        byte[] snapshot = data.clone();
        int start = HEADER_SIZE;
        for (int i = 0; i < recordCount(); i++) {
            int length = i == slot ? record.length : slotLength(i);
            int offset = slotOffset(i);
            if (i == slot) System.arraycopy(record, 0, data, start, length);
            else System.arraycopy(snapshot, offset, data, start, length);
            putShort(slotAddr(i), (short) start);
            putShort(slotAddr(i) + 2, (short) length);
            start += length;
        }
        Arrays.fill(data, start, SIZE - recordCount() * SLOT_SIZE, (byte) 0);
        putShort(OFF_FREE_START, (short) start);
        return true;
    }

    /** 读回第 i 条记录；越界或空槽位返回 null。 */
    public byte[] getRecord(int i) {
        if (i < 0 || i >= recordCount()) return null;
        int off = slotOffset(i);
        int len = slotLength(i);
        if (len <= 0 || off + len > HEADER_SIZE + SIZE) return null;
        byte[] out = new byte[len];
        System.arraycopy(data, off, out, 0, len);
        return out;
    }

    /** 顺序遍历所有记录，跳过空槽位。 */
    public Iterable<byte[]> records() {
        return () -> {
            return new java.util.Iterator<byte[]>() {
                private int i = 0;
                @Override public boolean hasNext() {
                    while (i < recordCount() && slotLength(i) <= 0) i++;
                    return i < recordCount();
                }
                @Override public byte[] next() {
                    int idx = i;
                    byte[] r = getRecord(idx);
                    i++;
                    return r;
                }
            };
        };
    }

    private int getInt(int off) {
        return (data[off] & 0xFF) << 24 | (data[off + 1] & 0xFF) << 16
             | (data[off + 2] & 0xFF) << 8 | (data[off + 3] & 0xFF);
    }
    private short getShort(int off) {
        return (short) (((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF));
    }
    private void putInt(int off, int v) {
        data[off] = (byte) (v >> 24);
        data[off + 1] = (byte) (v >> 16);
        data[off + 2] = (byte) (v >> 8);
        data[off + 3] = (byte) v;
    }
    private void putShort(int off, short v) {
        data[off] = (byte) (v >> 8);
        data[off + 1] = (byte) v;
    }

    @Override public String toString() {
        return String.format("Page{tableId=%d, pageNo=%d, rec=%d, free=%d}",
                tableId, pageNo, recordCount(), freeSpace());
    }
}
