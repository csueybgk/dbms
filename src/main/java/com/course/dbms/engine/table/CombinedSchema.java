package com.course.dbms.engine.table;

import com.course.dbms.common.Error;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多表拼接后的"逻辑列集合"。
 * 列顺序 = 各表列按 FROM/JOIN 顺序拼接，每个列记录 {限定符(别名/表名), 列名, 类型}。
 * 供 Join / Filter / Aggregate 把列引用（可带限定符）解析成拼接行上的下标，
 * 并处理裸列名的唯一性 / 歧义。
 */
public class CombinedSchema {

    private static final class Bound {
        final String qualifier; // 可空
        final String name;
        final FieldType type;
        Bound(String qualifier, String name, FieldType type) {
            this.qualifier = qualifier;
            this.name = name;
            this.type = type;
        }
    }

    private final List<Bound> cols = new ArrayList<>();
    private final Map<String, Integer> byQualified = new HashMap<>();
    private final Map<String, Integer> bareCount = new HashMap<>();

    /** 追加一列；qualifier 为该列所属表的限定名（别名或表名）。返回拼接下标。 */
    public int add(String qualifier, String name, FieldType type) {
        int idx = cols.size();
        cols.add(new Bound(qualifier, name, type));
        if (qualifier != null) {
            byQualified.put((qualifier.toLowerCase() + "." + name.toLowerCase()), idx);
        }
        String bare = name.toLowerCase();
        Integer c = bareCount.get(bare);
        bareCount.put(bare, c == null ? 1 : c + 1);
        return idx;
    }

    /** 把列引用解析成拼接行下标。限定名查 map；裸名须全局唯一，否则抛 SE-0004。 */
    public int resolve(String qualifier, String name) {
        if (qualifier != null) {
            Integer i = byQualified.get(qualifier.toLowerCase() + "." + name.toLowerCase());
            if (i == null) throw new Error("SE-0004", "列不存在: " + qualifier + "." + name);
            return i;
        }
        String bare = name.toLowerCase();
        Integer cnt = bareCount.get(bare);
        if (cnt == null) throw new Error("SE-0004", "列不存在: " + name);
        if (cnt > 1) throw new Error("SE-0004", "列名有歧义: " + name + "（请用 表名.列名 限定）");
        for (int i = 0; i < cols.size(); i++) {
            if (cols.get(i).name.equalsIgnoreCase(name)) return i;
        }
        throw new Error("SE-0004", "列不存在: " + name);
    }

    public int width() { return cols.size(); }
    public FieldType typeAt(int idx) { return cols.get(idx).type; }
    public String nameAt(int idx) { return cols.get(idx).name; }
    public String qualifierAt(int idx) { return cols.get(idx).qualifier; }

    /** 生成列名唯一的 Schema（限定列用 qualifier.name，裸列用 name），供 StorageEngine.cast 按位置取类型。 */
    public Schema toSchema() {
        Schema s = new Schema();
        for (Bound b : cols) {
            s.add(b.qualifier != null ? b.qualifier + "." + b.name : b.name, b.type);
        }
        return s;
    }
}
