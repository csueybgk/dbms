package com.course.dbms.engine.exec.op;

import com.course.dbms.compiler.ast.Cond;
import com.course.dbms.engine.table.CombinedSchema;
import com.course.dbms.engine.table.Row;
import com.course.dbms.engine.table.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * 连接算子：对左行 × 右行做嵌套循环，满足 ON（为 null 即笛卡尔积）则输出拼接行。
 * 行拼接顺序 = 左子管线各行在前、右子管线各行在后；leftOuter 时无匹配也输出一行（右列置 null）。
 * 说明：本算子基于全物化的 {left, right} 行列表（与项目其余算子一致，非流式）。
 */
public class Join extends Operator {

    private final Operator right;
    private final Cond on;                    // 可空 = 笛卡尔积
    private final CombinedSchema cs;
    private final CondEval.Resolver resolver;
    private final boolean leftOuter;
    private final int leftWidth;
    private final int rightWidth;

    public Join(Operator left, Operator right, Cond on, CombinedSchema cs,
                CondEval.Resolver resolver, boolean leftOuter, int leftWidth, int rightWidth) {
        super(left);
        this.right = right;
        this.on = on;
        this.cs = cs;
        this.resolver = resolver;
        this.leftOuter = leftOuter;
        this.leftWidth = leftWidth;
        this.rightWidth = rightWidth;
    }

    /** 右子算子。 */
    public Operator rightChild() { return right; }

    @Override public List<Row> execute() {
        List<Row> leftRows = child.execute();
        List<Row> rightRows = right.execute();
        Schema castSchema = cs.toSchema();
        List<Row> out = new ArrayList<>();
        for (Row lr : leftRows) {
            boolean matched = false;
            for (Row rr : rightRows) {
                Row joined = cat(lr, rr);
                if (on == null || CondEval.eval(on, castSchema, joined, resolver)) {
                    out.add(joined);
                    matched = true;
                }
            }
            if (!matched && leftOuter) {
                out.add(cat(lr, new Row(nulls(rightWidth))));
            }
        }
        return out;
    }

    private Row cat(Row l, Row r) {
        List<Object> vals = new ArrayList<>(l.size() + r.size());
        vals.addAll(l.values());
        vals.addAll(r.values());
        return new Row(vals);
    }

    private List<Object> nulls(int n) {
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(null);
        return l;
    }

    @Override public String name() {
        return (leftOuter ? "LeftJoin" : "Join") + (on != null ? "(" + on + ")" : "");
    }
}
