package com.course.dbms.client;

import com.course.dbms.common.Consts;

import java.net.Socket;

/**
 * 客户端入口。
 * 用法：client [-e SQL] [-f 文件] [host] [port]
 *   -e SQL   直接执行一条 SQL 后退出
 *   -f 文件  批量执行文件中的语句后退出
 *   缺省 host=127.0.0.1 port=9999
 */
public class ClientLauncher {

    public static void main(String[] args) throws Exception {
        String host = null;
        int port = -1;
        String sql = null;
        String file = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-e": sql = args[++i]; break;
                case "-f": file = args[++i]; break;
                default:
                    if (host == null) host = args[i];
                    else port = Integer.parseInt(args[i]);
            }
        }
        if (host == null) host = Consts.DEFAULT_HOST;
        if (port < 0) port = Consts.DEFAULT_PORT;

        try (Socket socket = new Socket(host, port)) {
            Shell shell = new Shell(socket);
            if (sql != null) { shell.execute(sql); return; }
            if (file != null) { shell.executeFile(file); return; }
            shell.run();
        }
    }
}
