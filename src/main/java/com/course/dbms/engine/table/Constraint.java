package com.course.dbms.engine.table;

import com.course.dbms.compiler.Parser;
import com.course.dbms.compiler.ast.Cond;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条完整性约束。列级与表级约束共用本类表示，区别只在 {@link #columns()} 的列数：
 *   - 引用单列的约束（NOT NULL / DEFAULT / 列级 PK/UNIQUE/CHECK）显示在该列的格子里；
 *   - 引用多列的约束（复合 PRIMARY KEY / UNIQUE）另起一行。
 * 显示规则按"列数"而不是按"写在哪"来定，否则 `id int32 primary key` 与
 * `primary key (id)` 两种等价写法会展示得不一样。
 *
 * detail 存 SQL 原文而不是解析后的对象，有两个理由：
 *   1) 落盘只能存基本类型（sys_constraints 是普通表，见 Catalog）；
 *   2) STRING 列的默认值 'null' 与 DEFAULT NULL 靠原文区分才不会混淆。
 * 解析结果缓存在 transient 字段里，避免每条 INSERT 都重新解析一次。
 */
public class Constraint implements Serializable {

    public enum Kind {
        PRIMARY_KEY("primary key"),
        UNIQUE("unique"),
        NOT_NULL("not null"),
        CHECK("check"),
        DEFAULT("default");

        private final String sql;
        Kind(String sql) { this.sql = sql; }
        public String sql() { return sql; }

        /** 反查（从 sys_constraints 读回时用）；未知抛 CT-0002。 */
        public static Kind fromSql(String s) {
            for (Kind k : values()) {
                if (k.sql.equalsIgnoreCase(s)) return k;
            }
            throw new com.course.dbms.common.Error("CT-0002", "unknown constraint kind: " + s);
        }
    }

    private final Kind kind;
    private final String name;           // 用户用 constraint <名> 起的名字；未起名时为空串
    private final List<String> columns;  // 涉及的列名（PRIMARY_KEY / UNIQUE 可多列，其余单列）
    private final String detail;         // DEFAULT: 字面量原文；CHECK: 表达式 SQL；其余为 null

    private transient Cond cond;             // CHECK 的解析结果
    private transient Object defaultValue;   // DEFAULT 的解析结果
    private transient boolean defaultResolved;

    public Constraint(Kind kind, String name, List<String> columns, String detail) {
        this.kind = kind;
        this.name = name == null ? "" : name;
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        this.detail = detail;
    }

    /** 单列约束的快捷构造（列级语法用）。 */
    public static Constraint onColumn(Kind kind, String name, String column, String detail) {
        return new Constraint(kind, name, Collections.singletonList(column), detail);
    }

    public Kind kind() { return kind; }
    public String name() { return name; }
    public List<String> columns() { return columns; }
    public String detail() { return detail; }

    /** 只引用一列（显示时贴在那一列的格子里）。 */
    public boolean singleColumn() { return columns.size() == 1; }

    /**
     * CHECK 的条件树。首次调用时从 detail 重新解析并缓存 —— 落盘只能存文本，
     * 重启/回滚后要重建这棵树。解析失败说明元数据被写坏了，直接抛错而非静默放行。
     */
    public Cond cond() {
        if (cond == null) cond = Parser.parseCondition(detail);
        return cond;
    }

    /** DEFAULT 的字面量值，按目标列类型解析（结果缓存；同一约束的列类型不会变）。 */
    public Object defaultValue(FieldType type) {
        if (!defaultResolved) {
            defaultValue = type.parseLiteral(detail);
            defaultResolved = true;
        }
        return defaultValue;
    }

    /** show table 里展示的文本。 */
    public String describe() {
        switch (kind) {
            case CHECK:   return "check (" + detail + ")";
            case DEFAULT: return "default " + detail;
            default:      return kind.sql();
        }
    }

    @Override public String toString() {
        return (name.isEmpty() ? "" : "constraint " + name + " ") + describe()
                + " (" + String.join(", ", columns) + ")";
    }
}
