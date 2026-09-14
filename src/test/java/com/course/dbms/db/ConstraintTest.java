package com.course.dbms.db;

import com.course.dbms.common.Error;
import com.course.dbms.engine.Result;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/**
 * 完整性约束的端到端测试：建表 → 生效 → 落盘 → 重启后仍生效 → 事务回滚可撤销。
 *
 * 与 {@code AnalyzerTest} 的分工：那边测"静态预检报的错"（列名/列数/类型），
 * 这边测"依赖实际数据才能判定的"约束 —— NOT NULL / UNIQUE / PRIMARY KEY / CHECK
 * 全部由写入点的 {@link com.course.dbms.engine.table.ConstraintChecker} 把关。
 *
 * 每张表都用新名字，避免用例之间互相污染（目录是同一个 dir）。
 */
public class ConstraintTest {

    private Database db;
    private Session s;               // 走 Session（与真实服务端一致），事务控制语句才有人处理
    private final String dir = "target/tmp-constraint";

    @Before public void setup() {
        wipe(dir);
        db = new Database(dir);
        s = new Session(db);
    }
    @After public void teardown() { db.close(); }

    private static void wipe(String d) {
        File f = new File(d);
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) x.delete();
    }

    /** 断言 SQL 抛出指定错误码。 */
    private Error fails(String code, String sql) {
        try {
            s.execute(sql);
            fail("should throw " + code + ": " + sql);
            return null;
        } catch (Error e) {
            assertEquals(sql, code, e.code());
            return e;
        }
    }

    private long count(String sql) {
        return s.execute(sql).rows.size();
    }

    // ---- PRIMARY KEY ----

    @Test public void primaryKeyRejectsDuplicateAndNull() {
        s.execute("create table pk (id int32 primary key, name string)");
        s.execute("insert into pk values (1, 'a')");

        fails("SE-0011", "insert into pk values (1, 'b')");     // 重复
        fails("SE-0010", "insert into pk values (null, 'c')");  // PRIMARY KEY 隐含 NOT NULL
        assertEquals(1, count("select * from pk"));
    }

    /** 复合主键：单列可重复，整组不能重复。 */
    @Test public void compositePrimaryKeyComparesWholeTuple() {
        s.execute("create table cp (a int32, b int32, primary key (a, b))");
        s.execute("insert into cp values (1, 1)");
        s.execute("insert into cp values (1, 2)");             // a 相同不算冲突
        s.execute("insert into cp values (2, 1)");
        fails("SE-0011", "insert into cp values (1, 1)");
        fails("SE-0011", "insert into cp values (1, 2)");
        assertEquals(3, count("select * from cp"));
    }

    // ---- NOT NULL ----

    @Test public void notNullRejectsMissingAndExplicitNull() {
        s.execute("create table nn (id int32, name string not null)");
        s.execute("insert into nn values (1, 'a')");

        // 列清单省略 name → 无 DEFAULT，补 NULL → 拒绝
        fails("SE-0010", "insert into nn (id) values (2)");
        // 显式写 null → 拒绝
        fails("SE-0010", "insert into nn values (3, null)");
        assertEquals(1, count("select * from nn"));
    }

    /** 未提供且非 NOT NULL 的列补 NULL，是合法写入 —— 证明 NULL 确实进得了库。 */
    @Test public void omittedNullableColumnBecomesNull() {
        s.execute("create table nn2 (id int32, name string)");
        s.execute("insert into nn2 (id) values (1)");
        Result r = s.execute("select * from nn2");
        assertEquals(1, r.rows.size());
        assertNull(r.rows.get(0).get(1));
    }

    // ---- DEFAULT ----

    @Test public void defaultFillsOmittedColumns() {
        s.execute("create table dd (id int32, name string default 'anon', age int32 default 18)");
        s.execute("insert into dd (id) values (1)");

        Result r = s.execute("select * from dd");
        assertEquals(1, r.rows.size());
        assertEquals(1, ((Number) r.rows.get(0).get(0)).intValue());
        assertEquals("anon", r.rows.get(0).get(1));
        assertEquals(18, ((Number) r.rows.get(0).get(2)).intValue());
    }

    /** 显式给了值就覆盖 DEFAULT（包括显式 NULL）。 */
    @Test public void explicitValueOverridesDefault() {
        s.execute("create table dd2 (id int32, age int32 default 18)");
        s.execute("insert into dd2 values (1, 30)");
        s.execute("insert into dd2 values (2, null)");         // 显式 null ≠ 用默认值

        Result r = s.execute("select * from dd2 order by id");
        assertEquals(30, ((Number) r.rows.get(0).get(1)).intValue());
        assertNull(r.rows.get(1).get(1));
    }

    @Test public void defaultNullOnNotNullColumnIsRejected() {
        fails("SE-0005", "create table bad1 (a int32 not null default null)");
    }

    /** DEFAULT 1.5 给 int32 必须报错：cast 会把它静默截断成 1，所以走 parseLiteral。 */
    @Test public void defaultLiteralTypeMismatchIsRejected() {
        fails("SE-0005", "create table bad2 (a int32 default 1.5)");
        fails("SE-0005", "create table bad3 (a int32 default 'abc')");
        fails("SE-0005", "create table bad5 (a bool default 3)");
        // STRING 例外：parseLiteral 对未加引号的文本是宽容的（当字符串用），18 是合法默认值
        s.execute("create table okstr (a string default 18)");
    }

    // ---- UNIQUE ----

    @Test public void uniqueRejectsDuplicateButAllowsMultipleNulls() {
        s.execute("create table uq (id int32, email string unique)");
        s.execute("insert into uq values (1, 'a@x')");
        s.execute("insert into uq values (2, 'b@x')");
        fails("SE-0011", "insert into uq values (3, 'a@x')");

        // SQL 语义：NULL 互不相等，下划线列可以有多行 NULL
        s.execute("insert into uq values (4, null)");
        s.execute("insert into uq values (5, null)");
        assertEquals(4, count("select * from uq"));
    }

    @Test public void uniqueOnCompositeTuple() {
        s.execute("create table uq2 (a int32, b int32, unique (a, b))");
        s.execute("insert into uq2 values (1, 1)");
        s.execute("insert into uq2 values (1, 2)");
        fails("SE-0011", "insert into uq2 values (1, 1)");
    }

    /** 同一条语句里插两行/改两行到同一个键：自比较看不到，靠 checker 的 accepted 集合挡住。 */
    @Test public void duplicatesWithinOneStatementAreRejected() {
        s.execute("create table uq3 (id int32 unique)");
        s.execute("insert into uq3 values (1)");
        s.execute("insert into uq3 values (2)");
        // 两行都改成 5 → 第二行会看见第一行的【旧】值，必须靠"本语句已接受键"拦下
        fails("SE-0011", "update uq3 set id = 5");
        assertEquals(2, count("select * from uq3"));
    }

    // ---- CHECK ----

    /** 注意：本库词法器不认负号（`-1` 会报 LX-0001），故用上界而不是下界来表达"越界"。 */
    @Test public void checkRejectsOnlyFalse() {
        s.execute("create table ck (id int32, score int32 check (score <= 100))");
        s.execute("insert into ck values (1, 10)");
        fails("SE-0012", "insert into ck values (2, 200)");

        // 求值为 UNKNOWN（score 为 NULL）按 SQL 语义【通过】，不能误判为违反
        s.execute("insert into ck (id) values (3)");
        s.execute("insert into ck values (4, null)");
        assertEquals(3, count("select * from ck"));
    }

    @Test public void tableLevelCheckSpansColumns() {
        s.execute("create table ck2 (a int32, b int32, check (a <= b))");
        s.execute("insert into ck2 values (1, 2)");
        s.execute("insert into ck2 values (3, 3)");
        fails("SE-0012", "insert into ck2 values (5, 1)");
        assertEquals(2, count("select * from ck2"));
    }

    /** CHECK 引用的列必须存在（SE-0004），且字符串字面量要能正确往返。 */
    @Test public void checkOnUnknownColumnIsRejected() {
        fails("SE-0004", "create table ck3 (a int32, check (nosuch > 0))");
    }

    /**
     * CHECK 里的字符串字面量：约束文本经 toSql() 落进 sys_constraints 再被重新解析，
     * 引号必须还在 —— 否则会退化成"列 vs 列"，'bad' 永远也匹配不上。
     */
    @Test public void checkWithStringLiteralIsEnforced() {
        s.execute("create table ck4 (name string check (name <> 'bad'))");
        s.execute("insert into ck4 values ('ok')");
        fails("SE-0012", "insert into ck4 values ('bad')");
    }

    // ---- UPDATE / 自比较 ----

    @Test public void updateSkipsTheRowBeingReplaced() {
        s.execute("create table up (id int32 primary key, v int32)");
        s.execute("insert into up values (1, 10)");
        s.execute("insert into up values (2, 20)");

        s.execute("update up set v = 99 where id = 1");         // 碰到自己 → 不算冲突
        fails("SE-0011", "update up set id = 2 where id = 1");   // 碰到别人 → 冲突
        assertEquals(2, count("select * from up"));              // 且失败的语句没留下部分修改
        assertEquals(99, ((Number) s.execute("select v from up where id = 1").rows.get(0).get(0)).intValue());
    }

    @Test public void updateEnforcesNotNull() {
        s.execute("create table up2 (id int32, name string not null)");
        s.execute("insert into up2 values (1, 'a')");
        fails("SE-0010", "update up2 set name = null");
    }

    // ---- DDL 静态检查 ----

    @Test public void duplicateConstraintNameIsRejected() {
        fails("SE-0015", "create table bad6 (a int32 constraint c1 unique, b int32 constraint c1 unique)");
    }

    @Test public void twoPrimaryKeysAreRejected() {
        fails("SE-0016", "create table bad7 (a int32 primary key, b int32 primary key)");
        fails("SE-0016", "create table bad8 (a int32 primary key, b int32, primary key (b))");
    }

    @Test public void constraintOnUnknownColumnIsRejected() {
        fails("SE-0004", "create table bad9 (a int32, unique (nosuch))");
        fails("SE-0004", "create table bad10 (a int32, primary key (a, nosuch))");
    }

    // ---- 持久化 ----

    @Test public void constraintsSurviveReopen() {
        s.execute("create table pers (id int32 primary key, name string not null, age int32 default 18)");
        s.execute("insert into pers (id, name) values (1, 'a')");
        db.close();

        db = new Database(dir);
        s = new Session(db);
        // 约束元数据要从 sys_constraints 读回来并重新生效
        fails("SE-0011", "insert into pers values (1, 'b', 1)");
        fails("SE-0010", "insert into pers (id, name) values (2, null)");

        Result r = s.execute("select * from pers");
        assertEquals(1, r.rows.size());
        assertEquals(18, ((Number) r.rows.get(0).get(2)).intValue());   // DEFAULT 也还在
    }

    /** CHECK 落盘的是表达式【文本】，重启后要能重新解析 —— 顺序不同也要经得起。 */
    @Test public void checkExpressionSurvivesReopen() {
        s.execute("create table pers2 (a int32, b int32, check (a > 0 and b > 0))");
        s.execute("insert into pers2 values (1, 1)");
        db.close();

        db = new Database(dir);
        s = new Session(db);
        s.execute("insert into pers2 values (2, 2)");
        fails("SE-0012", "insert into pers2 values (3, 0)");   // b > 0 不成立
    }

    // ---- 事务 ----

    @Test public void rollbackUndoesCreateWithConstraints() {
        s.execute("begin");
        s.execute("create table tx1 (a int32 primary key)");
        s.execute("insert into tx1 values (1)");
        fails("SE-0011", "insert into tx1 values (1)");   // 事务内即时生效
        s.execute("rollback");

        Result r = s.execute("show tables");
        for (com.course.dbms.engine.table.Row row : r.rows) {
            assertNotEquals("tx1", row.get(0));           // 目录已 reload，表与约束一起消失
        }
    }

    @Test public void commitKeepsConstraintEnforcement() {
        s.execute("begin");
        s.execute("create table tx2 (a int32 primary key)");
        s.execute("insert into tx2 values (1)");
        s.execute("commit");

        fails("SE-0011", "insert into tx2 values (1)");
        assertEquals(1, count("select * from tx2"));
    }

    // ---- SHOW TABLE ----

    @Test public void showTableListsConstraints() {
        s.execute("create table sh (id int32 primary key, name string not null, "
                + "age int32 default 18, email string unique, score int32 check (score >= 0))");

        Result r = s.execute("show table sh");
        assertEquals(3, r.columns.size());
        assertEquals("column", r.columns.get(0));
        assertEquals("type", r.columns.get(1));
        assertEquals("constraint", r.columns.get(2));
        assertEquals(5, r.rows.size());                   // 5 列，无多列约束 → 不多出行

        assertEquals("id", r.rows.get(0).get(0));
        assertTrue(String.valueOf(r.rows.get(0).get(2)), String.valueOf(r.rows.get(0).get(2)).contains("primary key"));
        assertTrue(String.valueOf(r.rows.get(1).get(2)), String.valueOf(r.rows.get(1).get(2)).contains("not null"));
        assertTrue(String.valueOf(r.rows.get(2).get(2)), String.valueOf(r.rows.get(2).get(2)).contains("default 18"));
        assertTrue(String.valueOf(r.rows.get(3).get(2)), String.valueOf(r.rows.get(3).get(2)).contains("unique"));
        assertTrue(String.valueOf(r.rows.get(4).get(2)), String.valueOf(r.rows.get(4).get(2)).contains("check (score >= 0)"));
    }

    /** 无约束的表不能多出行（DatabaseTest.createInsertSelectShow 断言了"恰好 4 行"）。 */
    @Test public void showTableWithoutConstraintsHasNoExtraRows() {
        s.execute("create table sh2 (a int32, b string)");
        Result r = s.execute("show table sh2");
        assertEquals(2, r.rows.size());
        assertEquals("", r.rows.get(0).get(2));
    }

    /** 多列约束另起一行，展示规则按"引用了几列"而不是"写在哪"。 */
    @Test public void showTablePutsMultiColumnConstraintOnItsOwnRow() {
        s.execute("create table sh3 (a int32, b int32, primary key (a, b))");
        Result r = s.execute("show table sh3");
        assertEquals(3, r.rows.size());                   // 2 列 + 1 行多列约束
        assertEquals("(table)", r.rows.get(2).get(0));
        assertTrue(String.valueOf(r.rows.get(2).get(2)).contains("primary key"));

        // 等价写法 `a int32 primary key` 则贴在列上，不多出行
        s.execute("create table sh4 (a int32 primary key, b int32)");
        assertEquals(2, s.execute("show table sh4").rows.size());
    }
}
