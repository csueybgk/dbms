package com.course.dbms.compiler.ast;

import com.course.dbms.engine.table.Column;

import java.util.List;

/** CREATE TABLE 语句。 */
public class CreateStmt extends Stmt {
    public final String tableName;
    public final List<Column> columns;

    public CreateStmt(String tableName, List<Column> columns) {
        this.tableName = tableName;
        this.columns = columns;
    }
}
