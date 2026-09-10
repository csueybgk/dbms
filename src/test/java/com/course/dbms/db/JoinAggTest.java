package com.course.dbms.db;

import com.course.dbms.engine.Result;
import com.course.dbms.engine.table.Row;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 多表联查 + 聚合端到端测试（经 {@link Database#execute}, 即完整 Parser→Analyzer→PlanBuilder→Executor 链路）。
 */
public class JoinAggTest {

    private Database db;
    private final String dir = "target/tmp-joinagg";

    @Before public void setup() {
        wipe(dir);
        db = new Database(dir);
    }
    @After public void teardown() { db.close(); }

    private static void wipe(String d) {
        File f = new File(d);
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) x.delete();
    }

    private void seed() {
        db.execute("create table users (id int32, name string, age int32, score float64)");
        db.execute("insert into users values (1, 'alice', 23, 95.5)");
        db.execute("insert into users values (2, 'bob', 30, 88.0)");
        db.execute("insert into users values (3, 'carol', 22, 91.5)");
        db.execute("create table orders (oid int32, uid int32, amount float64)");
        db.execute("insert into orders values (10, 1, 100.0)");
        db.execute("insert into orders values (11, 2, 50.0)");
        db.execute("insert into orders values (12, 1, 75.0)");
    }

    @Test public void innerJoin() {
        seed();
        Result r = db.execute(
                "select u.name, o.amount from users u join orders o on u.id = o.uid order by o.amount");
        assertEquals("name", r.columns.get(0));
        assertEquals("amount", r.columns.get(1));
        assertEquals(3, r.rows.size());
        // amount 升序：bob(50), alice(75), alice(100)
        assertEquals("bob", r.rows.get(0).get(0));
        assertEquals(50.0, ((Number) r.rows.get(0).get(1)).doubleValue(), 1e-9);
        assertEquals("alice", r.rows.get(1).get(0));
        assertEquals(75.0, ((Number) r.rows.get(1).get(1)).doubleValue(), 1e-9);
        assertEquals("alice", r.rows.get(2).get(0));
        assertEquals(100.0, ((Number) r.rows.get(2).get(1)).doubleValue(), 1e-9);
    }

    @Test public void leftJoinKeepsUnmatched() {
        seed();
        Result r = db.execute(
                "select u.id, o.oid from users u left join orders o on u.id = o.uid order by u.id");
        assertEquals(4, r.rows.size());   // alice×2 + bob + carol(无订单)
        int nonNull = 0;
        for (Row row : r.rows) if (row.get(1) != null) nonNull++;
        assertEquals(3, nonNull);
        Row carol = r.rows.get(3);
        assertEquals(3L, ((Number) carol.get(0)).longValue());
        assertNull(carol.get(1));          // 左外连接：无匹配 → 右列为 NULL
    }

    @Test public void aggregateCount() {
        seed();
        Result r = db.execute("select count(*) from users");
        assertEquals(1, r.rows.size());
        assertEquals("count", r.columns.get(0));
        assertEquals(3L, ((Number) r.rows.get(0).get(0)).longValue());
    }

    @Test public void aggregateGroupBy() {
        seed();
        db.execute("create table t (g string, v int32)");
        db.execute("insert into t values ('a', 1)");
        db.execute("insert into t values ('a', 2)");
        db.execute("insert into t values ('b', 10)");
        Result r = db.execute(
                "select g, count(*), sum(v), avg(v), min(v), max(v) from t group by g order by count(*) desc");
        assertEquals(2, r.rows.size());
        Row a = r.rows.get(0);
        assertEquals("a", a.get(0));
        assertEquals(2L, ((Number) a.get(1)).longValue());         // count
        assertEquals(3L, ((Number) a.get(2)).longValue());         // sum 整型 → Long
        assertEquals(1.5, ((Number) a.get(3)).doubleValue(), 1e-9);// avg → Double
        assertEquals(1, ((Number) a.get(4)).intValue());           // min
        assertEquals(2, ((Number) a.get(5)).intValue());           // max
        Row b = r.rows.get(1);
        assertEquals("b", b.get(0));
        assertEquals(1L, ((Number) b.get(1)).longValue());
        assertEquals(10L, ((Number) b.get(2)).longValue());
        assertEquals(10.0, ((Number) b.get(3)).doubleValue(), 1e-9);
    }

    @Test public void aggregateOverJoin() {
        seed();
        Result r = db.execute(
                "select u.name, count(*), sum(o.amount) from users u join orders o on u.id = o.uid "
                + "group by u.name order by u.name");
        assertEquals(2, r.rows.size());   // carol 无订单 → 不出现
        assertEquals("alice", r.rows.get(0).get(0));
        assertEquals(2L, ((Number) r.rows.get(0).get(1)).longValue());
        assertEquals(175.0, ((Number) r.rows.get(0).get(2)).doubleValue(), 1e-9);
        assertEquals("bob", r.rows.get(1).get(0));
        assertEquals(1L, ((Number) r.rows.get(1).get(1)).longValue());
        assertEquals(50.0, ((Number) r.rows.get(1).get(2)).doubleValue(), 1e-9);
    }

    @Test public void whereOnJoin() {
        seed();
        Result r = db.execute(
                "select u.name from users u join orders o on u.id = o.uid where o.amount > 80 order by u.name");
        assertEquals(1, r.rows.size());
        assertEquals("alice", r.rows.get(0).get(0));
    }

    @Test public void aggregateOverLeftJoinCountsNullsAsZero() {
        seed();
        Result r = db.execute(
                "select u.id, count(o.oid) from users u left join orders o on u.id = o.uid group by u.id order by u.id");
        assertEquals(3, r.rows.size());
        List<Row> rows = r.rows;
        // alice(3 单? 实际 o.oid 非空计数)：alice=2, bob=1, carol=0
        assertEquals(2L, ((Number) rows.get(0).get(1)).longValue());
        assertEquals(1L, ((Number) rows.get(1).get(1)).longValue());
        assertEquals(0L, ((Number) rows.get(2).get(1)).longValue()); // carol 无匹配 → count 忽略 NULL → 0
    }
}
