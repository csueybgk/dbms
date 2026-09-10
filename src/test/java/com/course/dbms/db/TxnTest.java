package com.course.dbms.db;

import com.course.dbms.common.Error;
import com.course.dbms.engine.Result;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * 事务语义测试：回滚无痕、提交持久、读己之写、事务内建表可回滚、并发隔离阻塞。
 * 所有语句都经 {@link Session}（与真实服务端一致），而非裸 db.execute。
 */
public class TxnTest {

    private Database db;
    private Session s;
    private final String dir = "target/tmp-txn";

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

    private void seedUsers() {
        s.execute("create table users (id int32, name string, age int32, score float64)");
        s.execute("insert into users values (1, 'alice', 23, 95.5)");
        s.execute("insert into users values (2, 'bob', 30, 88.0)");
    }

    @Test public void beginRollbackLeavesNoChange() {
        seedUsers();
        s.execute("begin");
        s.execute("insert into users values (3, 'carol', 22, 91.5)");
        Result r = s.execute("select * from users");
        assertEquals(3, r.rows.size());          // 事务内读己之写
        s.execute("rollback");
        r = s.execute("select * from users");
        assertEquals(2, r.rows.size());          // 回滚后恢复
    }

    @Test public void beginCommitPersists() {
        seedUsers();
        s.execute("begin");
        s.execute("insert into users values (3, 'carol', 22, 91.5)");
        s.execute("commit");
        Result r = s.execute("select * from users");
        assertEquals(3, r.rows.size());

        // 重启后仍可见
        db.close();
        db = new Database(dir);
        s = new Session(db);
        r = s.execute("select * from users");
        assertEquals(3, r.rows.size());
    }

    @Test public void readYourWrites() {
        seedUsers();
        s.execute("begin");
        s.execute("insert into users values (4, 'dave', 35, 99.0)");
        Result r = s.execute("select id, name from users where id = 4");
        assertEquals(1, r.rows.size());
        assertEquals("dave", r.rows.get(0).get(1));
        s.execute("rollback");
    }

    @Test public void createTableRollbackUndo() {
        s.execute("begin");
        s.execute("create table phantom (x int32)");
        Result r = s.execute("show tables");
        boolean seen = false;
        for (com.course.dbms.engine.table.Row row : r.rows) if ("phantom".equals(row.get(0))) seen = true;
        assertTrue("事务内应看到自建表", seen);
        s.execute("rollback");
        r = s.execute("show tables");
        for (com.course.dbms.engine.table.Row row : r.rows) {
            assertFalse("回滚后不应有 phantom 表", "phantom".equals(row.get(0)));
        }
    }

    @Test public void nestedBeginRejected() {
        s.execute("begin");
        try {
            s.execute("begin");
            fail("重复 BEGIN 应报错");
        } catch (Error e) {
            assertEquals("TX-0002", e.code());
        }
        s.execute("rollback");
    }

    @Test public void commitWithoutBeginRejected() {
        try {
            s.execute("commit");
            fail("无事务 COMMIT 应报错");
        } catch (Error e) {
            assertEquals("TX-0001", e.code());
        }
    }

    /** 事务 A 持 X 写锁未提交，会话 B 在 select 上应被阻塞，直到 A 提交。 */
    @Test public void isolationBlocksConcurrentReader() throws Exception {
        seedUsers();
        Session a = new Session(db);
        Session b = new Session(db);

        final CountDownLatch aHasX = new CountDownLatch(1);
        final CountDownLatch bDone = new CountDownLatch(1);
        final AtomicReference<Throwable> err = new AtomicReference<>();
        final AtomicReference<Integer> bRows = new AtomicReference<>();

        Thread ta = new Thread(() -> {
            try {
                a.execute("begin");
                a.execute("insert into users values (3, 'carol', 22, 91.5)");
                aHasX.countDown();
                Thread.sleep(300);               // 持锁一段时间，让 b 有机会阻塞
                a.execute("commit");
            } catch (Throwable e) { err.set(e); }
        });
        ta.start();
        assertTrue("A 应已拿到写锁", aHasX.await(2, TimeUnit.SECONDS));

        Thread tb = new Thread(() -> {
            try {
                Result r = b.execute("select * from users");
                bRows.set(r.rows.size());
            } catch (Throwable e) { err.set(e); }
            finally { bDone.countDown(); }
        });
        tb.start();
        assertTrue("B 的 select 应在 A 提交后才返回", bDone.await(5, TimeUnit.SECONDS));
        ta.join(2000);
        tb.join(2000);
        assertNull("运行出错: " + err.get(), err.get());
        assertEquals(Integer.valueOf(3), bRows.get());
    }
}
