package com.course.dbms.common;

/**
 * 极简日志工具。零第三方依赖，统一输出到 stdout。
 * 用于缓存命中统计、页读写等运行日志，便于演示。
 */
public final class Log {

    private Log() {}

    public enum Level { DEBUG, INFO, WARN, ERROR }

    private static Level level = Level.INFO;

    public static void setLevel(Level l) { level = l; }

    public static void debug(String msg) { log(Level.DEBUG, msg); }
    public static void info(String msg)  { log(Level.INFO, msg); }
    public static void warn(String msg)  { log(Level.WARN, msg); }
    public static void error(String msg) { log(Level.ERROR, msg); }

    private static void log(Level l, String msg) {
        if (l.ordinal() < level.ordinal()) return;
        System.out.println(String.format("[%s] %s", l, msg));
    }
}
