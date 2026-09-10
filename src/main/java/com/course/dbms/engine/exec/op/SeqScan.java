package com.course.dbms.engine.exec.op;

import com.course.dbms.engine.storage.StorageEngine;
import com.course.dbms.engine.table.Row;
import com.course.dbms.engine.table.Table;

import java.util.List;

/**
 * 全表扫描算子：把一张表的全部行读出来（叶子，无 child）。
 * 由存储引擎的 StorageEngine.scan 逐页逐槽位实现。
 */
public class SeqScan extends Operator {

    private final StorageEngine se;
    private final Table table;

    public SeqScan(StorageEngine se, Table table) {
        super(null);
        this.se = se;
        this.table = table;
    }

    public Table table() { return table; }

    @Override public List<Row> execute() {
        return se.scan(table);
    }

    @Override public String name() { return "SeqScan(" + table.name() + ")"; }
}
