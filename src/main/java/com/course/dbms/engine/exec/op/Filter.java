package com.course.dbms.engine.exec.op;

import com.course.dbms.common.Error;
import com.course.dbms.compiler.ast.Cond;
import com.course.dbms.engine.table.Row;
import com.course.dbms.engine.table.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * 过滤算子：对子管线的每一行按 WHERE 条件判定，保留满足条件的行。
 * 条件求值委托给 {@link CondEval}（支持列-列比较），单表/多表共用。
 */
public class Filter extends Operator {

    private final Schema castSchema;
    private final CondEval.Resolver resolver;
    private final Cond cond;

    /** 单表过滤：列名按 schema 解析（旧路径；行为与原 Filter 一致）。 */
    public Filter(Operator child, Schema schema, Cond cond) {
        this(child, schema, (q, n) -> {
            int i = schema.indexOf(n);
            if (i < 0) throw new Error("SE-0004", "列不存在: " + n);
            return i;
        }, cond);
    }

    /** 多表过滤：列引用经 resolver（通常由 CombinedSchema）解析成行下标。 */
    public Filter(Operator child, Schema castSchema, CondEval.Resolver resolver, Cond cond) {
        super(child);
        this.castSchema = castSchema;
        this.resolver = resolver;
        this.cond = cond;
    }

    @Override public List<Row> execute() {
        List<Row> out = new ArrayList<>();
        for (Row r : child.execute()) {
            if (CondEval.eval(cond, castSchema, r, resolver)) out.add(r);
        }
        return out;
    }

    @Override public String name() { return "Filter(" + cond + ")"; }
}
