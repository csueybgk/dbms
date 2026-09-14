package com.course.dbms.db;

import com.course.dbms.engine.Result;
import com.course.dbms.engine.table.Row;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/**
 * NULL 三值逻辑回归测试。
 *
 * 上半部分（前 8 个用例）的 NULL 全部由 LEFT JOIN 的未匹配行合成（见 Join.nulls）——
 * 它们是 {@code Compare.apply} 与 {@code CondEval.eval} 的历史护栏。下半部分改从写入侧
 * 直接造 NULL：SQL 现在有 NULL 字面量，省略列也会补 NULL，存储层用 {@code len = -1}
 * 标记空值。两条路径产生的 NULL 必须遵守同一套规则。
 *
 * 这组用例锁住 {@link com.course.dbms.engine.exec.op.Compare} 的两条相反规则：
 *   谓词（apply）—— 与 NULL 的任何比较都是 UNKNOWN，WHERE 不保留该行；
 *   排序（compare）—— NULL 位置必须确定：升序垫底、降序在最前。
 *
 * 历史缺陷：apply 原先共用 compare，NULL 经 String.valueOf 变成字符串 "null"，
 * 而 'n'(110) 大于所有数字字符，于是 NULL 被当成"比任何数都大" ——
 * `where o.amount > 50` 会错误地放进 NULL 行。本文件即为该缺陷的回归防线。
 */
public class NullSemanticsTest {

    private Database db;
    private final String dir = "target/tmp-nullsem";

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

    /** users: alice/bob/carol；orders 只覆盖 alice(100.0) 与 carol(40.0) → bob 无订单，右列为 NULL。 */
    private void seed() {
        db.execute("create table u (id int32, name string)");
        db.execute("insert into u values (1, 'alice')");
        db.execute("insert into u values (2, 'bob')");
        db.execute("insert into u values (3, 'carol')");
        db.execute("create table o (oid int32, uid int32, amount float64)");
        db.execute("insert into o values (10, 1, 100.0)");
        db.execute("insert into o values (11, 3, 40.0)");
    }

    private static final String BASE = "select u.id, o.amount from u left join o on u.id = o.uid ";

    /** 只取结果集的第 0 列（id），便于断言"哪几行被保留"。 */
    private static long[] ids(Result r) {
        long[] out = new long[r.rows.size()];
        for (int i = 0; i < r.rows.size(); i++) {
            out[i] = ((Number) r.rows.get(i).get(0)).longValue();
        }
        return out;
    }

    @Test public void leftJoinReallyProducesNull() {
        seed();
        Result r = db.execute(BASE + "order by u.id");
        assertEquals(3, r.rows.size());              // alice / bob / carol 都在
        assertNull(r.rows.get(1).get(1));            // bob 的 amount 为 NULL —— 其余用例的前提
        assertEquals(100.0, ((Number) r.rows.get(0).get(1)).doubleValue(), 1e-9);
        assertEquals(40.0, ((Number) r.rows.get(2).get(1)).doubleValue(), 1e-9);
    }

    @Test public void nullFailsEveryComparison() {
        seed();
        // 数据：alice=100.0(id1)、carol=40.0(id3)、bob=NULL(id2)。六个运算符都不应保留 NULL 行。
        assertArrayEquals(new long[] {1},    ids(db.execute(BASE + "where o.amount > 50 order by u.id")));
        assertArrayEquals(new long[] {1},    ids(db.execute(BASE + "where o.amount >= 50 order by u.id")));
        assertArrayEquals(new long[] {3},    ids(db.execute(BASE + "where o.amount < 50 order by u.id")));
        assertArrayEquals(new long[] {3},    ids(db.execute(BASE + "where o.amount <= 50 order by u.id")));
        assertArrayEquals(new long[] {3},    ids(db.execute(BASE + "where o.amount = 40 order by u.id")));
        assertArrayEquals(new long[] {1},    ids(db.execute(BASE + "where o.amount <> 40 order by u.id")));
        // 关键：>= 50 与 < 50 必须【恰好划分】非 NULL 行 {1,3}，NULL 两侧都不出现、不留缝隙。
        assertArrayEquals(new long[] {1, 3}, ids(db.execute(BASE + "where o.amount >= 40 order by u.id")));
    }

    /** 经典陷阱：x = x 在 x 为 NULL 时是 UNKNOWN，不能匹配 —— 自比也不该把 NULL 捞出来。 */
    @Test public void nullIsNotEqualToItself() {
        seed();
        assertArrayEquals(new long[] {1, 3}, ids(db.execute(BASE + "where o.amount = o.amount order by u.id")));
        assertArrayEquals(new long[] {},    ids(db.execute(BASE + "where o.amount <> o.amount order by u.id")));
    }

    /** 列 vs 列走 CondEval 的另一分支（c.column2 != null），NULL 规则须一致。 */
    @Test public void nullInColumnToColumnComparison() {
        seed();
        // o.amount 与 o.uid 同为非 NULL 时按数值比较（100.0 vs 1、40.0 vs 3 均不等），NULL 行一律排除
        assertArrayEquals(new long[] {},    ids(db.execute(BASE + "where o.amount = o.uid order by u.id")));
        assertArrayEquals(new long[] {1, 3}, ids(db.execute(BASE + "where o.amount <> o.uid order by u.id")));
    }

    /** 排序：NULL 位置必须确定 —— 升序垫底（NULLS LAST）。 */
    @Test public void nullSortsLastAscending() {
        seed();
        Result r = db.execute(BASE + "order by o.amount");
        assertEquals(3, r.rows.size());
        assertEquals(40.0, ((Number) r.rows.get(0).get(1)).doubleValue(), 1e-9);   // carol
        assertEquals(100.0, ((Number) r.rows.get(1).get(1)).doubleValue(), 1e-9);  // alice
        assertNull(r.rows.get(2).get(1));                                          // bob NULL 垫底
    }

    /** 排序：降序时 Sort 取反比较结果，NULL 自然落到最前（NULLS FIRST）。 */
    @Test public void nullSortsFirstDescending() {
        seed();
        Result r = db.execute(BASE + "order by o.amount desc");
        assertEquals(3, r.rows.size());
        assertNull(r.rows.get(0).get(1));                                          // bob NULL 在最前
        assertEquals(100.0, ((Number) r.rows.get(1).get(1)).doubleValue(), 1e-9);  // alice
        assertEquals(40.0, ((Number) r.rows.get(2).get(1)).doubleValue(), 1e-9);   // carol
    }

    /** 聚合侧：NULL 仍被忽略（COUNT 计 0、SUM/AVG 跳过），不能被本次改动破坏。 */
    @Test public void aggregateStillIgnoresNull() {
        seed();
        Result r = db.execute(
                "select u.id, count(o.amount), sum(o.amount) from u left join o on u.id = o.uid "
                + "group by u.id order by u.id");
        assertEquals(3, r.rows.size());
        assertEquals(1L, ((Number) r.rows.get(0).get(1)).longValue());             // alice 1 单
        assertEquals(0L, ((Number) r.rows.get(1).get(1)).longValue());             // bob 无单 → 计 0
        assertNull(r.rows.get(1).get(2));                                          // bob sum 无值 → NULL
        assertEquals(1L, ((Number) r.rows.get(2).get(1)).longValue());             // carol 1 单
    }

    /** LEFT JOIN 后再 ORDER BY 左表列：NULL 不影响左表列的排序。 */
    @Test public void orderByNonNullColumnUnaffected() {
        seed();
        Result r = db.execute(BASE + "order by u.id desc");
        assertArrayEquals(new long[] {3, 2, 1}, ids(r));
    }

    // ---- 真 NULL：NULL 字面量 + 省略列，直接从 SQL 写进表 ----
    //
    // 上面 8 个用例的 NULL 都来自 LEFT JOIN 的合成列。这组改从写入侧造 NULL：
    // 存储层用 len = -1 标记空值，读回来必须还是 NULL，且六算子的三值逻辑不变。

    /** id=1 的 v 有值(10)，id=2 省略列补 NULL，id=3 显式 NULL。 */
    private void seedNulls() {
        db.execute("create table t (id int32, v int32)");
        db.execute("insert into t values (1, 10)");
        db.execute("insert into t (id) values (2)");
        db.execute("insert into t values (3, null)");
    }

    /** 真 NULL 走一遍完整往返：写入 → 落盘 → 扫描 → 投影。 */
    @Test public void realNullsSurviveStorageRoundTrip() {
        seedNulls();
        Result r = db.execute("select id, v from t order by id");
        assertEquals(3, r.rows.size());
        assertEquals(10, ((Number) r.rows.get(0).get(1)).intValue());
        assertNull(r.rows.get(1).get(1));       // 省略列 → NULL
        assertNull(r.rows.get(2).get(1));       // 显式 null → NULL
    }

    /** `where v = null` 是 UNKNOWN → 0 行；不能抛异常（NullPointer 会掐断连接）。 */
    @Test public void nullLiteralInWhereMatchesNothing() {
        seedNulls();
        assertArrayEquals(new long[] {}, ids(db.execute("select id from t where v = null order by id")));
        assertArrayEquals(new long[] {}, ids(db.execute("select id from t where v <> null order by id")));
        // 关键：上一条语句没有把库搞坏，后续查询照常
        assertArrayEquals(new long[] {1}, ids(db.execute("select id from t where v = 10 order by id")));
    }

    /** 真 NULL 同样被所有比较谓词排除（与 LEFT JOIN 合成的 NULL 一致）。 */
    @Test public void realNullFailsEveryComparison() {
        seedNulls();
        assertArrayEquals(new long[] {1}, ids(db.execute("select id from t where v > 5 order by id")));
        assertArrayEquals(new long[] {},  ids(db.execute("select id from t where v < 5 order by id")));
        assertArrayEquals(new long[] {},  ids(db.execute("select id from t where v <> 10 order by id")));
        // v = v 自比也捞不出 NULL
        assertArrayEquals(new long[] {1}, ids(db.execute("select id from t where v = v order by id")));
    }

    @Test public void realNullSortsLastAscending() {
        seedNulls();
        Result r = db.execute("select v from t order by v");
        assertEquals(3, r.rows.size());
        assertEquals(10, ((Number) r.rows.get(0).get(0)).intValue());
        assertNull(r.rows.get(1).get(0));
        assertNull(r.rows.get(2).get(0));
    }

    /** UPDATE 写 NULL：真 NULL 也能由 DML 产生，后续谓词必须一视同仁。 */
    @Test public void updateCanWriteRealNull() {
        seedNulls();
        db.execute("update t set v = null where id = 1");
        assertArrayEquals(new long[] {}, ids(db.execute("select id from t where v > 0 order by id")));

        Result r = db.execute("select v from t order by id");
        for (Row row : r.rows) assertNull(row.get(0));
    }

    /** 聚合照旧忽略 NULL：2 行 NULL 不计入 COUNT(v)，SUM 只看有值的那行。 */
    @Test public void aggregateIgnoresRealNull() {
        seedNulls();
        Result r = db.execute("select count(v), sum(v), count(*) from t");
        assertEquals(1, r.rows.size());
        assertEquals(1L, ((Number) r.rows.get(0).get(0)).longValue());   // 只有 id=1 有值
        assertEquals(10L, ((Number) r.rows.get(0).get(1)).longValue());
        assertEquals(3L, ((Number) r.rows.get(0).get(2)).longValue());   // COUNT(*) 数行
    }

    /** 带索引的列上有 NULL 行时，索引扫描不能把 NULL 行捞出来，也不能漏掉非 NULL 行。 */
    @Test public void indexScanWithNullsInTable() {
        seedNulls();
        db.execute("create index idx_v on t(v)");
        assertArrayEquals(new long[] {1}, ids(db.execute("select id from t where v = 10 order by id")));
        assertArrayEquals(new long[] {},  ids(db.execute("select id from t where v = 0 order by id")));
        // 范围扫描（索引的另一条出口）同样不能放进 NULL 行
        assertArrayEquals(new long[] {1}, ids(db.execute("select id from t where v >= 10 order by id")));
    }

    /** NULL 标记真的落进了页文件：重开数据库后仍是 NULL，不会被解成 0 或空串。 */
    @Test public void realNullSurvivesReopen() {
        seedNulls();
        db.close();
        db = new Database(dir);

        Result r = db.execute("select v from t order by id");
        assertEquals(3, r.rows.size());
        assertEquals(10, ((Number) r.rows.get(0).get(0)).intValue());
        assertNull(r.rows.get(1).get(0));
        assertNull(r.rows.get(2).get(0));
    }
}
