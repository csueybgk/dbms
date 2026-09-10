package com.course.dbms.engine.exec.op;

import com.course.dbms.engine.index.IndexManager;
import com.course.dbms.engine.storage.StorageEngine;
import com.course.dbms.engine.table.Row;
import com.course.dbms.engine.table.Table;

import java.util.Collections;
import java.util.List;

/**
 * 插入算子（DML，叶子动作）：把一行写入目标表，返回受影响行数。
 *
 * 插入成功后顺手把这行的 RID 记进该表上的所有索引（本系统没有 delete/update，
 * insert 是唯一写路径，所以索引维护一处钩子即可覆盖）。
 */
public class Insert extends Operator {

    private final StorageEngine se;
    private final Table table;
    private final Row row;
    private final IndexManager indexes;   // 可为 null（无索引环境）

    public Insert(StorageEngine se, Table table, Row row) {
        this(se, table, row, null);
    }

    public Insert(StorageEngine se, Table table, Row row, IndexManager indexes) {
        super(null);
        this.se = se;
        this.table = table;
        this.row = row;
        this.indexes = indexes;
    }

    @Override public List<Row> execute() {
        int[] rid = se.insert(table, row);   // 返回 (页号,槽位)
        if (rid != null && indexes != null) {
            indexes.onInsert(table, row, rid[0], rid[1]);
        }
        return Collections.singletonList(Row.of(rid != null ? 1 : 0));
    }

    @Override public String name() { return "Insert(" + table.name() + ")"; }
}
