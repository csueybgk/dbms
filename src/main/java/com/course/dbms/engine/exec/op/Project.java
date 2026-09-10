package com.course.dbms.engine.exec.op;

import com.course.dbms.engine.table.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * 投影算子：从每行中取出 SELECT 指定的列（按源列下标），丢弃其余列。
 * SELECT id, name → Project(sourceIdx=[0,1])。
 */
public class Project extends Operator {

    private final int[] cols;

    public Project(Operator child, int[] cols) {
        super(child);
        this.cols = cols;
    }

    @Override public List<Row> execute() {
        List<Row> out = new ArrayList<>();
        for (Row r : child.execute()) {
            List<Object> vals = new ArrayList<>(cols.length);
            for (int c : cols) vals.add(r.get(c));
            out.add(new Row(vals));
        }
        return out;
    }

    /** 投影的源列下标。 */
    public int[] columns() { return cols; }

    @Override public String name() { return "Project" + java.util.Arrays.toString(cols); }
}
