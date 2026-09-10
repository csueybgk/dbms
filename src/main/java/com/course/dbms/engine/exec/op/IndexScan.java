package com.course.dbms.engine.exec.op;

import com.course.dbms.engine.index.BPlusTree;
import com.course.dbms.engine.storage.StorageEngine;
import com.course.dbms.engine.table.Row;
import com.course.dbms.engine.table.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * 索引扫描算子：走 B+ 树按范围取 RID，再回表按 RID 取行（叶子算子，无 child）。
 *
 * 与 SeqScan 的区别是"取哪些行"：SeqScan 逐页逐槽位读全表，
 * IndexScan 只在树上取落在 [lo, hi] 里的 RID，行数少时省掉大量页读。
 * 输出按【索引键有序】（沿叶子链右扫），但 SQL 不保证无序查询的行序，故上层仍照常叠 Filter/Sort。
 *
 * 关键：规划器不会因为走了索引就把 WHERE 去掉 —— Filter 始终保留在 IndexScan 之上，
 * 索引只负责"缩小扫描范围"，结果永远由 Filter 判定，索引退化时也不会出错。
 */
public class IndexScan extends Operator {

    private final StorageEngine se;
    private final Table table;
    private final BPlusTree tree;
    private final int keyCol;
    private final Object lo;
    private final boolean loInc;
    private final Object hi;
    private final boolean hiInc;

    /** lo/hi 为 null 表示该侧无界；loInc/hiInc 表示是否含端点。 */
    public IndexScan(StorageEngine se, Table table, BPlusTree tree, int keyCol,
                     Object lo, boolean loInc, Object hi, boolean hiInc) {
        super(null);
        this.se = se;
        this.table = table;
        this.tree = tree;
        this.keyCol = keyCol;
        this.lo = lo;
        this.loInc = loInc;
        this.hi = hi;
        this.hiInc = hiInc;
    }

    public Table table() { return table; }

    /** 被索引的列下标（测试与计划展示用）。 */
    public int keyColumn() { return keyCol; }

    /** 扫描区间下界（null = 无下界）与是否含端点。 */
    public Object lower() { return lo; }
    public boolean lowerInclusive() { return loInc; }

    /** 扫描区间上界（null = 无上界）与是否含端点。 */
    public Object upper() { return hi; }
    public boolean upperInclusive() { return hiInc; }

    @Override public List<Row> execute() {
        List<Row> out = new ArrayList<>();
        for (int[] rid : tree.range(lo, loInc, hi, hiInc)) {
            Row r = se.fetch(table, rid[0], rid[1]);     // 回表
            if (r != null) out.add(r);                   // 失效 RID（页已回收）跳过
        }
        return out;
    }

    @Override public String name() { return "IndexScan(" + table.name() + ")"; }
}
