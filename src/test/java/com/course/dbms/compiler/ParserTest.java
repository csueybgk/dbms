package com.course.dbms.compiler;

import com.course.dbms.common.Error;
import com.course.dbms.compiler.ast.Cond;
import com.course.dbms.compiler.ast.CreateIndexStmt;
import com.course.dbms.compiler.ast.CreateStmt;
import com.course.dbms.compiler.ast.InsertStmt;
import com.course.dbms.compiler.ast.SelectStmt;
import com.course.dbms.compiler.ast.ShowStmt;
import com.course.dbms.compiler.ast.Stmt;
import com.course.dbms.engine.table.FieldType;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class ParserTest {

    @Test public void parseCreate() {
        Stmt s = new Parser("create table users (id int32, name string, age int32, score float64)").parse();
        assertTrue(s instanceof CreateStmt);
        CreateStmt c = (CreateStmt) s;
        assertEquals("users", c.tableName);
        assertEquals(4, c.columns.size());
        assertEquals("id", c.columns.get(0).name());
        assertEquals(FieldType.INT32, c.columns.get(0).type());
        assertEquals(FieldType.STRING, c.columns.get(1).type());
    }

    @Test public void parseInsert() {
        Stmt s = new Parser("insert into users values (1, 'alice', 23, 95.5)").parse();
        assertTrue(s instanceof InsertStmt);
        InsertStmt ins = (InsertStmt) s;
        assertEquals("users", ins.tableName);
        List<Object> v = ins.values;
        assertEquals(4, v.size());
        assertEquals(1L, v.get(0));          // 数字解析为 Long
        assertEquals("alice", v.get(1));
        assertEquals(23L, v.get(2));
        assertEquals(95.5, v.get(3));
    }

    @Test public void parseSelectStar() {
        Stmt s = new Parser("select * from users").parse();
        assertTrue(s instanceof SelectStmt);
        SelectStmt sel = (SelectStmt) s;
        assertTrue(sel.all);
        assertEquals("users", sel.tableName);
        assertNull(sel.where);
    }

    @Test public void parseSelectWithWhereOrder() {
        Stmt s = new Parser("select id, name from users where age > 24 and score >= 90 order by id desc").parse();
        SelectStmt sel = (SelectStmt) s;
        assertFalse(sel.all);
        assertEquals(2, sel.columns.size());
        assertEquals("id", sel.columns.get(0));
        assertEquals("name", sel.columns.get(1));
        assertNotNull(sel.where);
        assertEquals("age", sel.where.children.get(0).column);
        assertEquals("id", sel.orderBy);
        assertTrue(sel.orderDesc);
    }

    @Test public void parseShow() {
        assertTrue(new Parser("show tables").parse() instanceof ShowStmt);
        ShowStmt s = (ShowStmt) new Parser("show table users").parse();
        assertFalse(s.list);
        assertEquals("users", s.tableName);
    }

    @Test public void parseSemicolon() {
        assertTrue(new Parser("select * from t;").parse() instanceof SelectStmt);
        assertTrue(new Parser("select * from t;;").parse() instanceof SelectStmt);
    }

    @Test public void syntaxError() {
        try {
            new Parser("create table users id").parse();
            fail("should throw");
        } catch (Error e) {
            assertTrue(e.code().startsWith("SY"));
        }
    }

    @Test public void unknownTypeError() {
        try {
            new Parser("create table t (id banana)").parse();
            fail("should throw");
        } catch (Error e) {
            assertTrue(e.code().startsWith("SE")); // FieldType.fromName 抛 SE
        }
    }

    @Test public void parseJoin() {
        SelectStmt sel = (SelectStmt) new Parser(
                "select a.id, b.name from users a join orders b on a.id = b.uid").parse();
        assertTrue(sel.isRich());
        assertEquals(2, sel.from.size());
        assertEquals("users", sel.from.get(0).name);
        assertEquals("a", sel.from.get(0).alias);
        assertEquals("orders", sel.from.get(1).name);
        assertEquals("b", sel.from.get(1).alias);
        assertEquals(1, sel.joinOn.size());
        Cond on = sel.joinOn.get(0);
        assertEquals("id", on.column);
        assertEquals("a", on.qualifier);
        assertEquals("uid", on.column2);
        assertEquals("b", on.qualifier2);
        assertEquals("a", sel.items.get(0).col.qualifier);
        assertEquals("id", sel.items.get(0).col.name);
        assertEquals("b", sel.items.get(1).col.qualifier);
        assertEquals("name", sel.items.get(1).col.name);
    }

    @Test public void parseLeftJoin() {
        SelectStmt sel = (SelectStmt) new Parser(
                "select u.id from users u left join orders o on u.id = o.uid").parse();
        assertEquals(1, sel.joinOuter.size());
        assertTrue(sel.joinOuter.get(0));
        assertNotNull(sel.joinOn.get(0));
    }

    @Test public void parseAggregateCountStar() {
        SelectStmt sel = (SelectStmt) new Parser("select count(*) from users").parse();
        assertTrue(sel.isRich());
        assertEquals(1, sel.items.size());
        assertTrue(sel.items.get(0).isAgg());
        assertEquals("count", sel.items.get(0).func);
        assertNull(sel.items.get(0).arg);
    }

    @Test public void parseGroupByAndAggregate() {
        SelectStmt sel = (SelectStmt) new Parser(
                "select dept, avg(score) from users group by dept order by count(*) desc").parse();
        assertEquals(2, sel.items.size());
        assertEquals("dept", sel.items.get(0).col.name);
        assertTrue(sel.items.get(1).isAgg());
        assertEquals("avg", sel.items.get(1).func);
        assertEquals("score", sel.items.get(1).arg.name);
        assertEquals(1, sel.groupBy.size());
        assertEquals("dept", sel.groupBy.get(0).name);
        assertEquals("count", sel.orderBy);
        assertTrue(sel.orderDesc);
    }

    @Test public void parseAggregateAlias() {
        SelectStmt sel = (SelectStmt) new Parser("select count(*) as total from users").parse();
        assertEquals("total", sel.items.get(0).alias);
        assertEquals("total", sel.items.get(0).outputName());
    }

    @Test public void parseCrossJoin() {
        SelectStmt sel = (SelectStmt) new Parser("select * from a join b").parse();
        assertTrue(sel.all);
        assertNull(sel.joinOn.get(0));
        assertFalse(sel.joinOuter.get(0));
    }

    @Test public void parseQualifiedOrder() {
        SelectStmt sel = (SelectStmt) new Parser(
                "select u.name from users u order by u.age").parse();
        assertEquals("u.age", sel.orderBy);
    }

    @Test public void parseCreateIndex() {
        Stmt s = new Parser("create index idx_age on users(age)").parse();
        assertTrue(s instanceof CreateIndexStmt);
        CreateIndexStmt c = (CreateIndexStmt) s;
        assertEquals("idx_age", c.indexName);
        assertEquals("users", c.tableName);
        assertEquals("age", c.columnName);
    }

    @Test public void parseShowIndexes() {
        ShowStmt all = (ShowStmt) new Parser("show indexes").parse();
        assertTrue(all.indexes);
        assertNull(all.tableName);                 // 不带 ON 即全部
        ShowStmt onTable = (ShowStmt) new Parser("show indexes on users").parse();
        assertTrue(onTable.indexes);
        assertEquals("users", onTable.tableName);
        assertTrue("show index 单数也接受", ((ShowStmt) new Parser("show index").parse()).indexes);
    }

    @Test public void parseCreateIndexIncompleteIsSyntaxError() {
        Error e = null;
        try {
            new Parser("create index idx_age users(age)").parse();   // 少了 ON
            fail("应报语法错误");
        } catch (Error err) { e = err; }
        assertTrue(e.code().startsWith("SY"));
    }
}
