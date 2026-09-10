package com.course.dbms.engine;

import com.course.dbms.engine.table.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次查询的返回结果：列名 + 行。由服务端序列化发给客户端，客户端据此渲染表格。
 * 这是客户端/服务器端契约的核心数据结构。
 */
public class Result {

    public final List<String> columns;
    public final List<Row> rows;

    public Result(List<String> columns, List<Row> rows) {
        this.columns = columns;
        this.rows = rows;
    }

    public static Result message(String msg) {
        List<String> cols = new ArrayList<>();
        cols.add("result");
        List<Row> rs = new ArrayList<>();
        rs.add(Row.of(msg));
        return new Result(cols, rs);
    }

    public static Result affected(int n) {
        List<String> cols = new ArrayList<>();
        cols.add("affected");
        List<Row> rs = new ArrayList<>();
        rs.add(Row.of(n));
        return new Result(cols, rs);
    }

    public int rowCount() { return rows.size(); }

    @Override public String toString() {
        return columns + "\n" + rows;
    }
}
