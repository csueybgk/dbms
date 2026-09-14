package com.course.dbms.engine.table;

import com.course.dbms.common.Error;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** 表结构：有序的列集合 + 完整性约束集合。 */
public class Schema implements Serializable {

    private final List<Column> columns = new ArrayList<>();
    private final List<Constraint> constraints = new ArrayList<>();

    public Schema add(String name, FieldType type) {
        columns.add(new Column(name, type));
        return this;
    }

    /** 追加一条完整性约束（列级与表级共用同一个列表）。 */
    public Schema addConstraint(Constraint c) {
        constraints.add(c);
        return this;
    }

    /** 全部约束（列级 + 表级）。 */
    public List<Constraint> constraints() { return constraints; }

    /** 取某列的 DEFAULT 约束；没有 DEFAULT 返回 null（调用方按 NULL 补全）。 */
    public Constraint defaultOf(int colIndex) {
        String name = column(colIndex).name();
        for (Constraint c : constraints) {
            if (c.kind() == Constraint.Kind.DEFAULT && c.singleColumn()
                    && c.columns().get(0).equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
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
