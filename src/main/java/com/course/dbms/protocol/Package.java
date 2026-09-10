package com.course.dbms.protocol;

/**
 * 通信包：一个错误标志 + 一段负载（UTF-8 字节）。
 * 客户端->服务端：payload 为 SQL 文本；服务端->客户端：payload 为结果（Result）或错误信息。
 */
public class Package {

    public final boolean error;
    public final byte[] payload;

    public Package(boolean error, byte[] payload) {
        this.error = error;
        this.payload = payload;
    }
}
