package com.course.dbms.common;

/**
 * 统一错误。带错误码，方便客户端/用户定位问题。
 * 错误码形如 "TB-0001"，前缀代表模块：
 *   ST 存储层；CS 缓存层；TB 表；CT 目录；LX 词法；SY 语法；SE 语义；PL 计划；EX 执行；SV 服务端。
 */
public class Error extends RuntimeException {

    private final String code;

    public Error(String code, String message) {
        super(message);
        this.code = code;
    }

    public Error(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 拼接最简展示：✗ [CODE] message */
    @Override
    public String toString() {
        return "✗ [" + code + "] " + getMessage();
    }

    // 常用构造简写，避免写死错误码时遗漏
    public static Error st(String m) { return new Error("ST-0001", m); }
    public static Error cs(String m) { return new Error("CS-0001", m); }
    public static Error tb(String m) { return new Error("TB-0001", m); }
    public static Error ct(String m) { return new Error("CT-0001", m); }
}
