package com.course.dbms.engine.exec;

import com.course.dbms.compiler.Plan;
import com.course.dbms.engine.Result;
import com.course.dbms.engine.table.Row;

import java.util.List;

/**
 * 执行引擎：驱动一条执行计划（算子树），返回结构化结果。
 * 自身不参与 SQL 编译/语义校验；这些由上层 {@literal com.course.dbms.db.Database} 负责。
 * 对应图片"③ 执行引擎：执行各种算子"。
 */
public class Executor {

    public Result execute(Plan plan) {
        List<Row> rows = plan.root.execute();
        return new Result(plan.columns, rows);
    }
}
