package com.course.dbms.engine.exec.op;

import com.course.dbms.engine.table.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * 排序算子：按指定源列（ORDER BY col）对子管线结果排序。
 * 作用于投影之前（因为在投影中该列可能被丢弃），故其 child 通常是 SeqScan/Filter。
 */
public class Sort extends Operator {

    private final int col;
    private final boolean desc;

    public Sort(Operator child, int col, boolean desc) {
        super(child);
        this.col = col;
        this.desc = desc;
    }

    @Override public List<Row> execute() {
        List<Row> rows = new ArrayList<>(child.execute());
        rows.sort((a, b) -> {
            int c = Compare.compare(a.get(col), b.get(col));
            return desc ? -c : c;
        });
        return rows;
    }

    @Override public String name() { return "Sort(col=" + col + ", " + (desc ? "desc" : "asc") + ")"; }
}
