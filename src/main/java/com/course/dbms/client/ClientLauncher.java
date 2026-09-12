package com.course.dbms.client;

import com.course.dbms.common.Consts;

import java.net.Socket;

/**
 * 客户端入口。
 * 用法：client [-e SQL] [-f 文件] [-t] [host] [port]
 *   -e SQL   直接执行一条 SQL 后退出
 *   -f 文件  批量执行文件中的语句后退出
 *   -t       trace 模式：每条语句先回显编译四阶段输出（Token流→AST→语义→计划）
 *   缺省 host=127.0.0.1 port=9999
 */
public class ClientLauncher {

    public static void main(String[] args) throws Exception {
        String host = null;
        int port = -1;
        String sql = null;
        String file = null;
        boolean trace = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-e": sql = args[++i]; break;
                case "-f": file = args[++i]; break;
                case "-t": trace = true; break;
                default:
                    if (host == null) host = args[i];
                    else port = Integer.parseInt(args[i]);
            }
        }
        if (host == null) host = Consts.DEFAULT_HOST;
        if (port < 0) port = Consts.DEFAULT_PORT;

        try (Socket socket = new Socket(host, port)) {
            Shell shell = new Shell(socket);
            shell.setTrace(trace);
            if (sql != null) { shell.execute(sql); return; }
            if (file != null) { shell.executeFile(file); return; }
            shell.run();
        }
    }
}
