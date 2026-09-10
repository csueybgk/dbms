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
