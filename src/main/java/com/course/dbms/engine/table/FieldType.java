package com.course.dbms.engine.table;

import com.course.dbms.common.Error;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 字段类型。每种类型负责一段值与字节数组的互转。
 *
 * 固定长度类型编码为固定字节数；STRING 变长，依赖外层长度前缀。
 * Java 侧值类型：INT32->Integer, INT64->Long, FLOAT64->Double,
 *               BOOL->Boolean, STRING->String, DATETIME->Long(毫秒时间戳)。
 */
public enum FieldType {

    INT32(1),
    INT64(2),
    FLOAT64(3),
    BOOL(4),
    STRING(5),
    DATETIME(6);

    private final byte tag;

    FieldType(int tag) { this.tag = (byte) tag; }

    public byte tag() { return tag; }

    public boolean variable() { return this == STRING; }

    /** 类型名（小写，SQL 里用的名字）。 */
    public String sqlName() {
        return name().toLowerCase();
    }

    public byte[] encode(Object v) {
        switch (this) {
            case INT32: return ByteBuffer.allocate(4).putInt((Integer) v).array();
            case INT64: return ByteBuffer.allocate(8).putLong((Long) v).array();
            case FLOAT64: return ByteBuffer.allocate(8).putDouble((Double) v).array();
            case BOOL: return new byte[] { (byte) ((Boolean) v ? 1 : 0) };
            case STRING: return ((String) v).getBytes(StandardCharsets.UTF_8);
            case DATETIME: return ByteBuffer.allocate(8).putLong((Long) v).array();
            default: throw Error.st("unknown type encode: " + this);
        }
    }

    /** 从字节解码（len 为实际长度；固定类型忽略 len，变长用 len）。 */
    public Object decode(byte[] buf, int off, int len) {
        switch (this) {
            case INT32: return ByteBuffer.wrap(buf, off, 4).getInt();
            case INT64: return ByteBuffer.wrap(buf, off, 8).getLong();
            case FLOAT64: return ByteBuffer.wrap(buf, off, 8).getDouble();
            case BOOL: return buf[off] != 0;
            case STRING: return new String(buf, off, len, StandardCharsets.UTF_8);
            case DATETIME: return ByteBuffer.wrap(buf, off, 8).getLong();
            default: throw Error.st("unknown type decode: " + this);
        }
    }

    /**
     * 把 DEFAULT 约束里保存的字面量【原文】还原成本类型的 Java 值。
     * 存原文而不是存 "String.valueOf(值)" 是有意的：这样 STRING 列的默认值
     * 'null'（四个字符）与 DEFAULT NULL 不会混淆，且与 SQL 里写的形态一致。
     *
     * 原文形态：{@code 'abc'} 字符串字面量（带引号）、{@code 123} 数值、
     * {@code true}/{@code false} 布尔、{@code NULL} 空值。
     * 与列类型不匹配（如给 int32 写 1.5）抛 SE-0005 —— 注意不能只靠
     * {@link #encode} 或 StorageEngine.cast 兜底：cast 会把 1.5 静默截断成 1。
     */
    public Object parseLiteral(String text) {
        if (text == null || text.equalsIgnoreCase("null")) return null;
        String t = text.trim();
        // 字符串字面量：词法器产出的 STR_LIT 原文一定带引号（且不支持转义，内部不可能有 '）
        if (t.length() >= 2 && t.charAt(0) == '\'' && t.charAt(t.length() - 1) == '\'') {
            if (this != STRING) throw mismatch(text);
            return t.substring(1, t.length() - 1);
        }
        try {
            switch (this) {
                case INT32: {
                    long n = Long.parseLong(t);
                    if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) throw mismatch(text);
                    return (int) n;
                }
                case INT64:
                case DATETIME: return Long.parseLong(t);
                case FLOAT64:  return Double.parseDouble(t);
                case BOOL:
                    if (t.equalsIgnoreCase("true")) return Boolean.TRUE;
                    if (t.equalsIgnoreCase("false")) return Boolean.FALSE;
                    throw mismatch(text);
                case STRING: return t;               // 宽容：未加引号的文本也当字符串
                default: throw Error.st("unknown type parseLiteral: " + this);
            }
        } catch (NumberFormatException e) {
            throw mismatch(text);
        }
    }

    private Error mismatch(String text) {
        return new Error("SE-0005", "默认值 " + text + " 与列类型 " + sqlName() + " 不匹配");
    }

    public static FieldType fromName(String name) {
        for (FieldType t : values()) {
            if (t.name().equalsIgnoreCase(name)) return t;
        }
        throw new Error("SE-0002", "unknown type: " + name);
    }

    public static FieldType fromTag(byte tag) {
        for (FieldType t : values()) {
            if (t.tag == tag) return t;
        }
        throw new Error("SE-0003", "unknown field tag: " + tag);
    }
}
