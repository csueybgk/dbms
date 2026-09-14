package com.course.dbms.compiler;

import com.course.dbms.common.Error;
import com.course.dbms.compiler.token.Token;
import com.course.dbms.compiler.token.TokenType;
import com.course.dbms.db.Database;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 课程验收补充测试：Token 四元式（行号列号）、错误定位、期望符号集、编译四阶段 trace。
 */
public class TracerTest {

    private Database db;

    @Before
    public void setup() throws Exception {
        db = new Database(Files.createTempDirectory("dbms-trace").toString());
    }

    @After
    public void teardown() {
        db.close();
    }

    @Test
    public void traceShowsAllFourStages() {
        db.execute("create table t (id int32, name string)");
        db.execute("insert into t values (1, 'alice')");
        String out = Tracer.trace(db, "select id, name from t where id = 1");
        assertTrue(out.contains("--① 词法分析"));
        assertTrue(out.contains("--② 语法分析"));
        assertTrue(out.contains("--③ 语义分析: OK"));
        assertTrue(out.contains("--④ 逻辑执行计划"));
        // 算子树 S-表达式：Project(Filter(SeqScan(t)))
        assertTrue(out.contains("Project"));
        assertTrue(out.contains("Filter"));
        assertTrue(out.contains("SeqScan(t)"));
    }

    @Test
    public void tokenCarriesLineAndColumn() {
        //            0         1
        //            012345678 901234
        List<Token> ts = db.tokenize("select name\nfrom t");
        Token from = ts.get(2);            // FROM 在第二行第一列
        assertEquals(2, from.line);
        assertEquals(1, from.column);
        Token name = ts.get(1);
        assertEquals(1, name.line);
        assertEquals(8, name.column);
    }

    @Test
    public void lexErrorReportsLineColumn() {
        try {
            db.tokenize("select name from @");   // '@' 位于第 18 列
            fail("expected lex error");
        } catch (Error e) {
            assertEquals("LX-0001", e.code());
            assertTrue(e.getMessage(), e.getMessage().contains("at line 1, column 18"));
        }
    }

    @Test
    public void unterminatedStringReportsLineColumn() {
        try {
            db.tokenize("insert into t values ('abc");
            fail("expected lex error");
        } catch (Error e) {
            assertTrue(e.getMessage(), e.getMessage().contains("unterminated string literal"));
            assertTrue(e.getMessage(), e.getMessage().contains("column"));
        }
    }

    @Test
    public void syntaxErrorHasExpectedSet() {
        try {
            db.parse("insert into t values (1, );");
            fail("expected syntax error");
        } catch (Error e) {
            assertEquals("SY-0001", e.code());
            // and 之后应是列名（或字面量），期望集合 + 行列定位
            assertTrue(e.getMessage(), e.getMessage().contains("expected: NUMBER | STR_LIT | TRUE | FALSE"));
            assertTrue(e.getMessage(), e.getMessage().contains("at line 1, column"));
        }
    }

    @Test
    public void commentsAreSkipped() {
        List<Token> ts = db.tokenize(
                "-- 行注释\nselect /* 块\n注释 */ id from t");
        // 注释不产出 token：select id from t EOF
        assertEquals(5, ts.size());
        assertEquals(TokenType.SELECT, ts.get(0).type);
        assertEquals(2, ts.get(0).line);   // select 在第二行
    }

    // ---- 约束语法与 NULL 字面量的 trace ----

    /** 第②阶段靠反射判断 AST 是否自定义了 toString：不写就只打印节点名，约束在 trace 里会全隐身。 */
    @Test
    public void traceShowsConstraintsInAst() {
        String out = Tracer.trace(db, "create table t (id int32 primary key, name string not null, "
                + "age int32 default 18, score int32 check (score >= 0))");
        assertTrue(out.contains("--② 语法分析"));
        assertTrue(out, out.contains("create table t"));
        assertTrue(out, out.contains("primary key"));
        assertTrue(out, out.contains("not null"));
        assertTrue(out, out.contains("default 18"));
        assertTrue(out, out.contains("check (score >= 0)"));
    }

    /** 多列约束不能被"贴在列上"的规则吞掉：表级 PK/UNIQUE 要单独出现。 */
    @Test
    public void traceShowsTableLevelConstraint() {
        String out = Tracer.trace(db, "create table p (a int32, b int32, primary key (a, b))");
        assertTrue(out, out.contains("primary key (a, b)"));
    }

    /** NULL 是新字面量：词法/语法/计划三阶段都要能过。 */
    @Test
    public void traceAcceptsNullLiteral() {
        db.execute("create table t (a int32, b string)");
        String out = Tracer.trace(db, "insert into t values (1, null)");
        assertTrue(out.contains("--① 词法分析"));
        assertTrue(out, out.contains("NULL"));          // token 流里看得见 NULL
        assertTrue(out.contains("--③ 语义分析: OK"));
        assertTrue(out, out.contains("Insert(t)"));
    }

    /** SHOW TABLE 的结果集多了第三列（约束），trace 到第④阶段仍要能编译。 */
    @Test
    public void traceShowTableWithConstraints() {
        db.execute("create table t (id int32 primary key)");
        String out = Tracer.trace(db, "show table t");
        assertTrue(out.contains("--④ 逻辑执行计划"));
        assertTrue(out, out.contains("ShowTable(t)"));
    }

    /** INSERT 的可选列清单也要进 token 流与语法树。 */
    @Test
    public void traceAcceptsInsertColumnList() {
        db.execute("create table t (a int32, b string default 'x')");
        String out = Tracer.trace(db, "insert into t (a) values (1)");
        assertTrue(out.contains("--③ 语义分析: OK"));
        assertTrue(out, out.contains("Insert(t)"));
    }
}
