package com.course.dbms.engine.table;

import com.course.dbms.common.Error;
import com.course.dbms.common.ErrorCode;

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

    /**
     * 把列引用解析成拼接行下标。限定名查 map；裸名须全局唯一。
     * 歧义抛 SE-0017、不存在抛 SE-0004 —— 两种情况的修复动作不同（限定名 vs 改列名）。
     */
    public int resolve(String qualifier, String name) {
        if (qualifier != null) {
            Integer i = byQualified.get(qualifier.toLowerCase() + "." + name.toLowerCase());
            if (i == null) {
                throw new Error(ErrorCode.SE_COLUMN_NOT_FOUND,
                        "列不存在: " + qualifier + "." + name + "（" + qualifiedNames() + "）");
            }
            return i;
        }
        String bare = name.toLowerCase();
        Integer cnt = bareCount.get(bare);
        if (cnt == null) {
            throw new Error(ErrorCode.SE_COLUMN_NOT_FOUND,
                    "列不存在: " + name + "（" + columnNames() + "）");
        }
        if (cnt > 1) {
            throw new Error(ErrorCode.SE_AMBIGUOUS_COLUMN,
                    "列名有歧义: " + name + "（表 " + qualifiersOf(name)
                            + " 里都有这一列；请写成 表名." + name + "）");
        }
        for (int i = 0; i < cols.size(); i++) {
            if (cols.get(i).name.equalsIgnoreCase(name)) return i;
        }
        throw new Error(ErrorCode.SE_COLUMN_NOT_FOUND,
                "列不存在: " + name + "（" + columnNames() + "）");
    }

    /** 拼接后的全部列名（限定符.列名），用于"列不存在"时报出候选。 */
    public String qualifiedNames() {
        StringBuilder sb = new StringBuilder();
        int show = Math.min(cols.size(), 10);
        for (int i = 0; i < show; i++) {
            if (i > 0) sb.append(", ");
            sb.append(displayName(cols.get(i)));
        }
        if (cols.size() > show) sb.append(", …（共 ").append(cols.size()).append(" 列）");
        return sb.toString();
    }

    /** 裸列名清单，用于不带限定符的列引用报错。 */
    public String columnNames() {
        StringBuilder sb = new StringBuilder();
        int show = Math.min(cols.size(), 10);
        for (int i = 0; i < show; i++) {
            if (i > 0) sb.append(", ");
            sb.append(cols.get(i).name);
        }
        if (cols.size() > show) sb.append(", …（共 ").append(cols.size()).append(" 列）");
        return sb.toString();
    }

    /** 某个裸列名涉及哪些限定符（表名/别名），用于歧义报错。 */
    private String qualifiersOf(String name) {
        StringBuilder sb = new StringBuilder();
        for (Bound b : cols) {
            if (!b.name.equalsIgnoreCase(name)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(b.qualifier == null ? "(未命名)" : b.qualifier);
        }
        return sb.toString();
    }

    private static String displayName(Bound b) {
        return b.qualifier == null ? b.name : b.qualifier + "." + b.name;
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
