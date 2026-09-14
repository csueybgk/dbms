package com.course.dbms.compiler.ast;

import java.util.List;

/**
 * INSERT INTO t [(列清单)] VALUES (...) 语句。values 为字面量列表（含 null）。
 * columns 为 null 表示没写列清单 —— 此时值必须覆盖全部列，按表结构顺序对应；
 * 写了列清单则只覆盖这些列，其余列由 DEFAULT 或 NULL 补全。
 */
public class InsertStmt extends Stmt {
    public final String tableName;
    public final List<String> columns;
    public final List<Object> values;

    public InsertStmt(String tableName, List<Object> values) {
        this(tableName, null, values);
    }

    public InsertStmt(String tableName, List<String> columns, List<Object> values) {
        this.tableName = tableName;
        this.columns = columns;
        this.values = values;
    }
}
