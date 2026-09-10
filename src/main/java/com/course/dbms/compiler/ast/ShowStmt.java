package com.course.dbms.compiler.ast;

/**
 * SHOW 语句：三种形态
 *   show tables                —— list=true
 *   show table <name>          —— list=false，看表结构
 *   show indexes [on <name>]   —— indexes=true，列出索引（tableName 为 null 表示全部）
 */
public class ShowStmt extends Stmt {
    public final boolean list;        // true=show tables
    public final String tableName;    // show table 时的表名；show indexes 时可空
    public final boolean indexes;     // true=show indexes

    public ShowStmt(boolean list, String tableName) {
        this(list, tableName, false);
    }

    public ShowStmt(boolean list, String tableName, boolean indexes) {
        this.list = list;
        this.tableName = tableName;
        this.indexes = indexes;
    }

    /** show indexes [on <表>]。 */
    public static ShowStmt indexes(String tableName) {
        return new ShowStmt(false, tableName, true);
    }
}
