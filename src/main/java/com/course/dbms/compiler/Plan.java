package com.course.dbms.compiler;

import com.course.dbms.engine.exec.op.Operator;

import java.util.List;

/**
 * 执行计划：一条 SQL 之后要跑的算子树根 + 输出列名。
 * 由 {@link PlanBuilder} 从 AST 生成，交给执行引擎执行。
 */
public class Plan {

    public final Operator root;
    public final List<String> columns;

    public Plan(Operator root, List<String> columns) {
        this.root = root;
        this.columns = columns;
    }

    @Override public String toString() {
        return root == null ? "<noop>" : root.toString();
    }
}
