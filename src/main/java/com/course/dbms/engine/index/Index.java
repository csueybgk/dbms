package com.course.dbms.engine.index;

import com.course.dbms.engine.table.FieldType;

import java.util.List;

/**
 * 一个索引：元数据（名字、所属表、列）+ 承载索引项的内存 B+ 树。
 *
 * 设计取舍：索引是【派生数据】——完全可以从基表重建，因此只把元数据持久化到
 * 系统目录表 sys_indexes，树本身放在内存里，进程启动（Catalog.reload）时重扫基表重建。
 * 这样索引与 WAL / 影子页事务解耦：提交后重建即可，回滚后重建即撤销。
 */
public class Index {

    private final String name;
    private final String tableName;
    private final int tableId;
    private final String columnName;
    private final int colIndex;
    private final FieldType type;
    private final BPlusTree tree = new BPlusTree();

    public Index(String name, String tableName, int tableId, String columnName, int colIndex, FieldType type) {
        this.name = name;
        this.tableName = tableName;
        this.tableId = tableId;
        this.columnName = columnName;
        this.colIndex = colIndex;
        this.type = type;
    }

    public String name() { return name; }
    public String tableName() { return tableName; }
    public int tableId() { return tableId; }
    public String columnName() { return columnName; }

    /** 被索引的列在表结构里的下标（扫描出的行据此取键）。 */
    public int colIndex() { return colIndex; }
    public FieldType type() { return type; }
    public BPlusTree tree() { return tree; }
    public int size() { return tree.size(); }
    public int height() { return tree.height(); }

    /** 记入一条索引项（插入一行时由 IndexManager 调用）。 */
    public void insert(Object key, int pageNo, int slot) {
        tree.insert(key, pageNo, slot);
    }

    /** 点查：该列等于 key 的所有行的 RID。 */
    public List<int[]> search(Object key) {
        return tree.search(key);
    }

    @Override public String toString() {
        return name + " on " + tableName + "(" + columnName + ")";
    }
}
