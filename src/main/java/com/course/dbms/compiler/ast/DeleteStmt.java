package com.course.dbms.compiler.ast;

/** DELETE 的目标表、条件及写入参数。 */
public class DeleteStmt extends Stmt {
    public final String tableName;
    public final Cond where;

    public DeleteStmt(String tableName, Cond where) {
        this.tableName = tableName;
        this.where = where;
    }
}
