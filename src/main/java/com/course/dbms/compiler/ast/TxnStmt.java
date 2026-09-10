package com.course.dbms.compiler.ast;

/**
 * 事务控制语句：BEGIN / COMMIT / ROLLBACK。
 * 由 {@link com.course.dbms.db.Session} 在编译管线前端拦截处理，不生成执行计划。
 */
public class TxnStmt extends Stmt {

    public final TxOp op;

    public TxnStmt(TxOp op) {
        this.op = op;
    }
}
