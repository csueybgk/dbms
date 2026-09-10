package com.course.dbms.engine.exec.op;

import com.course.dbms.engine.table.Catalog;
import com.course.dbms.engine.table.Row;
import com.course.dbms.engine.table.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * SHOW TABLES 算子：列出所有用户表名（只含普通表，不露系统目录表）。
 */
public class ShowTables extends Operator {

    private final Catalog catalog;

    public ShowTables(Catalog catalog) {
        super(null);
        this.catalog = catalog;
    }

    @Override public List<Row> execute() {
        List<Row> out = new ArrayList<>();
        for (Table t : catalog.listTables()) out.add(Row.of(t.name(), t.schema().columnCount()));
        return out;
    }

    @Override public String name() { return "ShowTables"; }
}
