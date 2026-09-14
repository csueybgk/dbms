package com.course.dbms.compiler.ast;

import com.course.dbms.engine.table.Column;
import com.course.dbms.engine.table.Constraint;

import java.util.Collections;
import java.util.List;

/** CREATE TABLE 语句。constraints 是列级与表级约束合并后的列表（无约束时为空列表）。 */
public class CreateStmt extends Stmt {
    public final String tableName;
    public final List<Column> columns;
    public final List<Constraint> constraints;

    public CreateStmt(String tableName, List<Column> columns) {
        this(tableName, columns, Collections.<Constraint>emptyList());
    }

    public CreateStmt(String tableName, List<Column> columns, List<Constraint> constraints) {
        this.tableName = tableName;
        this.columns = columns;
        this.constraints = constraints;
    }

    /**
     * 供 trace 第②阶段展示（{@link com.course.dbms.compiler.Tracer} 靠反射判断是否
     * 自定义了 toString，不写就只打印节点类名，约束在新语法里会完全看不见）。
     * 引用单列的约束贴在它那一列后面，多列的排在最后。
     */
    @Override public String toString() {
        StringBuilder sb = new StringBuilder("create table ").append(tableName).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            Column col = columns.get(i);
            if (i > 0) sb.append(", ");
            sb.append(col);
            for (Constraint c : constraints) {
                if (c.singleColumn() && c.columns().get(0).equalsIgnoreCase(col.name())) {
                    sb.append(' ').append(c.describe());
                }
            }
        }
        for (Constraint c : constraints) {
            if (c.singleColumn()) continue;              // 已贴在列上
            sb.append(", ").append(c);
        }
        return sb.append(')').toString();
    }
}
