package com.course.dbms.engine.exec.op;

import com.course.dbms.engine.table.Row;

import java.util.List;

/**
 * 物理算子基类。算子树从上到下组合成一条执行管线，root.execute() 产出最终行集合。
 * 流式算子（SeqScan→Filter→Project→Sort）持有 child，逐层拉取；
 * 叶子动作算子（CreateTable/Insert/Show*）无 child，直接产生结果。
 * 对应图片"③ 执行引擎：CreateTable/Insert/SeqScan/Filter/Project 五算子"。
 */
public abstract class Operator {

    protected final Operator child;

    protected Operator(Operator child) {
        this.child = child;
    }

    /** 执行本算子（含其子管线），返回输出行。 */
    public abstract List<Row> execute();

    /** 算子名，用于日志/计划展示。 */
    public abstract String name();

    /** 子算子（流式管线中层叠；叶子动作为 null），供测试与计划展示使用。 */
    public Operator child() { return child; }

    @Override public String toString() {
        return name() + (child != null ? "(" + child + ")" : "");
    }
}
