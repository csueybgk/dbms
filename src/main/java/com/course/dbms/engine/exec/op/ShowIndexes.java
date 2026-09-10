package com.course.dbms.engine.exec.op;

import com.course.dbms.engine.index.Index;
import com.course.dbms.engine.table.Catalog;
import com.course.dbms.engine.table.Row;
import com.course.dbms.engine.table.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * SHOW INDEXES 算子（叶子动作）：列出索引 (索引名, 表, 列)。
 * 指定表名时只列该表上的索引。
 */
public class ShowIndexes extends Operator {

    private final Catalog catalog;
    private final String tableName;   // null = 全部

    public ShowIndexes(Catalog catalog, String tableName) {
        super(null);
        this.catalog = catalog;
        this.tableName = tableName;
    }

    @Override public List<Row> execute() {
        List<Row> out = new ArrayList<>();
        Integer onlyTable = null;
        if (tableName != null) {
            Table t = catalog.getTable(tableName);              // 表不存在抛 TB-0001
            onlyTable = Integer.valueOf(t.tableId());
        }
        for (Index idx : catalog.indexes()) {
            if (onlyTable != null && idx.tableId() != onlyTable.intValue()) continue;
            out.add(Row.of(idx.name(), idx.tableName(), idx.columnName()));
        }
        return out;
    }

    @Override public String name() { return "ShowIndexes"; }
}
