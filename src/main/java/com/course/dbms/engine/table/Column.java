package com.course.dbms.engine.table;

import java.io.Serializable;

/** 一列的定义：列名 + 类型。 */
public class Column implements Serializable {
    private final String name;
    private final FieldType type;

    public Column(String name, FieldType type) {
        this.name = name;
        this.type = type;
    }

    public String name() { return name; }
    public FieldType type() { return type; }

    @Override public String toString() {
        return name + " " + type.sqlName();
    }
}
