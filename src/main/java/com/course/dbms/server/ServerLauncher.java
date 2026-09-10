package com.course.dbms.server;

import com.course.dbms.common.Consts;
import com.course.dbms.common.Log;
import com.course.dbms.db.Database;

import java.io.File;

/**
 * 服务端启动入口。
 * 用法：server <create|open> <数据库目录> [端口]
 *   create —— 清空目录后新建一个库（首次演示用）
 *   open   —— 打开已有目录（重启恢复）
 */
public class ServerLauncher {

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "open";
        String dir = args.length > 1 ? args[1] : "data/db";
        int port = args.length > 2 ? Integer.parseInt(args[2]) : Consts.DEFAULT_PORT;

        if (cmd.equalsIgnoreCase("create")) {
            wipe(dir);
            Log.info("[CREATE] fresh database at '" + dir + "'");
        }

        Database db = new Database(dir);
        Log.info("[OPEN] database dir = " + dir);

        Server server = new Server(db, port);
        server.start();

        // 主线程挂起，等待外部终止
        Thread.currentThread().join();
    }

    private static void wipe(String dir) {
        File d = new File(dir);
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (!f.delete()) Log.warn("[CREATE] cannot delete " + f);
        }
    }
}
