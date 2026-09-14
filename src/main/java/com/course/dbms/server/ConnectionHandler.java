package com.course.dbms.server;

import com.course.dbms.common.Error;
import com.course.dbms.common.ErrorCode;
import com.course.dbms.common.Log;
import com.course.dbms.compiler.Tracer;
import com.course.dbms.db.Database;
import com.course.dbms.db.Session;
import com.course.dbms.engine.Result;
import com.course.dbms.protocol.Encoder;
import com.course.dbms.protocol.Package;
import com.course.dbms.protocol.Packager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 一次连接的处理器：每个客户端连接一个线程。
 * 循环：读 SQL 包 → Database 执行 → 把结果（或错误）编码回包。
 * 对应图片"客户端与服务器端交互"的服务端一侧。
 *
 * trace 前缀：SQL 以 "trace " 开头（客户端 \trace 开关自动添加）时，先用 Tracer
 * 产出编译四阶段输出（Token流→AST→语义→计划）作为 trace 包回传，再执行真正的语句。
 */
public class ConnectionHandler implements Runnable {

    private final Database db;
    private final Socket socket;

    public ConnectionHandler(Database db, Socket socket) {
        this.db = db;
        this.socket = socket;
    }

    @Override public void run() {
        try (Socket s = socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()))) {

            Session session = new Session(db);
            while (true) {
                Package pkg;
                try {
                    pkg = Packager.read(in);
                } catch (EOFException e) {
                    break; // 对端关闭
                }
                String sql = new String(pkg.payload, StandardCharsets.UTF_8);
                Log.info("[client " + s.getRemoteSocketAddress() + "] " + sql);
                try {
                    // trace 前缀检测：整句以 "trace" 开头且后跟空白/结束
                    String text = sql.trim();
                    boolean trace = text.length() >= 5
                            && text.regionMatches(true, 0, "trace", 0, 5)
                            && (text.length() == 5 || Character.isWhitespace(text.charAt(5)));
                    String real = trace ? text.substring(5).trim() : sql;

                    if (trace) {
                        String t = Tracer.trace(db, real);
                        Packager.write(out, new Package(false, true, t.getBytes(StandardCharsets.UTF_8)));
                    }
                    Result r = session.execute(real);
                    Packager.write(out, new Package(false, Encoder.encodeResult(r)));
                } catch (Error e) {
                    Log.warn("[client " + s.getRemoteSocketAddress() + "] " + e.toString());
                    Packager.write(out, new Package(true, Encoder.encodeError(e)));
                } catch (RuntimeException e) {
                    // 兜底：任何没有包装成 Error 的运行时异常（例如某处未预期的类型强转）
                    // 过去会一路穿出本方法、掐断连接，客户端只看到 EOFException 堆栈。
                    // 现在服务端把完整堆栈记进日志供排查，客户端收到一句 SV-0001，
                    // 连接保持可用 —— "所有错误都有回复"的最后一道网兜。
                    Log.error("[client " + s.getRemoteSocketAddress() + "] 内部错误: " + e);
                    Log.error(stackOf(e));
                    Packager.write(out, new Package(true, Encoder.encodeError(
                            new Error(ErrorCode.SV_INTERNAL,
                                    "服务端内部错误: " + e.getClass().getSimpleName()
                                            + "（已记入服务端日志；连接未中断，可以继续执行别的语句）", e))));
                }
            }
            Log.info("[client " + s.getRemoteSocketAddress() + "] disconnected");
        } catch (IOException e) {
            Log.error("[conn] " + e.getMessage());
        }
    }

    /** 异常堆栈转字符串，交给日志（Log 只收消息字符串）。 */
    private static String stackOf(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
