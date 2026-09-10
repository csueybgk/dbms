package com.course.dbms.engine.exec.op;

import com.course.dbms.engine.table.Catalog;
import com.course.dbms.engine.table.Row;
import com.course.dbms.engine.table.Schema;

import java.util.Collections;
import java.util.List;

/**
 * 建表算子（DDL，叶子动作）：在系统目录中登记一张新表。
 */
public class CreateTable extends Operator {

    private final Catalog catalog;
    private final String name;
    private final Schema schema;

    public CreateTable(Catalog catalog, String name, Schema schema) {
        super(null);
        this.catalog = catalog;
        this.name = name;
        this.schema = schema;
    }

    @Override public List<Row> execute() {
        catalog.createTable(name, schema);
        return Collections.singletonList(Row.of("table '" + name + "' created"));
    }

    @Override public String name() { return "CreateTable(" + name + ")"; }
}
