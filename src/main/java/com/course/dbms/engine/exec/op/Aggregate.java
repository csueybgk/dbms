package com.course.dbms.engine.exec.op;

import com.course.dbms.common.Error;
import com.course.dbms.engine.table.FieldType;
import com.course.dbms.engine.table.Row;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聚合算子：按分组键（groupIdx，空 = 全局聚合）把子管线结果分组，每组产出一行。
 * 每个 SELECT 项如何产出由一个 {@link Item} 描述：
 *   - 非聚合项：直接取分组键的第 groupPos 位；
 *   - 聚合项：COUNT/SUM/AVG/MIN/MAX 作用于参数列 argIdx（COUNT(*) 计数全部行）。
 * 聚合语义：COUNT → Long；SUM → 参数列整型时 Long、浮点型时 Double；AVG → Double；MIN/MAX → 源值。
 * 空输入下全局聚合仍产出一行（COUNT=0，其余为空），分组聚合则输出空。
 */
public class Aggregate extends Operator {

    /** 某个 SELECT 项如何产出。 */
    public static final class Item {
        public final boolean isAgg;
        public final int groupPos;      // !isAgg：分组键在输出行中的位次
        public final String func;       // isAgg：count/sum/avg/min/max
        public final int argIdx;        // isAgg：参数列在子管线行中的下标（COUNT(*) 为 -1）
        public final boolean countStar; // isAgg 且 COUNT(*)
        public final FieldType argType; // isAgg 且 !countStar：参数列类型（决定 SUM 整型/浮点）

        public Item(boolean isAgg, int groupPos, String func, int argIdx, boolean countStar, FieldType argType) {
            this.isAgg = isAgg;
            this.groupPos = groupPos;
            this.func = func;
            this.argIdx = argIdx;
            this.countStar = countStar;
            this.argType = argType;
        }
    }

    private final int[] groupIdx;
    private final Item[] items;

    public Aggregate(Operator child, int[] groupIdx, List<Item> items) {
        super(child);
        this.groupIdx = groupIdx;
        this.items = items.toArray(new Item[0]);
    }

    private static final class Acc {
        long count;
        long lsum;
        double dsum;
        Object min;
        Object max;
        boolean any; // 是否遇到非 NULL 值（用于区分 SUM 无结果=空）
    }

    @Override public List<Row> execute() {
        List<Row> rows = child.execute();
        LinkedHashMap<List<Object>, Acc[]> groups = new LinkedHashMap<>();
        if (groupIdx.length == 0) {
            groups.put(Arrays.asList(), new Acc[items.length]); // 全局聚合：空输入也产出一行
        }
        for (Row r : rows) {
            List<Object> key = key(r);
            Acc[] accs = groups.get(key);
            if (accs == null) { accs = new Acc[items.length]; groups.put(key, accs); }
            feed(accs, r);
        }
        List<Row> out = new ArrayList<>();
        for (Map.Entry<List<Object>, Acc[]> e : groups.entrySet()) {
            Acc[] accs = e.getValue();
            List<Object> vals = new ArrayList<>(items.length);
            for (int i = 0; i < items.length; i++) {
                Item it = items[i];
                if (!it.isAgg) {
                    vals.add(e.getKey().get(it.groupPos));
                } else {
                    Acc a = accs[i];
                    if (a == null) a = new Acc(); // 未喂到行（空输入）
                    vals.add(result(a, it));
                }
            }
            out.add(new Row(vals));
        }
        return out;
    }

    private void feed(Acc[] accs, Row r) {
        for (int i = 0; i < items.length; i++) {
            Item it = items[i];
            if (!it.isAgg) continue;
            Acc a = accs[i];
            if (a == null) { a = new Acc(); accs[i] = a; }
            accumulate(a, it, r);
        }
    }

    private void accumulate(Acc a, Item it, Row r) {
        if (it.func.equals("count")) {
            if (it.countStar) { a.count++; return; }
            Object v = r.get(it.argIdx);
            if (v != null) { a.any = true; a.count++; }
            return;
        }
        Object v = r.get(it.argIdx);
        if (v == null) return;                    // 聚合忽略 NULL
        a.any = true;
        Number n = (Number) v;
        switch (it.func) {
            case "sum":
                if (integral(it.argType)) a.lsum += n.longValue();
                else a.dsum += n.doubleValue();
                break;
            case "avg":
                a.dsum += n.doubleValue();
                a.count++;
                break;
            case "min":
                if (a.min == null || Compare.compare(v, a.min) < 0) a.min = v;
                break;
            case "max":
                if (a.max == null || Compare.compare(v, a.max) > 0) a.max = v;
                break;
            default:
                throw new Error("EX-0002", "unknown aggregate: " + it.func);
        }
    }

    private Object result(Acc a, Item it) {
        switch (it.func) {
            case "count": return Long.valueOf(a.count);
            case "sum":
                if (!a.any) return null;
                return integral(it.argType) ? Long.valueOf(a.lsum) : Double.valueOf(a.dsum);
            case "avg":
                if (!a.any) return null;
                return Double.valueOf(a.dsum / a.count);
            case "min": return a.min;
            case "max": return a.max;
            default: throw new Error("EX-0002", "unknown aggregate: " + it.func);
        }
    }

    private List<Object> key(Row r) {
        List<Object> k = new ArrayList<>(groupIdx.length);
        for (int g : groupIdx) k.add(r.get(g));
        return k;
    }

    private static boolean integral(FieldType t) {
        return t == FieldType.INT32 || t == FieldType.INT64;
    }

    @Override public String name() { return "Aggregate" + Arrays.toString(groupIdx); }
}
