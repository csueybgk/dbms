package com.course.dbms.server;

import com.course.dbms.db.Database;
import com.course.dbms.engine.Result;
import com.course.dbms.protocol.Decoder;
import com.course.dbms.protocol.Package;
import com.course.dbms.protocol.Packager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * 真实双连接并发测试：验证
 *   1. 一个事务对某表持写锁未提交时，另一连接的 select 会被阻塞，直到提交；
 *   2. 两个连接各自自动提交的并发 insert 不丢更新、不报错。
 */
public class ConcurrencyTest {

    private Database db;
    private Server server;
    private final String dir = "target/tmp-concur";

    @Before public void setup() throws Exception {
        wipe(dir);
        db = new Database(dir);
        server = new Server(db, 0);
        server.start();
    }
    @After public void teardown() throws Exception {
        server.close();
        db.close();
    }

    private static void wipe(String d) {
        File f = new File(d);
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) x.delete();
    }

    /** 一个保持打开的连接（跨多条 SQL 复用，事务才能跨语句）。 */
    private static final class Client implements AutoCloseable {
        final Socket s;
        final DataInputStream in;
        final DataOutputStream out;
        Client(int port) throws IOException {
            s = new Socket("127.0.0.1", port);
            in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        }
        Result exec(String sql) throws IOException {
            Packager.write(out, new Package(false, sql.getBytes("UTF-8")));
            Package pkg = Packager.read(in);
            if (pkg.error) throw new RuntimeException(new String(pkg.payload, "UTF-8"));
            return Decoder.decodeResult(pkg.payload);
        }
        @Override public void close() throws IOException { s.close(); }
    }

    @Test public void writerTxnBlocksConcurrentReader() throws Exception {
        int port = server.port();
        try (Client setup = new Client(port)) {
            setup.exec("create table users (id int32, name string, age int32, score float64)");
            setup.exec("insert into users values (1, 'alice', 23, 95.5)");
            setup.exec("insert into users values (2, 'bob', 30, 88.0)");
        }

        final CountDownLatch aHasX = new CountDownLatch(1);
        final CountDownLatch bDone = new CountDownLatch(1);
        final AtomicReference<Throwable> err = new AtomicReference<>();
        final AtomicReference<Integer> bRows = new AtomicReference<>();

        Thread ta = new Thread(() -> {
            try (Client a = new Client(port)) {
                a.exec("begin");
                a.exec("insert into users values (3, 'carol', 22, 91.5)");
                aHasX.countDown();             // 此刻 A 已持有 users 的写锁（未提交）
                Thread.sleep(500);             // 保持事务开着，让 B 有机会来读
                a.exec("commit");
            } catch (Throwable e) { err.set(e); }
        });
        ta.start();
        assertTrue("A 应已拿写锁", aHasX.await(2, TimeUnit.SECONDS));

        Thread tb = new Thread(() -> {
            try (Client b = new Client(port)) {
                Result r = b.exec("select * from users");
                bRows.set(r.rows.size());
            } catch (Throwable e) { err.set(e); }
            finally { bDone.countDown(); }
        });
        tb.start();
        // B 的 select 只有在 A commit 后才会返回；若未阻塞会提前返回（1 行），这里尽量宽裕
        assertTrue("B 的 select 应被 A 阻塞到提交后才返回", bDone.await(6, TimeUnit.SECONDS));
        ta.join(3000);
        tb.join(3000);
        assertNull("运行出错: " + err.get(), err.get());
        assertEquals("提交后应看到 3 行", Integer.valueOf(3), bRows.get());
    }

    @Test public void concurrentAutocommitInsertsNoLostUpdate() throws Exception {
        int port = server.port();
        try (Client setup = new Client(port)) {
            setup.exec("create table t (id int32, v int32)");
        }
        final int n = 50;
        final AtomicReference<Throwable> err = new AtomicReference<>();
        Runnable writer = () -> {
            try (Client c = new Client(port)) {
                for (int i = 0; i < n; i++) {
                    c.exec("insert into t values (" + i + ", " + i + ")");
                }
            } catch (Throwable e) { err.set(e); }
        };
        Thread t1 = new Thread(writer);
        Thread t2 = new Thread(writer);
        t1.start();
        t2.start();
        t1.join(15000);
        t2.join(15000);
        assertNull("并发插入出错: " + err.get(), err.get());
        try (Client c = new Client(port)) {
            Result r = c.exec("select * from t");
            assertEquals("不应丢更新", 2 * n, r.rows.size());
        }
    }
}
