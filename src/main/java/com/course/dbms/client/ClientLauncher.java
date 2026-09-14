package com.course.dbms.client;

import com.course.dbms.common.Consts;
import com.course.dbms.common.Error;
import com.course.dbms.common.ErrorCode;

import java.io.IOException;
import java.net.Socket;

/**
 * 客户端入口。
 * 用法：client [-e SQL] [-f 文件] [-t] [host] [port]
 *   -e SQL   直接执行一条 SQL 后退出
 *   -f 文件  批量执行文件中的语句后退出
 *   -t       trace 模式：每条语句先回显编译四阶段输出（Token流→AST→语义→计划）
 *   缺省 host=127.0.0.1 port=9999
 *
 * 所有失败路径都翻译成一行 {@code ✗ [CL-xxxx] 说明}（CL-0001..CL-0005），
 * 不再向用户抛 Java 堆栈 —— 参数写错、服务端没起、脚本文件不存在，都是用户
 * 一眼能看懂并知道下一步做什么的提示。
 */
public class ClientLauncher {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Error e) {
            // 已经是统一的 ✗ [CODE] 说明 格式，直接照原样打印
            System.out.println(e.toString());
            System.exit(1);
        } catch (IOException e) {
            // 走到这里说明是客户端本地 I/O（如控制台读取）失败，不是连接问题
            System.out.println(new Error(ErrorCode.CL_INTERNAL,
                    "客户端 I/O 失败: " + e.getMessage() + "（本地输入输出出错，并非 SQL 的问题）", e));
            System.exit(1);
        } catch (RuntimeException e) {
            // 兜底：客户端自身的缺陷。宁可给一句可读的话，也不要甩一段堆栈
            System.out.println(new Error(ErrorCode.CL_INTERNAL,
                    "客户端内部错误: " + e + "（这是客户端自身的缺陷，不是 SQL 的问题）", e));
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        String host = null;
        int port = -1;
        String sql = null;
        String file = null;
        boolean trace = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-e":
                    if (i + 1 >= args.length) throw badArg("-e 后面要跟一条 SQL");
                    sql = args[++i];
                    break;
                case "-f":
                    if (i + 1 >= args.length) throw badArg("-f 后面要跟一个文件路径");
                    file = args[++i];
                    break;
                case "-t":
                    trace = true;
                    break;
                default:
                    if (args[i].startsWith("-")) throw badArg("未知的选项 " + args[i]);
                    if (host == null) host = args[i];
                    else if (port < 0) port = parsePort(args[i]);
                    else throw badArg("多余的参数 " + args[i]);
            }
        }
        if (host == null) host = Consts.DEFAULT_HOST;
        if (port < 0) port = Consts.DEFAULT_PORT;

        Socket socket;
        try {
            socket = new Socket(host, port);
        } catch (IOException e) {
            throw new Error(ErrorCode.CL_CONNECT_FAILED,
                    "无法连接 " + host + ":" + port + "（服务端未启动，或 host/port 写错了）", e);
        }

        try (Socket s = socket) {
            Shell shell = new Shell(s);
            shell.setTrace(trace);
            if (sql != null) { shell.execute(sql); return; }
            if (file != null) { shell.executeFile(file); return; }
            shell.run();
        }
    }

    /** 端口必须是 1..65535 的整数；写错了要说清"实际给了什么"。 */
    private static int parsePort(String s) {
        int p;
        try {
            p = Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw badArg("端口必须是数字，实际是 \"" + s + "\"");
        }
        if (p < 1 || p > 65535) {
            throw badArg("端口必须落在 1..65535，实际是 " + p);
        }
        return p;
    }

    private static Error badArg(String why) {
        return new Error(ErrorCode.CL_BAD_ARGUMENT,
                "命令行参数错误: " + why + "（用法: client [-e SQL] [-f 文件] [-t] [host] [port]）");
    }
}
