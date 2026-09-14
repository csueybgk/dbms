package com.course.dbms.engine.exec.op;

import com.course.dbms.engine.table.Catalog;
import com.course.dbms.engine.table.Column;
import com.course.dbms.engine.table.Constraint;
import com.course.dbms.engine.table.Row;
import com.course.dbms.engine.table.Schema;
import com.course.dbms.engine.table.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * SHOW TABLE <name> 算子：展示某张表的结构（列名 + 类型 + 约束），
 * 直观呈现系统目录中存放的元数据。
 *
 * 约束的排布规则：只引用一列的约束（NOT NULL / DEFAULT / 列级 PK/UNIQUE/CHECK）
 * 贴在那一列的格子里；引用多列或整表的（复合 PK/UNIQUE、表级 CHECK）另起一行。
 * 按"引用了几列"而不是"写在哪"来分，否则 `id int32 primary key` 与
 * `primary key (id)` 这两种等价写法会展示得不一样。
 */
public class ShowTable extends Operator {

    private final Catalog catalog;
    private final String name;

    public ShowTable(Catalog catalog, String name) {
        super(null);
        this.catalog = catalog;
        this.name = name;
    }

    @Override public List<Row> execute() {
        Table table = catalog.getTable(name);   // 表不存在抛 TB-0001
        Schema schema = table.schema();
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < schema.columnCount(); i++) {
            Column c = schema.column(i);
            out.add(Row.of(c.name(), c.type().sqlName(), onColumn(schema, c.name())));
        }
        for (Constraint con : schema.constraints()) {
            if (con.singleColumn()) continue;   // 已贴在列上
            out.add(Row.of("(table)", "", con.describe()));
        }
        return out;
    }

    /** 挂在某一列上的约束文本（多条用逗号连起来），没有则空串。 */
    private static String onColumn(Schema schema, String columnName) {
        StringBuilder sb = new StringBuilder();
        for (Constraint con : schema.constraints()) {
            if (!con.singleColumn() || !con.columns().get(0).equalsIgnoreCase(columnName)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(con.describe());
        }
        return sb.toString();
    }

    @Override public String name() { return "ShowTable(" + name + ")"; }
}
