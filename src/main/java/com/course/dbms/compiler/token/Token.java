package com.course.dbms.compiler.token;

/**
 * 一个词法单元。
 * text   —— 原始词面（如 "select"、"123"、"'abc'"）。
 * value  —— 字面量解析后的 Java 值（NUMBER->Long/Double, STRING->String,
 *           TRUE/FALSE->Boolean, 其余为 null）。
 * pos    —— 在原始 SQL 中的字符偏移，用于错误定位。
 */
public class Token {
    public final TokenType type;
    public final String text;
    public final Object value;
    public final int pos;

    public Token(TokenType type, String text, Object value, int pos) {
        this.type = type;
        this.text = text;
        this.value = value;
        this.pos = pos;
    }

    @Override public String toString() {
        return type + "(" + text + ")";
    }
}
