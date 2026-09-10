package com.course.dbms.compiler.ast;

/**
 * FROM 子句中的一张表引用（含可选别名）。
 * alias 为 null 表示未起别名，限定名用表名本身（如 users.id）。
 * 起别名后限定名用别名（如 a.id），表原名不再可用于限定。
 */
public class TableRef {

    public final String name;
    public final String alias; // 可空

    public TableRef(String name, String alias) {
        this.name = name;
        this.alias = alias;
    }

    /** 用于列限定的名字：有别名用别名，否则用表名。 */
    public String effective() { return alias != null ? alias : name; }

    @Override public String toString() {
        return alias != null ? name + " AS " + alias : name;
    }
}
