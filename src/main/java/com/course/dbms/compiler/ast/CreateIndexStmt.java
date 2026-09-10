package com.course.dbms.compiler.ast;

/** CREATE INDEX <名> ON <表> '(' <列> ')' —— 在单列上建一个索引。 */
public class CreateIndexStmt extends Stmt {
    public final String indexName;
    public final String tableName;
    public final String columnName;

    public CreateIndexStmt(String indexName, String tableName, String columnName) {
        this.indexName = indexName;
        this.tableName = tableName;
        this.columnName = columnName;
    }

    @Override public String toString() {
        return "CREATE INDEX " + indexName + " ON " + tableName + "(" + columnName + ")";
    }
}
