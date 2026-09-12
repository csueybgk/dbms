package com.course.dbms.protocol;

/**
 * 通信包：标志位 + 一段负载（UTF-8 字节）。
 * 客户端->服务端：payload 为 SQL 文本；
 * 服务端->客户端：payload 为结果（Result）、错误信息，或 trace 中间输出
 * （trace 包在最终结果/错误包之前发送，客户端收到后原样打印再继续读）。
 */
public class Package {

    public final boolean error;
    public final boolean trace;
    public final byte[] payload;

    public Package(boolean error, byte[] payload) {
        this(error, false, payload);
    }

    public Package(boolean error, boolean trace, byte[] payload) {
        this.error = error;
        this.trace = trace;
        this.payload = payload;
    }
}
