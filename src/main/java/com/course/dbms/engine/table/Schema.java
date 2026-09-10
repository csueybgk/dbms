package com.course.dbms.engine.table;

import com.course.dbms.common.Error;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** 表结构：有序的列集合。 */
public class Schema implements Serializable {

    private final List<Column> columns = new ArrayList<>();

    public Schema add(String name, FieldType type) {
        columns.add(new Column(name, type));
        return this;
    }

    public int columnCount() { return columns.size(); }

    public List<Column> columns() { return columns; }

    public Column column(int i) { return columns.get(i); }

    public int indexOf(String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    public FieldType typeOf(String name) {
        int i = indexOf(name);
        if (i < 0) throw new Error("SE-0001", "column not found: " + name);
        return columns.get(i).type();
    }

    @Override public String toString() {
        return columns.toString();
    }
}
