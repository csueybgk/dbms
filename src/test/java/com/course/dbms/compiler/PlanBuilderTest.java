package com.course.dbms.compiler;

import com.course.dbms.compiler.ast.Stmt;
import com.course.dbms.engine.exec.op.Aggregate;
import com.course.dbms.engine.exec.op.CreateIndex;
import com.course.dbms.engine.exec.op.CreateTable;
import com.course.dbms.engine.exec.op.Filter;
import com.course.dbms.engine.exec.op.IndexScan;
import com.course.dbms.engine.exec.op.Insert;
import com.course.dbms.engine.exec.op.Join;
import com.course.dbms.engine.exec.op.Operator;
import com.course.dbms.engine.exec.op.Project;
import com.course.dbms.engine.exec.op.SeqScan;
import com.course.dbms.engine.exec.op.ShowIndexes;
import com.course.dbms.engine.exec.op.ShowTables;
import com.course.dbms.engine.exec.op.Sort;
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

public class PlanBuilderTest {

    private PageManager pm;
    private Catalog cat;
    private StorageEngine se;
    private PlanBuilder planner;
    private final String dir = "target/tmp-planner";

    @Before public void setup() {
        wipe(dir);
        pm = new PageManager(new DiskManager(dir), new LruCache(32));
        se = new StorageEngine(pm);
        cat = new Catalog(se);
        planner = new PlanBuilder(cat, se);
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

    private Plan build(String sql) {
        Stmt s = new Parser(sql).parse();
        return planner.build(s);
    }

    @Test public void createIsLeafAction() {
        Plan p = build("create table t (a int32, b string)");
        assertTrue(p.root instanceof CreateTable);
        assertNull(p.root.child());
    }

    @Test public void insertIsLeafAction() {
        Plan p = build("insert into users values (1, 'a', 20, 90)");
        assertTrue(p.root instanceof Insert);
        assertNull(p.root.child());
    }

    @Test public void selectStarIsSeqScanPassedThrough() {
        Plan p = build("select * from users");
        assertTrue(p.root instanceof Project);
        Project proj = (Project) p.root;
        assertEquals(4, proj.columns().length);
        assertEquals("id", p.columns.get(0));
        assertEquals("score", p.columns.get(3));
        // * 不带 where，Project 的下层应直接是 SeqScan（Project 后就是叶）
        assertTrue(proj.child() instanceof SeqScan);
        // 但可能包了 Sort 之类的；直接断言最底层是 SeqScan
        Operator op = proj.child();
        while (!(op instanceof SeqScan)) op = op.child();
        assertNull(op.child());
    }

    @Test public void wherePushesFilterUnderProject() {
        Plan p = build("select id from users where age > 24");
        assertTrue(p.root instanceof Project);
        Project proj = (Project) p.root;
        assertEquals(1, proj.columns().length);
        assertTrue(proj.child() instanceof Filter);
        assertTrue(proj.child().child() instanceof SeqScan);
    }

    @Test public void orderBySortsUnderProject() {
        Plan p = build("select id, name from users where age > 20 order by score desc");
        assertTrue(p.root instanceof Project);
        Operator cur = p.root.child();
        assertTrue(cur instanceof com.course.dbms.engine.exec.op.Sort);
        cur = cur.child();
        assertTrue(cur instanceof Filter);
        cur = cur.child();
        assertTrue(cur instanceof SeqScan);
    }

    @Test public void showTablesIsLeaf() {
        Plan p = build("show tables");
        assertTrue(p.root instanceof ShowTables);
        assertEquals("table", p.columns.get(0));
    }

    @Test public void planColumnsMatchSelectList() {
        Plan p = build("select score, id from users");
        assertEquals("score", p.columns.get(0));
        assertEquals("id", p.columns.get(1));
    }

    @Test public void joinBuildsProjectOverJoin() {
        Plan p = build("select u.name, o.amount from users u join orders o on u.id = o.uid");
        assertTrue(p.root instanceof Project);
        Project proj = (Project) p.root;
        assertEquals(2, proj.columns().length);
        Operator op = proj.child();
        assertTrue(op instanceof Join);
        Join j = (Join) op;
        assertNull(j.child().child());          // 左 SeqScan 叶子
        assertNull(j.rightChild().child());     // 右 SeqScan 叶子
        assertEquals(2, p.columns.size());
        assertEquals("name", p.columns.get(0));
        assertEquals("amount", p.columns.get(1));
    }

    @Test public void aggregateIsRoot() {
        Plan p = build("select count(*) from users");
        assertTrue(p.root instanceof Aggregate);
        assertNull(p.root.child().child());     // Aggregate 下直接是 SeqScan 叶子
        assertEquals(1, p.columns.size());
        assertEquals("count", p.columns.get(0));
    }

    @Test public void sortOverAggregate() {
        Plan p = build("select age, count(*) from users group by age order by count(*) desc");
        assertTrue(p.root instanceof Sort);
        assertTrue(p.root.child() instanceof Aggregate);
        assertEquals("age", p.columns.get(0));
        assertEquals("count", p.columns.get(1));
    }

    @Test public void createIndexIsLeafAction() {
        Plan p = build("create index idx_age on users(age)");
        assertTrue(p.root instanceof CreateIndex);
        assertNull(p.root.child());
    }

    @Test public void showIndexesIsLeaf() {
        Plan p = build("show indexes");
        assertTrue(p.root instanceof ShowIndexes);
        assertEquals("index", p.columns.get(0));
        assertEquals("column", p.columns.get(2));
    }

    /** 建了索引：底层扫描从 SeqScan 换成 IndexScan，Filter 照旧叠在上面。 */
    @Test public void indexReplacesSeqScanWhenAvailable() {
        cat.createIndex("idx_age", "users", "age");
        Plan p = build("select id from users where age = 30");
        assertTrue(p.root instanceof Project);
        Operator op = p.root.child();
        assertTrue("Filter 仍在索引扫描之上", op instanceof Filter);
        assertTrue("等值条件应走索引", op.child() instanceof IndexScan);

        IndexScan is = (IndexScan) op.child();
        assertNull("IndexScan 是叶子", is.child());
        assertEquals("age 是第 2 列（下标 2）", 2, is.keyColumn());
        assertEquals("= 换算成闭区间 [30,30]", Integer.valueOf(30), is.lower());
        assertEquals(Integer.valueOf(30), is.upper());
        assertTrue(is.lowerInclusive());
        assertTrue(is.upperInclusive());
    }

    @Test public void indexRangeBoundsFollowOperator() {
        cat.createIndex("idx_age", "users", "age");
        IndexScan gt = (IndexScan) build("select id from users where age > 24").root.child().child();
        assertEquals(Integer.valueOf(24), gt.lower());
        assertFalse("> 不含端点", gt.lowerInclusive());
        assertNull("无上界", gt.upper());

        IndexScan le = (IndexScan) build("select id from users where age <= 24").root.child().child();
        assertNull(le.lower());
        assertEquals(Integer.valueOf(24), le.upper());
        assertTrue("<=", le.upperInclusive());
    }

    /** 没建索引 → 与以前逐字节一样，仍是 SeqScan。 */
    @Test public void seqScanWhenNoIndex() {
        Plan p = build("select id from users where age = 30");
        assertTrue(p.root.child() instanceof Filter);
        assertTrue(p.root.child().child() instanceof SeqScan);
    }

    /** AND 的合取项里能找到索引就用（哪怕另一个合取项用不上）。 */
    @Test public void indexUsedForConjunctInsideAnd() {
        cat.createIndex("idx_age", "users", "age");
        Plan p = build("select id from users where name = 'alice' and age > 24");
        assertTrue(p.root.child().child() instanceof IndexScan);
    }

    /** OR 的任一支不成立就不能缩小范围 → 整体退回全表扫描。 */
    @Test public void orConditionFallsBackToSeqScan() {
        cat.createIndex("idx_age", "users", "age");
        Plan p = build("select id from users where age = 30 or score > 90");
        assertTrue(p.root.child() instanceof Filter);
        assertTrue("OR 不走索引", p.root.child().child() instanceof SeqScan);
    }

    /** <> 用不上 B+ 树的有序性 → 全表扫描。 */
    @Test public void notEqualFallsBackToSeqScan() {
        cat.createIndex("idx_age", "users", "age");
        Plan p = build("select id from users where age <> 30");
        assertTrue(p.root.child().child() instanceof SeqScan);
    }

    /** 索引列上没有条件（条件在别的列上）→ 全表扫描。 */
    @Test public void indexIgnoredWhenPredicateIsOnOtherColumn() {
        cat.createIndex("idx_age", "users", "age");
        Plan p = build("select id from users where score > 90");
        assertTrue(p.root.child().child() instanceof SeqScan);
    }
}
