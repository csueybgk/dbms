package com.course.dbms.compiler.ast;

/**
 * 列引用：可带表限定符。
 * qualifier 为 null 表示裸列名（如 id）；否则是 表名/别名.列名（如 a.id）。
 * 用于多表联查里的限定列、GROUP BY 列表与聚合函数的参数列。
 */
public class ColRef {

    public final String qualifier; // 可空 = 裸列
    public final String name;

    public ColRef(String qualifier, String name) {
        this.qualifier = qualifier;
        this.name = name;
    }

    public boolean qualified() { return qualifier != null; }

    public String display() { return qualified() ? qualifier + "." + name : name; }

    @Override public String toString() { return display(); }
}
