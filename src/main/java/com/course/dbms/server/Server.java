package com.course.dbms.server;

import com.course.dbms.common.Log;
import com.course.dbms.db.Database;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

/**
 * TCP 服务端：监听端口，每来一个连接派一个线程用 {@link ConnectionHandler} 处理。
 * 用独立线程运行（start()），并支持 close() 优雅关闭（用于测试）。
 */
public class Server implements Runnable, Closeable {

    private final Database db;
    private final ServerSocket ss;
    private final List<Thread> handlers = new ArrayList<>();

    public Server(Database db, int port) throws IOException {
        this.db = db;
        this.ss = new ServerSocket(port);
    }

    /** 当前绑定的实际端口（端口传 0 时自动分配）。 */
    public int port() { return ss.getLocalPort(); }

    public void start() {
        Thread t = new Thread(this, "dbms-server");
        t.setDaemon(true);
        t.start();
    }

    @Override public void run() {
        Log.info("[SERVER] listening on " + ss.getInetAddress() + ":" + ss.getLocalPort());
        try {
            while (!ss.isClosed()) {
                Socket s = ss.accept();
                Log.info("[SERVER] client connected: " + s.getRemoteSocketAddress());
                Thread th = new Thread(new ConnectionHandler(db, s), "dbms-client");
                th.setDaemon(true);
                th.start();
                handlers.add(th);
            }
        } catch (SocketException ignored) {
            // 关闭引起的
        } catch (IOException e) {
            Log.error("[SERVER] accept error: " + e.getMessage());
        }
    }

    @Override public void close() throws IOException {
        ss.close();
    }
}
