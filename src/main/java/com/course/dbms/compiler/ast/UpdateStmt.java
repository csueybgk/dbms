package com.course.dbms.compiler.ast;

/** UPDATE 的目标表、条件及写入参数。 */
public class UpdateStmt extends Stmt {
    public final String tableName;
    public final Cond where;
    public final java.util.Map<String, Object> assignments;

    public UpdateStmt(String tableName, Cond where, java.util.Map<String, Object> assignments) {
        this.tableName = tableName;
        this.where = where;
        this.assignments = new java.util.LinkedHashMap<>(assignments);
    }
}
