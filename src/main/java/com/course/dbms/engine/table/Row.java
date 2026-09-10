package com.course.dbms.engine.table;

import java.util.ArrayList;
import java.util.List;

/** 一行数据：按列顺序的值列表。 */
public class Row {

    private final List<Object> values;

    public Row(List<Object> values) {
        this.values = new ArrayList<>(values);
    }

    public static Row of(Object... vs) {
        List<Object> l = new ArrayList<>();
        for (Object v : vs) l.add(v);
        return new Row(l);
    }

    public int size() { return values.size(); }
    public Object get(int i) { return values.get(i); }
    public List<Object> values() { return values; }

    @Override public String toString() { return values.toString(); }
}
