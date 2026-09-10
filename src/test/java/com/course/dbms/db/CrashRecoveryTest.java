package com.course.dbms.db;

import com.course.dbms.common.Consts;
import com.course.dbms.engine.Result;
import com.course.dbms.txn.Txn;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/**
 * 崩溃恢复（经 Session，与真实服务端一致）：
 *  - 已提交事务在"崩溃重开"后仍在（持久性：commit 写 WAL + 落页）；
 *  - 未提交事务在崩溃后消失（原子性：影子页从未落盘，且无 WAL 记录）。
 *
 * 用"不做干净 close / 清除线程事务"来模拟进程崩溃，而非正常关停。
 */
public class CrashRecoveryTest {

    private Database db;
    private Session s;
    private final String dir = "target/tmp-crash";

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

    /** 已提交事务在崩溃重开后仍在：证明 commit 让数据持久。 */
    @Test public void committedTxnSurvivesCrash() {
        seedUsers();
        s.execute("begin");
        s.execute("insert into users values (3, 'carol', 22, 91.5)");
        s.execute("commit");
        // 提交应已经把 WAL 写出来（证明 commit 真走了 WAL），否则测的是影子路径而非崩溃恢复
        assertTrue("commit 应产生 WAL 文件", new File(dir, Consts.WAL_FILE).length() > 0);

        // 模拟崩溃：不 close 第一个库，直接在同一目录"重开"一个新库（跑恢复）
        Database db2 = new Database(dir);
        Session s2 = new Session(db2);
        Result r = s2.execute("select * from users");
        assertEquals("已提交事务应在崩溃后仍在", 3, r.rows.size());
        db2.close();
    }

    /** 未提交事务在崩溃后被丢弃：影子页从不落盘，恢复也不重放它。 */
    @Test public void uncommittedTxnRolledBackOnCrash() {
        seedUsers();
        s.execute("begin");
        s.execute("insert into users values (3, 'carol', 22, 91.5)");
        // 模拟崩溃：进程终止，线程上绑定的活动事务随之消失（不 commit，也不 rollback）
        Txn.clearCurrent();

        Database db2 = new Database(dir);
        Session s2 = new Session(db2);
        Result r = s2.execute("select * from users");
        assertEquals("未提交事务应在崩溃后被丢弃", 2, r.rows.size());
        db2.close();
    }
}
