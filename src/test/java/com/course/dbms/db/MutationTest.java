package com.course.dbms.db;

import com.course.dbms.common.Error;
import com.course.dbms.txn.Txn;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 从 SQL 入口验证写操作，避免只验证解析而遗漏索引和持久化。 */
public class MutationTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private Database db;
    private Session session;
    @Before public void setup() {
        db = new Database(folder.getRoot().getPath());
        session = new Session(db);
        session.execute("create table t (id int32, name string)");
        session.execute("insert into t values (1, '甲')");
        session.execute("insert into t values (2, '乙')");
        session.execute("insert into t values (3, '丙')");
        session.execute("create index ix on t (id)");
    }
    @After public void close() {
        if (Txn.current() != null) session.execute("rollback");
        db.close();
    }
    private int count(String sql) { return ((Number) session.execute(sql).rows.get(0).get(0)).intValue(); }
    @Test public void conditionalDeleteAndAllRows() {
        assertEquals(2, count("delete from t where t.id = 1 or id >= 3"));
        assertEquals(0, session.execute("select * from t where id = 1").rows.size());
        assertEquals(1, session.execute("select * from t").rows.size());
        assertEquals(0, count("delete from t where id = 99"));
        assertEquals(1, count("delete from t"));
        assertEquals(0, count("delete from t"));
    }
    @Test public void updateMultipleColumnsAndIndexKeys() {
        assertEquals(2, count("update t set id = 8, name = '修改' where id >= 2 and id <= 3"));
        assertEquals(2, session.execute("select * from t where id = 8").rows.size());
        assertEquals(0, session.execute("select * from t where id = 2").rows.size());
        assertEquals("甲", session.execute("select * from t where id = 1").rows.get(0).get(1));
        assertEquals(3, count("update t set name = '全部'"));
        assertEquals(0, count("update t set id = 4 where id = 99"));
    }
    @Test public void rollbackAndReopen() {
        session.execute("begin");
        session.execute("update t set id = 8 where id = 1");
        session.execute("delete from t where id = 2");
        assertEquals(1, session.execute("select * from t where id = 8").rows.size());
        session.execute("rollback");
        assertEquals(1, session.execute("select * from t where id = 1").rows.size());
        assertEquals(1, session.execute("select * from t where id = 2").rows.size());
        session.execute("begin");
        session.execute("update t set id = 8 where id = 1");
        session.execute("delete from t where id = 2");
        session.execute("commit");
        db.close();
        db = new Database(folder.getRoot().getPath()); session = new Session(db);
        assertEquals(2, session.execute("select * from t").rows.size());
        assertEquals(1, session.execute("select * from t where id = 8").rows.size());
        assertEquals(0, session.execute("select * from t where id = 2").rows.size());
    }
    @Test public void invalidWritesLeaveRowsIntact() {
        String[] invalid = {"update t set missing = 1", "update t set id = 'bad'", "update t set id = 4, ID = 5", "delete from t where other.id = 1", "update t set id > 4", "update t set name = '" + "x".repeat(5000) + "'"};
        for (String sql : invalid) {
            assertThrows(Error.class, () -> session.execute(sql));
            assertEquals(3, session.execute("select * from t").rows.size());
            assertEquals(1, session.execute("select * from t where id = 1").rows.size());
        }
    }
    @Test public void growingRowsAcrossPagesAreUpdatedOnce() {
        for (int i = 4; i <= 80; i++) session.execute("insert into t values (" + i + ", 'old')");
        String value = "长".repeat(500);
        assertEquals(80, count("update t set name = '" + value + "' where id = id"));
        assertEquals(80, session.execute("select * from t").rows.size());
        assertEquals(value, session.execute("select * from t where id = 40").rows.get(0).get(1));
        assertEquals(80, count("delete from t"));
        session.execute("insert into t values (40, 'new')");
        assertEquals(1, session.execute("select * from t where id = 40").rows.size());
    }
    @Test public void fullPageDeletedSlotIsReused() {
        session.execute("create table fullrow (value string)");
        String sql = "insert into fullrow values ('" + "x".repeat(4072) + "')";
        session.execute(sql);
        session.execute("delete from fullrow");
        session.execute(sql);
        assertEquals(1, db.pages().pageCount(db.catalog().getTable("fullrow").tableId()));
        assertEquals(1, session.execute("select * from fullrow").rows.size());
    }

}
