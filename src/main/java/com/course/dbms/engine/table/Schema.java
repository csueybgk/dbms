package com.course.dbms.engine.table;

import com.course.dbms.common.Error;
import com.course.dbms.common.ErrorCode;

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
        if (i < 0) {
            throw new Error(ErrorCode.SE_COLUMN_NOT_FOUND,
                    "列不存在: " + name + "（该表列为 " + columnNames() + "）");
        }
        return columns.get(i).type();
    }

    /**
     * 列名清单，用于"列不存在"类报错里给出候选。
     * 超过 10 列只列前 10 个再加省略号，避免宽表把错误刷屏。
     */
    public String columnNames() {
        StringBuilder sb = new StringBuilder();
        int show = Math.min(columns.size(), 10);
        for (int i = 0; i < show; i++) {
            if (i > 0) sb.append(", ");
            sb.append(columns.get(i).name());
        }
        if (columns.size() > show) sb.append(", …（共 ").append(columns.size()).append(" 列）");
        return sb.toString();
    }

    @Override public String toString() {
        return columns.toString();
    }
}
