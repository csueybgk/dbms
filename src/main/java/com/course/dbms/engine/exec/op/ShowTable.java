package com.course.dbms.engine.exec.op;

import com.course.dbms.engine.table.Catalog;
import com.course.dbms.engine.table.Column;
import com.course.dbms.engine.table.Row;
import com.course.dbms.engine.table.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * SHOW TABLE <name> 算子：展示某张表的结构（列名 + 类型），直观呈现系统目录中存放的元数据。
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
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < table.schema().columnCount(); i++) {
            Column c = table.schema().column(i);
            out.add(Row.of(c.name(), c.type().sqlName()));
        }
        return out;
    }

    @Override public String name() { return "ShowTable(" + name + ")"; }
}
