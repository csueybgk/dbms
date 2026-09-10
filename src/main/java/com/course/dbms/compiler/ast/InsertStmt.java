package com.course.dbms.compiler.ast;

import java.util.List;

/** INSERT INTO t VALUES (...) 语句。values 为字面量列表。 */
public class InsertStmt extends Stmt {
    public final String tableName;
    public final List<Object> values;

    public InsertStmt(String tableName, List<Object> values) {
        this.tableName = tableName;
        this.values = values;
    }
}
