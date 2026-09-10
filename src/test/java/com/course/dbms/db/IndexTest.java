package com.course.dbms.db;

import com.course.dbms.common.Error;
import com.course.dbms.engine.Result;
import com.course.dbms.engine.table.Row;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * B+ 树索引的端到端测试（经 Database/Session 的完整管线）：
 *   - 建索引前后【查询结果必须完全一致】（索引只是加速，不是另一套语义）；
 *   - 等值 / 范围 / 与其它条件并存都能正确；
 *   - 索引元数据落盘，重启后重建；事务内建索引回滚后消失；
 *   - 建索引之后插入的新行也能被索引查到（insert 维护钩子）。
 */
public class IndexTest {

    private Database db;
    private Session s;
    private final String dir = "target/tmp-index";

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

    private void seed() {
        s.execute("create table users (id int32, name string, age int32, score float64)");
        s.execute("insert into users values (1, 'alice', 23, 95.5)");
        s.execute("insert into users values (2, 'bob', 30, 88.0)");
        s.execute("insert into users values (3, 'carol', 23, 91.5)");
        s.execute("insert into users values (4, 'dave', 30, 99.0)");
    }

    /** 把结果里某一列拉成列表，便于逐行断言。 */
    private List<Object> col(Result r, int i) {
        List<Object> out = new ArrayList<>();
        for (Row row : r.rows) out.add(row.get(i));
        return out;
    }

    private static List<Object> vals(Object... vs) {
        List<Object> l = new ArrayList<>();
        for (Object v : vs) l.add(v);
        return l;
    }

    /** 核心对照：同一条查询在建索引前后结果逐行一致。 */
    @Test public void indexGivesSameRowsAsSeqScan() {
        seed();
        String q = "select id, name from users where age = 30 order by id";
        Result before = s.execute(q);

        s.execute("create index idx_age on users(age)");
        Result after = s.execute(q);

        assertEquals("行数不变", before.rows.size(), after.rows.size());
        assertEquals(vals(2, "bob"), before.rows.get(0).values());
        assertEquals("结果逐行一致", before.rows.get(0).values(), after.rows.get(0).values());
        assertEquals(before.rows.get(1).values(), after.rows.get(1).values());
        assertEquals(2, after.rows.size());
    }

    @Test public void equalityScanFindsDuplicates() {
        seed();
        s.execute("create index idx_age on users(age)");
        Result r = s.execute("select id from users where age = 23 order by id");
        assertEquals("重复键的两行都要出来", vals(1, 3), col(r, 0));
    }

    @Test public void rangeScanWithOpenAndClosedBounds() {
        seed();
        s.execute("create index idx_age on users(age)");
        // age 分别为 23 23 30 30
        assertEquals(vals(1, 3), col(s.execute("select id from users where age < 30 order by id"), 0));
        assertEquals(vals(2, 4), col(s.execute("select id from users where age >= 30 order by id"), 0));
        assertEquals(vals(2, 4), col(s.execute("select id from users where age > 23 order by id"), 0));
        assertEquals(4, s.execute("select id from users where age >= 23").rows.size());
        assertEquals("范围外无命中", 0, s.execute("select id from users where age > 99").rows.size());
    }

    /** 索引扫描之上仍叠着 Filter：索引外的条件照旧生效。 */
    @Test public void filterStillAppliesAboveIndexScan() {
        seed();
        s.execute("create index idx_age on users(age)");
        Result r = s.execute("select id, name from users where age = 30 and score > 90 order by id");
        assertEquals("age=30 命中两行，score>90 再筛掉一行", 1, r.rows.size());
        assertEquals("dave", r.rows.get(0).get(1));

        // OR 条件的任一支用不上索引 → 规划器整体退回 SeqScan，结果依然正确
        Result or = s.execute("select id from users where age = 30 or name = 'alice' order by id");
        assertEquals(vals(1, 2, 4), col(or, 0));
    }

    @Test public void sortAndIndexCombineCorrectly() {
        seed();
        s.execute("create index idx_age on users(age)");
        Result r = s.execute("select id from users where age >= 23 order by id desc");
        assertEquals(vals(4, 3, 2, 1), col(r, 0));
    }

    @Test public void indexIsMaintainedOnLaterInserts() {
        seed();
        s.execute("create index idx_age on users(age)");
        s.execute("insert into users values (5, 'eve', 30, 77.0)");
        Result r = s.execute("select id from users where age = 30 order by id");
        assertEquals("建索引后插入的新行也要能被索引查到", vals(2, 4, 5), col(r, 0));
    }

    @Test public void stringColumnIndexWorksToo() {
        seed();
        s.execute("create index idx_name on users(name)");
        Result r = s.execute("select id from users where name = 'carol'");
        assertEquals(vals(3), col(r, 0));
        // 字符串按字典序比较：alice < bob < carol < dave → >= 'bob' 命中后三个
        assertEquals(vals(2, 3, 4), col(s.execute("select id from users where name >= 'bob' order by id"), 0));
    }

    @Test public void showIndexesListsMetadata() {
        seed();
        assertEquals(0, s.execute("show indexes").rows.size());
        s.execute("create index idx_age on users(age)");
        Result r = s.execute("show indexes");
        assertEquals(1, r.rows.size());
        assertEquals(vals("idx_age", "users", "age"), r.rows.get(0).values());
        // 限定到某张表
        assertEquals(1, s.execute("show indexes on users").rows.size());
        s.execute("create table t (a int32)");
        assertEquals("t 上没有索引", 0, s.execute("show indexes on t").rows.size());
    }

    @Test public void duplicateIndexNameRejected() {
        seed();
        s.execute("create index idx_age on users(age)");
        try {
            s.execute("create index idx_age on users(score)");
            fail("重复索引名应被拒绝");
        } catch (Error e) {
            assertEquals("SE-0007", e.code());
        }
    }

    @Test public void indexOnUnknownColumnOrTableRejected() {
        seed();
        try {
            s.execute("create index idx_x on users(nosuch)");
            fail("列不存在应报错");
        } catch (Error e) {
            assertEquals("SE-0004", e.code());
        }
        try {
            s.execute("create index idx_y on ghost(a)");
            fail("表不存在应报错");
        } catch (Error e) {
            assertEquals("TB-0001", e.code());
        }
    }

    /** 索引元数据存在系统表里，所以重启后索引仍在（内容从基表重建）。 */
    @Test public void indexSurvivesReopen() {
        seed();
        s.execute("create index idx_age on users(age)");
        db.close();

        Database db2 = new Database(dir);
        Session s2 = new Session(db2);
        assertEquals("重启后索引元数据仍在", 1, s2.execute("show indexes").rows.size());
        assertEquals("重启后索引内容已从基表重建", 2,
                s2.execute("select id from users where age = 30").rows.size());
        db2.close();
    }

    /** 事务内建索引，回滚后应消失（Catalog.reload 重建时不会带上它）。 */
    @Test public void indexRolledBackWithTransaction() {
        seed();
        s.execute("begin");
        s.execute("create index idx_age on users(age)");
        assertEquals("事务内可见", 1, s.execute("show indexes").rows.size());
        assertEquals("事务内索引扫描可用", 2, s.execute("select id from users where age = 30").rows.size());
        s.execute("rollback");

        assertEquals("回滚后索引应消失", 0, s.execute("show indexes").rows.size());
        assertEquals("退回全表扫描，结果不变", 2, s.execute("select id from users where age = 30").rows.size());
    }

    /** 事务内建索引并提交：索引留下，且内容含事务内插入的行。 */
    @Test public void indexCommittedWithTransaction() {
        seed();
        s.execute("begin");
        s.execute("insert into users values (5, 'eve', 42, 70.0)");
        s.execute("create index idx_age on users(age)");
        s.execute("commit");

        assertEquals(1, s.execute("show indexes").rows.size());
        assertEquals(vals(5), col(s.execute("select id from users where age = 42"), 0));

        Database db2 = new Database(dir);
        Session s2 = new Session(db2);
        assertEquals("提交后重启，索引与其内容都在", 1, s2.execute("show indexes").rows.size());
        assertEquals(1, s2.execute("select id from users where age = 42").rows.size());
        db2.close();
    }
}
