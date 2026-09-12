package com.course.dbms.server;

import com.course.dbms.common.Error;
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
                }
            }
            Log.info("[client " + s.getRemoteSocketAddress() + "] disconnected");
        } catch (IOException e) {
            Log.error("[conn] " + e.getMessage());
        }
    }
}
