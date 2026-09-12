package com.course.dbms.client;

import com.course.dbms.protocol.Decoder;
import com.course.dbms.protocol.Package;
import com.course.dbms.protocol.Packager;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

/**
 * 客户端交互 shell。
 * 交互模式：逐行读入，累积到"分号"才发送；支持 \dt / \d <t> / \trace / \q。
 * 批处理：-e SQL 立即执行；-f 文件逐条执行；-t 让每条语句附带编译四阶段输出。
 * 用一个连接持续往返（真正体现客户端/服务器端交互）。
 */
public class Shell {

    private final DataInputStream in;
    private final DataOutputStream out;
    private final BufferedReader console;
    private final StringBuilder buf = new StringBuilder();
    private boolean traceOn = false;

    public Shell(Socket socket) throws IOException {
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        this.console = new BufferedReader(new InputStreamReader(System.in));
    }

    /** 批处理开关：true 时之后发送的每条 SQL 都附带编译四阶段输出（trace 前缀）。 */
    public void setTrace(boolean on) { this.traceOn = on; }

    /** 交互 REPL。 */
    public void run() throws IOException {
        System.out.println(banner());
        while (true) {
            System.out.print(pending() ? "     " : "dbms> ");
            System.out.flush();
            String line = console.readLine();
            if (line == null) break;                       // EOF
            String t = line.trim();
            if (!pending()) {
                if (t.isEmpty()) continue;
                if (t.equals("\\q") || t.equals("\\quit") || t.equals("exit")) {
                    System.out.println("bye");
                    break;
                }
                if (t.equals("\\dt")) { send("show tables"); continue; }
                if (t.startsWith("\\d ")) { send("show table " + t.substring(3).trim()); continue; }
                if (t.equals("\\trace")) {
                    traceOn = !traceOn;
                    System.out.println("trace " + (traceOn ? "on" : "off"));
                    continue;
                }
                if (t.startsWith("\\")) { System.out.println("unknown command: " + t); continue; }
            }
            buf.append(line).append('\n');
            drain();
        }
    }

    /** 批处理 -e：执行一条 SQL。 */
    public void execute(String sql) throws IOException {
        buf.setLength(0);
        buf.append(sql).append('\n');
        drain();
        // 若没有分号结尾，把剩余当成一条语句执行
        if (buf.toString().trim().length() > 0) {
            send(buf.toString().trim());
            buf.setLength(0);
        }
    }

    /** 批处理 -f：执行文件中所有语句。 */
    public void executeFile(String path) throws IOException {
        String content = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        buf.setLength(0);
        buf.append(content).append('\n');
        drain();
        if (buf.toString().trim().length() > 0) {
            send(buf.toString().trim());
            buf.setLength(0);
        }
    }

    /** 发送一条完整语句并渲染结果；开 trace 时先回显编译四阶段输出再渲染结果。 */
    private void send(String sql) throws IOException {
        if (traceOn) sql = "trace " + sql;
        Packager.write(out, new Package(false, sql.getBytes(StandardCharsets.UTF_8)));
        while (true) {
            Package pkg = Packager.read(in);
            if (pkg.trace) {
                // 编译四阶段中间输出：Token流 -> AST -> 语义 -> 计划
                System.out.println(new String(pkg.payload, StandardCharsets.UTF_8));
                continue;
            }
            if (pkg.error) {
                System.out.println(new String(pkg.payload, StandardCharsets.UTF_8));
            } else {
                Renderer.render(Decoder.decodeResult(pkg.payload));
            }
            break;
        }
    }

    /** 把缓冲里所有到分号为止的完整语句抽出并发送。 */
    private void drain() throws IOException {
        String text = buf.toString();
        int idx = topLevelSemicolon(text);
        while (idx >= 0) {
            String stmt = text.substring(0, idx + 1).trim();
            text = text.substring(idx + 1);
            buf.setLength(0);
            buf.append(text);
            if (!stmt.isEmpty()) send(stmt);
            idx = topLevelSemicolon(buf.toString());
        }
        // 纯空白残留（如末尾换行）清掉，避免误判"仍在续行"
        if (buf.toString().trim().isEmpty()) buf.setLength(0);
    }

    /** 是否真有未提交的内容（非空白）。用于决定提示符是 dbms> 还是续行缩进。 */
    private boolean pending() {
        return buf.toString().trim().length() > 0;
    }

    /** 找第一个"不在单引号字符串内"的分号下标；无则 -1。 */
    private int topLevelSemicolon(String s) {
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') inQuote = !inQuote;
            else if (c == ';' && !inQuote) return i;
        }
        return -1;
    }

    private String banner() {
        return "DBMS client -- type SQL, end with ';'.  \\dt  \\d <table>  \\trace  \\q";
    }
}
