package com.course.dbms.engine.storage;

import com.course.dbms.common.Error;
import com.course.dbms.engine.table.FieldType;
import com.course.dbms.engine.table.Row;
import com.course.dbms.engine.table.Schema;
import com.course.dbms.engine.table.Table;
import com.course.dbms.storage.Page;
import com.course.dbms.storage.PageManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 存储引擎：实现行与页的映射，并通过 {@link PageManager} 落盘。
 *
 * 一行（记录）被序列化成一个字节数组，放进表的某一页槽位；
 * 页满自动追加新页。读取时逐页遍历、逐槽位解码成 {@link Row}。
 *
 * 记录字节格式（每列）：
 *   [4-byte len][字节数组]
 *   固定类型 len 是固定字节数；STRING 是 UTF-8 实际字节数。
 */
public class StorageEngine {

    private final PageManager pm;

    public StorageEngine(PageManager pm) {
        this.pm = pm;
    }

    /** 按类型把值规整到目标 Java 类型（用于插入前的类型检查/转换）。 */
    public static Object cast(Schema schema, int colIndex, Object v) {
        FieldType type = schema.column(colIndex).type();
        try {
            switch (type) {
                case INT32:   return ((Number) v).intValue();
                case INT64:   return ((Number) v).longValue();
                case FLOAT64: return ((Number) v).doubleValue();
                case DATETIME:
                    if (v instanceof String) return Long.parseLong((String) v);
                    return ((Number) v).longValue();
                case BOOL:
                    if (v instanceof Boolean) return v;
                    return Boolean.parseBoolean(String.valueOf(v));
                case STRING:  return String.valueOf(v);
                default:      throw new Error("SE-0004", "unsupported type " + type);
            }
        } catch (ClassCastException | NumberFormatException e) {
            throw new Error("SE-0005", "插入值类型与列 " + schema.column(colIndex).name() + " 不匹配", e);
        }
    }

    /** 把一行序列化成字节数组。 */
    public byte[] rowToBytes(Schema schema, Row row) {
        if (row.size() != schema.columnCount()) {
            throw new Error("SE-0006", "行值个数与列数不一致: " + row.size() + "!=" + schema.columnCount());
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            for (int i = 0; i < schema.columnCount(); i++) {
                Object v = cast(schema, i, row.get(i));
                byte[] enc = schema.column(i).type().encode(v);
                out.writeInt(enc.length);
                out.write(enc);
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new Error("SE-0007", "serialize row failed", e);
        }
    }

    /** 从字节数组还原一行。 */
    public Row rowFromBytes(Schema schema, byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < schema.columnCount(); i++) {
                int len = in.readInt();
                byte[] buf = new byte[len];
                in.readFully(buf);
                values.add(schema.column(i).type().decode(buf, 0, len));
            }
            return new Row(values);
        } catch (IOException e) {
            throw new Error("SE-0008", "deserialize row failed", e);
        }
    }

    /** 插入一行到表：找有空位的页，无则追加新页。返回放入的 (页号,槽位)。 */
    public int[] insert(Table table, Row row) {
        int tableId = table.tableId();
        byte[] rec = rowToBytes(table.schema(), row);
        validateRecord(rec);
        int n = pm.pageCount(tableId);
        for (int pageNo = 0; pageNo < n; pageNo++) {
            Page page = pm.getPage(tableId, pageNo);
            if (page == null) continue;
            // 页自身判断是否需要新槽位，复用空槽位无需额外 SLOT_SIZE。
            int slot = page.addRecord(rec);
            if (slot >= 0) {
                pm.persist(page);
                return new int[] { pageNo, slot };
            }
        }
        int newPageNo = pm.newPage(tableId);
        Page newPage = pm.getPage(tableId, newPageNo);
        int slot = newPage.addRecord(rec);
        pm.persist(newPage);
        return new int[] { newPageNo, slot };
    }

    /** 单行不得超过一页，写入前检查以免出现无效 RID。 */
    public void validateRecord(byte[] record) {
        if (record.length > Page.SIZE - Page.HEADER_SIZE - Page.SLOT_SIZE)
            throw new Error("SE-0009", "record too large for page");
    }

    public void delete(Table table, Located row) {
        Page page = pm.getPage(table.tableId(), row.pageNo());
        if (page != null && page.deleteRecord(row.slot())) pm.persist(page);
    }

    public void update(Table table, Located old, Row row) {
        byte[] record = rowToBytes(table.schema(), row);
        validateRecord(record);
        Page page = pm.getPage(table.tableId(), old.pageNo());
        if (page.replaceRecord(old.slot(), record)) pm.persist(page);
        else {
            // 先成功插入新记录再移除旧记录；调用方已固定本次更新的 RID 集合。
            insert(table, row);
            delete(table, old);
        }
    }

    /** 全表扫描：逐页逐槽位读出所有行。 */
    public List<Row> scan(Table table) {
        List<Row> rows = new ArrayList<>();
        int tableId = table.tableId();
        int n = pm.pageCount(tableId);
        for (int pageNo = 0; pageNo < n; pageNo++) {
            Page page = pm.getPage(tableId, pageNo);
            if (page == null) continue;
            for (byte[] rec : page.records()) {
                rows.add(rowFromBytes(table.schema(), rec));
            }
        }
        return rows;
    }

    /** 一行 + 它在页里的位置（RID）。建索引时要把 RID 记进 B+ 树。 */
    public static final class Located {
        private final int pageNo;
        private final int slot;
        private final Row row;

        public Located(int pageNo, int slot, Row row) {
            this.pageNo = pageNo;
            this.slot = slot;
            this.row = row;
        }

        public int pageNo() { return pageNo; }
        public int slot() { return slot; }
        public Row row() { return row; }
    }

    /**
     * 按 RID 取一行（索引扫描用）：页或槽位已不存在时返回 null（防御性，调用方跳过即可）。
     * 走 PageManager，因此事务内取的是工作集里的深拷贝 —— 索引扫描同样能读到自己的未提交数据。
     */
    public Row fetch(Table table, int pageNo, int slot) {
        Page page = pm.getPage(table.tableId(), pageNo);
        if (page == null) return null;
        byte[] rec = page.getRecord(slot);
        if (rec == null) return null;
        return rowFromBytes(table.schema(), rec);
    }

    /** 全表扫描并附带每行的位置（建索引用；Page.records() 不给出槽位下标，故这里自己数）。 */
    public List<Located> scanLocated(Table table) {
        List<Located> out = new ArrayList<>();
        int tableId = table.tableId();
        int n = pm.pageCount(tableId);
        for (int pageNo = 0; pageNo < n; pageNo++) {
            Page page = pm.getPage(tableId, pageNo);
            if (page == null) continue;
            int count = page.recordCount();
            for (int slot = 0; slot < count; slot++) {
                byte[] rec = page.getRecord(slot);
                if (rec == null) continue;                 // 空槽位
                out.add(new Located(pageNo, slot, rowFromBytes(table.schema(), rec)));
            }
        }
        return out;
    }

    /** 供算子流式扫描（一次只取一行），返回一个迭代器。 */
    public Iterable<Row> iterate(Table table) {
        return () -> {
            java.util.Iterator<Row> it = new java.util.Iterator<Row>() {
                private final int n = pm.pageCount(table.tableId());
                private int pageNo = 0;
                private java.util.Iterator<byte[]> cur = null;
                private Row next = null;

                @Override public boolean hasNext() {
                    if (next != null) return true;
                    next = fetch();
                    return next != null;
                }
                @Override public Row next() {
                    if (!hasNext()) throw new java.util.NoSuchElementException();
                    Row r = next; next = null; return r;
                }
                private Row fetch() {
                    while (true) {
                        if (cur != null && cur.hasNext()) {
                            return rowFromBytes(table.schema(), cur.next());
                        }
                        if (pageNo >= n) return null;
                        Page page = pm.getPage(table.tableId(), pageNo++);
                        if (page != null) cur = page.records().iterator();
                    }
                }
            };
            return it;
        };
    }
}
