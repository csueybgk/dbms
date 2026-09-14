package com.course.dbms.engine.exec.op;

import com.course.dbms.engine.index.IndexManager;
import com.course.dbms.engine.storage.StorageEngine;
import com.course.dbms.engine.table.ConstraintChecker;
import com.course.dbms.engine.table.Row;
import com.course.dbms.engine.table.Table;

import java.util.Collections;
import java.util.List;

/**
 * 插入算子（DML，叶子动作）：把一行写入目标表，返回受影响行数。
 *
 * 写盘【之前】先过一遍完整性约束（NOT NULL / CHECK / UNIQUE / PRIMARY KEY）——
 * 这是权威检查点：唯一性依赖表中的实际数据，只有持数据的一方能判定。
 * Analyzer 里那套只做静态预检（列数、列类型），不做数据相关判定。
 *
 * 插入成功后把这行的 RID 记进该表上的所有索引。
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
        // 判重要看已有数据，故这里现扫一遍。checker 现建现用：一次 execute 就是一条语句，
        // 内部的"本次已接受键"集合不会跨语句残留
        new ConstraintChecker().check(table, row, null, se.scanLocated(table));
        int[] rid = se.insert(table, row);   // 返回 (页号,槽位)
        if (rid != null && indexes != null) {
            indexes.onInsert(table, row, rid[0], rid[1]);
        }
        return Collections.singletonList(Row.of(rid != null ? 1 : 0));
    }

    @Override public String name() { return "Insert(" + table.name() + ")"; }
}
