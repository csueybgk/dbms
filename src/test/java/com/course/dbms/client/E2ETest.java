package com.course.dbms.client;

import com.course.dbms.db.Database;
import com.course.dbms.server.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;

import static org.junit.Assert.*;

/** 步骤13：客户端 -f 端到端联调（真实 Socket + ClientLauncher -f DEMO.sql）。 */
public class E2ETest {

    private Database db;
    private Server server;
    private final String dir = "target/tmp-e2e";

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

    @Test public void demoBatchThroughClientLauncher() throws Exception {
        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(cap, true, "UTF-8"));
        try {
            String demo = new File("DEMO.sql").getCanonicalPath();
            ClientLauncher.main(new String[] { "127.0.0.1", String.valueOf(server.port()), "-f", demo });
        } finally {
            System.setOut(old);
        }
        String out = cap.toString("UTF-8");
        assertTrue(out.contains("users"));      // show tables 输出表名
        assertTrue(out.contains("alice"));      // select 返回 alice
        assertTrue(out.contains("bob"));        // select 返回 bob
        // order by score desc：alice(95.5) carol(91.5) bob(88.0)；age>24 只剩 bob → 1 行
        assertTrue(out.contains("1 row(s)"));
        // JOIN：u.name,o.amount order by o.amount → bob/50.0  alice/75.0  alice/100.0
        assertTrue(out.contains("100.0"));
        assertTrue(out.contains("50.0"));
        // 聚合：alice count=2 avg=(75+100)/2=87.5（仅聚合首次引入 87.5，避免数 id 等弱命中）
        assertTrue(out.contains("87.5"));
        // 索引：CREATE INDEX / SHOW INDEXES 走通；建索引后插入的 dave 也能被 where age=30 查到
        assertTrue("show indexes 应列出 idx_age", out.contains("idx_age"));
        assertTrue("索引扫描应查到建索引后插入的 dave", out.contains("dave"));

        // 完整性约束：新语法要能穿过真实 Socket 往返 ——
        // show table 的第三列（约束）经协议编码后仍要看得见
        assertTrue("show table 应列出主键约束", out.contains("primary key"));
        assertTrue("show table 应回显 CHECK 表达式原文", out.contains("check (bonus <= 1000)"));
        // 两条非法插入必须被拒绝，且错误经错误包渲染回客户端
        assertTrue("缺列插入应被 NOT NULL 拒绝", out.contains("SE-0010"));
        assertTrue("重复主键应被 PRIMARY KEY 拒绝", out.contains("SE-0011"));
    }
}
