package com.course.dbms.compiler;

import com.course.dbms.common.Error;
import com.course.dbms.compiler.ast.Stmt;
import com.course.dbms.engine.storage.StorageEngine;
import com.course.dbms.engine.table.Catalog;
import com.course.dbms.engine.table.FieldType;
import com.course.dbms.engine.table.Schema;
import com.course.dbms.storage.DiskManager;
import com.course.dbms.storage.LruCache;
import com.course.dbms.storage.PageManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

public class AnalyzerTest {

    private Catalog cat;
    private PageManager pm;
    private Analyzer analyzer;
    private final String dir = "target/tmp-analyzer";

    @Before public void setup() {
        wipe(dir);
        pm = new PageManager(new DiskManager(dir), new LruCache(32));
        cat = new Catalog(new StorageEngine(pm));
        analyzer = new Analyzer(cat);
        cat.createTable("users", new Schema()
                .add("id", FieldType.INT32)
                .add("name", FieldType.STRING)
                .add("age", FieldType.INT32)
                .add("score", FieldType.FLOAT64));
        cat.createTable("orders", new Schema()
                .add("id", FieldType.INT32)
                .add("uid", FieldType.INT32)
                .add("amount", FieldType.FLOAT64));
    }
    @After public void teardown() { pm.close(); }

    private static void wipe(String d) {
        File f = new File(d);
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) x.delete();
    }

    private void ok(String sql) {
        Stmt s = new Parser(sql).parse();
        analyzer.analyze(s); // 不抛即通过
    }

    private Error bad(String sql) {
        try {
            Stmt s = new Parser(sql).parse();
            analyzer.analyze(s);
            fail("should throw: " + sql);
            return null;
        } catch (Error e) {
            return e;
        }
    }

    @Test public void validSelectColumns() {
        ok("select * from users");
        ok("select id, name, age, score from users");
        ok("select name from users where age > 24 and score >= 90 order by score desc");
    }

    @Test public void insertValueCountMismatch() {
        Error e = bad("insert into users values (1, 'alice', 23)");
        assertTrue(e.code().startsWith("SE"));
    }

    @Test public void insertTypeMismatch() {
        Error e = bad("insert into users values (1, 'alice', 'not-a-number', 90)");
        assertTrue(e.code().startsWith("SE"));
    }

    @Test public void selectUnknownColumn() {
        Error e = bad("select id, nosuch from users");
        assertTrue(e.code().startsWith("SE"));
    }

    @Test public void whereUnknownColumn() {
        Error e = bad("select * from users where nonexistent > 5");
        assertTrue(e.code().startsWith("SE"));
    }

    @Test public void orderUnknownColumn() {
        Error e = bad("select * from users order by nosuch");
        assertTrue(e.code().startsWith("SE"));
    }

    @Test public void missingTable() {
        Error e = bad("select * from ghost");
        assertTrue(e.code().startsWith("TB"));
    }

    @Test public void createDuplicateColumn() {
        Error e = bad("create table t (a int32, a string)");
        assertTrue(e.code().startsWith("SE"));
    }

    @Test public void createEmptyColumnIsSyntaxError() {
        // 空列列表无法通过文法（parser 层就拒绝），属语法错误而非语义错误
        Error e = bad("create table t ()");
        assertTrue(e.code().startsWith("SY"));
    }

    @Test public void showTableMustExist() {
        ok("show tables");
        Error e = bad("show table ghost");
        assertTrue(e.code().startsWith("TB"));
    }

    @Test public void validJoinAndAggregate() {
        ok("select u.id, o.amount from users u join orders o on u.id = o.uid order by o.amount");
        ok("select * from users u join orders o on u.id = o.uid");
        ok("select u.name from users u");
        ok("select u.age from users u order by u.age");
        ok("select count(*) from users");
        ok("select age, avg(score) from users group by age");
        ok("select age, count(*) from users group by age order by count(*)");
    }

    @Test public void joinUnknownColumnInOn() {
        Error e = bad("select u.id from users u join orders o on u.nosuch = o.uid");
        assertTrue(e.code().startsWith("SE"));
    }

    @Test public void ambiguousBareColumnInJoin() {
        // "id" 同时存在于 users 和 orders，裸引用应报歧义
        Error e = bad("select id from users u join orders o on u.id = o.id");
        assertTrue(e.code().startsWith("SE"));
    }

    @Test public void aggregateNonGroupedColumn() {
        // name 不是分组键 → 分组规则要求它出现在 GROUP BY
        Error e = bad("select name, count(*) from users group by age");
        assertTrue(e.code().startsWith("SE"));
    }

    @Test public void orderByUnknownInJoin() {
        Error e = bad("select u.id from users u join orders o on u.id = o.uid order by o.nosuch");
        assertTrue(e.code().startsWith("SE"));
    }

    @Test public void validCreateIndexAndShowIndexes() {
        ok("create index idx_age on users(age)");
        ok("show indexes");
        ok("show indexes on users");
        ok("show index on orders");
    }

    @Test public void createIndexOnUnknownColumn() {
        Error e = bad("create index idx_x on users(nosuch)");
        assertEquals("SE-0004", e.code());
    }

    @Test public void createIndexOnUnknownTable() {
        Error e = bad("create index idx_x on ghost(id)");
        assertEquals("TB-0001", e.code());
    }

    @Test public void createDuplicateIndexName() {
        cat.createIndex("idx_age", "users", "age");            // 语义检查读的是目录里已存在的索引
        Error e = bad("create index idx_age on orders(uid)");
        assertEquals("SE-0007", e.code());
    }

    @Test public void showIndexesOnUnknownTable() {
        Error e = bad("show indexes on ghost");
        assertTrue(e.code().startsWith("TB"));
    }
}
