package com.course.dbms.engine.table;

/** 表元信息：表 id、表名、结构。数据本身由 StorageEngine 管理。 */
public class Table {
    private final int tableId;
    private final String name;
    private final Schema schema;

    public Table(int tableId, String name, Schema schema) {
        this.tableId = tableId;
        this.name = name;
        this.schema = schema;
    }

    public int tableId() { return tableId; }
    public String name() { return name; }
    public Schema schema() { return schema; }

    @Override public String toString() {
        return name + "(" + schema + ")";
    }
}
