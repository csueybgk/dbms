package com.course.dbms.engine.exec.op;

import com.course.dbms.engine.table.Catalog;
import com.course.dbms.engine.table.Row;

import java.util.Collections;
import java.util.List;

/**
 * 建索引算子（DDL，叶子动作）：在系统目录登记索引元数据并建出内存 B+ 树。
 */
public class CreateIndex extends Operator {

    private final Catalog catalog;
    private final String indexName;
    private final String tableName;
    private final String columnName;

    public CreateIndex(Catalog catalog, String indexName, String tableName, String columnName) {
        super(null);
        this.catalog = catalog;
        this.indexName = indexName;
        this.tableName = tableName;
        this.columnName = columnName;
    }

    @Override public List<Row> execute() {
        catalog.createIndex(indexName, tableName, columnName);
        return Collections.singletonList(Row.of("index '" + indexName + "' created"));
    }

    @Override public String name() { return "CreateIndex(" + indexName + ")"; }
}
