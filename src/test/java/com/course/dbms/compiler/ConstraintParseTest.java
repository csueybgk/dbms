package com.course.dbms.compiler;

import com.course.dbms.common.Error;
import com.course.dbms.compiler.ast.CreateStmt;
import com.course.dbms.compiler.ast.InsertStmt;
import com.course.dbms.compiler.ast.Stmt;
import com.course.dbms.engine.table.Constraint;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * CREATE TABLE 约束与 INSERT 列清单的【纯语法】测试（不碰目录、不落盘）。
 *
 * 重点覆盖三处容易回归的地方：
 *   1) 列级约束必须以 NOT / NULL / DEFAULT 开头也能被识别 —— 漏一个
 *      isConstraintStart 分支就会把 `name string not null` 报成 "expected ')'"；
 *   2) CHECK 的表达式要能 Cond → toSql() → 重新解析走一个来回不走样，
 *      尤其是字符串字面量（toString() 不加引号，会静默变成"列 vs 列"）；
 *   3) 期望符号集里的 NULL 必须【追加在最后】，既有断言依赖它的前缀。
 */
public class ConstraintParseTest {

    private static CreateStmt create(String sql) {
        Stmt s = new Parser(sql).parse();
        assertTrue(s instanceof CreateStmt);
        return (CreateStmt) s;
    }

    private static InsertStmt insert(String sql) {
        Stmt s = new Parser(sql).parse();
        assertTrue(s instanceof InsertStmt);
        return (InsertStmt) s;
    }

    /** 取第一条指定种类的约束（找不到直接让用例失败，附带全部约束便于定位）。 */
    private static Constraint con(CreateStmt c, Constraint.Kind kind) {
        for (Constraint x : c.constraints) {
            if (x.kind() == kind) return x;
        }
        fail("missing " + kind + " in " + c.constraints);
        return null;
    }

    private static Error bad(String sql) {
        try {
            new Parser(sql).parse();
            fail("should throw: " + sql);
            return null;
        } catch (Error e) {
            return e;
        }
    }

    // ---- 列级约束 ----

    @Test public void everyColumnConstraintParses() {
        CreateStmt c = create("create table t ("
                + "id int32 primary key, "
                + "name string not null, "
                + "age int32 default 18, "
                + "email string unique, "
                + "score int32 check (score >= 0))");

        assertEquals(5, c.columns.size());
        assertEquals(5, c.constraints.size());

        Constraint pk = con(c, Constraint.Kind.PRIMARY_KEY);
        assertEquals("id", pk.columns().get(0));
        assertTrue(pk.singleColumn());

        assertEquals("name", con(c, Constraint.Kind.NOT_NULL).columns().get(0));
        assertEquals("email", con(c, Constraint.Kind.UNIQUE).columns().get(0));

        Constraint def = con(c, Constraint.Kind.DEFAULT);
        assertEquals("age", def.columns().get(0));
        assertEquals("18", def.detail());                       // 存的是字面量【原文】
        assertEquals(18, def.defaultValue(com.course.dbms.engine.table.FieldType.INT32));

        Constraint ck = con(c, Constraint.Kind.CHECK);
        assertEquals("score", ck.columns().get(0));
        assertEquals("score >= 0", ck.detail());
    }

    /** 显式的 `NULL`（可空）是默认行为，认下来但不生成约束。 */
    @Test public void explicitNullColumnConstraintProducesNoConstraint() {
        CreateStmt c = create("create table t (a int32 null, b string)");
        assertEquals(2, c.columns.size());
        assertTrue(c.constraints.isEmpty());
    }

    /** 没有 DEFAULT 的列不会被自动补上任何约束。 */
    @Test public void noConstraintMeansEmptyList() {
        CreateStmt c = create("create table t (a int32, b string)");
        assertTrue(c.constraints.isEmpty());
        assertFalse(c.toString().contains("not null"));
    }

    @Test public void namedColumnConstraint() {
        CreateStmt c = create("create table t (a int32 constraint ck_a check (a > 0))");
        assertEquals("ck_a", con(c, Constraint.Kind.CHECK).name());
    }

    // ---- 表级约束 ----

    @Test public void compositePrimaryKeyIsTableLevel() {
        CreateStmt c = create("create table p (a int32, b int32, primary key (a, b))");
        Constraint pk = con(c, Constraint.Kind.PRIMARY_KEY);
        assertEquals(2, pk.columns().size());
        assertEquals("a", pk.columns().get(0));
        assertEquals("b", pk.columns().get(1));
        // 引用多列 → 不贴在列格子里，show table 时另起一行
        assertFalse(pk.singleColumn());
    }

    @Test public void compositeUniqueIsTableLevel() {
        CreateStmt c = create("create table p (a int32, b int32, unique (a, b))");
        assertFalse(con(c, Constraint.Kind.UNIQUE).singleColumn());
    }

    @Test public void tableLevelCheckSpansWholeTable() {
        CreateStmt c = create("create table t (a int32, b int32, check (a <= b))");
        Constraint ck = con(c, Constraint.Kind.CHECK);
        assertEquals("a <= b", ck.detail());
        assertTrue(ck.columns().isEmpty());          // 表级 CHECK 不绑定具体列
        assertFalse(ck.singleColumn());
    }

    @Test public void namedTableConstraint() {
        CreateStmt c = create("create table p (a int32, b int32, constraint pk_p primary key (a, b))");
        assertEquals("pk_p", con(c, Constraint.Kind.PRIMARY_KEY).name());
    }

    /** 列级与表级混写：两种等价写法应产出一致的约束（只有列数决定展示位置）。 */
    @Test public void columnLevelAndTableLevelPkAreEquivalent() {
        Constraint inline = con(create("create table t (id int32 primary key)"), Constraint.Kind.PRIMARY_KEY);
        Constraint separate = con(create("create table t (id int32, primary key (id))"), Constraint.Kind.PRIMARY_KEY);
        assertEquals(inline.columns(), separate.columns());
        assertTrue(inline.singleColumn());
        assertTrue(separate.singleColumn());
    }

    // ---- CHECK 表达式的往返 ----

    @Test public void checkExpressionRoundTrips() {
        Constraint ck = con(create("create table t (a int32, b int32, check (a > 0 and b > 0))"),
                Constraint.Kind.CHECK);
        assertEquals("a > 0 and b > 0", ck.detail());

        // cond() 就是从 detail 重新解析出来的 —— 等价于"重启后读回目录"这条路径
        com.course.dbms.compiler.ast.Cond cond = ck.cond();
        assertEquals(com.course.dbms.compiler.ast.Cond.Kind.AND, cond.kind);
        assertEquals(2, cond.children.size());
        assertEquals("a", cond.children.get(0).column);
        assertEquals(">", cond.children.get(0).op);
    }

    /**
     * 字符串字面量必须带引号落进 detail —— 这是 Cond.toSql() 存在的理由：
     * 用 Cond.toString() 会得到 `name <> alice`，重新解析时走"列 vs 列"分支，
     * 静默变成另一条约束。
     */
    @Test public void checkWithStringLiteralKeepsQuotes() {
        Constraint ck = con(create("create table t (name string, check (name <> 'alice'))"),
                Constraint.Kind.CHECK);
        assertEquals("name <> 'alice'", ck.detail());

        com.course.dbms.compiler.ast.Cond cond = ck.cond();
        assertNull("重新解析后必须仍是列 vs 字面量", cond.column2);
        assertEquals("alice", cond.value);
        assertTrue(cond.value instanceof String);
    }

    @Test public void checkWithOrExpressionRoundTrips() {
        Constraint ck = con(create("create table t (a int32, b int32, check (a = 1 or b = 2))"),
                Constraint.Kind.CHECK);
        assertEquals("a = 1 or b = 2", ck.detail());
        assertEquals(com.course.dbms.compiler.ast.Cond.Kind.OR, ck.cond().kind);
    }

    /** 静态解析入口：不套一层假 SELECT，出错时行列号才不会指向不存在的 SQL。 */
    @Test public void parseConditionIsStandalone() {
        com.course.dbms.compiler.ast.Cond c = Parser.parseCondition("a >= 10");
        assertEquals("a", c.column);
        assertEquals(">=", c.op);
        assertEquals(10L, c.value);

        try {
            Parser.parseCondition("a >= 10 and");
            fail("should throw");
        } catch (Error e) {
            assertEquals("SY-0001", e.code());
        }
    }

    // ---- INSERT 列清单与 NULL 字面量 ----

    @Test public void insertColumnListIsParsed() {
        InsertStmt ins = insert("insert into t (id, name) values (1, 'a')");
        assertEquals("t", ins.tableName);
        assertEquals(2, ins.columns.size());
        assertEquals("id", ins.columns.get(0));
        assertEquals("name", ins.columns.get(1));
        assertEquals(1L, ins.values.get(0));
        assertEquals("a", ins.values.get(1));
    }

    @Test public void insertWithoutColumnListHasNullColumns() {
        InsertStmt ins = insert("insert into t values (1, 'a')");
        assertNull(ins.columns);                       // null = 没写列清单，按位置对应全列
        assertEquals(2, ins.values.size());
    }

    @Test public void nullLiteralParsesToNull() {
        InsertStmt ins = insert("insert into t values (1, null)");
        assertEquals(1L, ins.values.get(0));
        assertNull(ins.values.get(1));
    }

    /** `default null` 的原文是 "null"；STRING 列的 `default 'null'` 是四个字符 —— 两者不可混淆。 */
    @Test public void defaultNullAndNullStringAreDistinguishable() {
        com.course.dbms.engine.table.FieldType STRING = com.course.dbms.engine.table.FieldType.STRING;
        com.course.dbms.engine.table.FieldType INT32 = com.course.dbms.engine.table.FieldType.INT32;

        Constraint a = con(create("create table t (a int32 default null)"), Constraint.Kind.DEFAULT);
        assertEquals("null", a.detail());
        assertNull(a.defaultValue(INT32));

        Constraint b = con(create("create table t (a string default 'null')"), Constraint.Kind.DEFAULT);
        assertEquals("'null'", b.detail());
        assertEquals("null", b.defaultValue(STRING));
    }

    @Test public void nullLiteralInWhereParses() {
        // 解析层必须接受它；求值由 Compare/NullSemanticsTest 负责（返回 UNKNOWN，不报错）
        assertTrue(new Parser("select * from t where a = null").parse() instanceof com.course.dbms.compiler.ast.SelectStmt);
    }

    // ---- 错误诊断 ----

    /** NULL 必须追加在期望集合最后：既有 TracerTest 断言了它的前缀。 */
    @Test public void expectedSetListsNullLast() {
        Error e = bad("insert into t values (1, )");
        assertEquals("SY-0001", e.code());
        assertTrue(e.getMessage(), e.getMessage()
                .contains("expected: NUMBER | STR_LIT | TRUE | FALSE | NULL"));
        assertTrue(e.getMessage(), e.getMessage().contains("at line 1, column"));
    }

    @Test public void foreignKeyGivesTargetedError() {
        Error e = bad("create table t (a int32, foreign key (a) references u(b))");
        assertEquals("SY-0001", e.code());
        assertTrue(e.getMessage(), e.getMessage().contains("FOREIGN KEY is not supported"));

        // 列级位置同样要给出定向报错，而不是 "expected IDENTIFIER but got 'references'"
        Error e2 = bad("create table t (a int32 references u(b))");
        assertTrue(e2.getMessage(), e2.getMessage().contains("FOREIGN KEY is not supported"));
    }

    /** 回归：isConstraintStart 漏掉 NOT/NULL/DEFAULT 时，这条会报成 "expected ')' but got 'not'"。 */
    @Test public void notNullWithoutCommaStillParses() {
        CreateStmt c = create("create table t (a int32 not null, b int32)");
        assertEquals(2, c.columns.size());
        assertEquals(Constraint.Kind.NOT_NULL, c.constraints.get(0).kind());
    }

    /** 空列列表仍应报语法错（AnalyzerTest.createEmptyColumnIsSyntaxError 的护栏）。 */
    @Test public void emptyColumnListStillSyntaxError() {
        assertTrue(bad("create table t ()").code().startsWith("SY"));
    }

    /** 约束后面缺右括号 → 仍然是带行列号的语法错。 */
    @Test public void unterminatedConstraintIsSyntaxError() {
        Error e = bad("create table t (a int32 primary key");
        assertEquals("SY-0001", e.code());
        assertTrue(e.getMessage(), e.getMessage().contains("expected ')'"));
    }

    /** CreateStmt.toString() 供 Tracer 第②阶段展示约束（否则新语法在 trace 里完全看不见）。 */
    @Test public void createToStringShowsConstraints() {
        String s = create("create table t (id int32 primary key, name string not null, age int32 default 18)").toString();
        assertTrue(s, s.contains("primary key"));
        assertTrue(s, s.contains("not null"));
        assertTrue(s, s.contains("default 18"));

        String multi = create("create table p (a int32, b int32, primary key (a, b))").toString();
        assertTrue(multi, multi.contains("a, b"));
    }
}
