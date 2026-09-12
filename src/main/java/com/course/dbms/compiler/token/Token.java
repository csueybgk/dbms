package com.course.dbms.compiler.token;

/**
 * 一个词法单元。
 * text   —— 原始词面（如 "select"、"123"、"'abc'"）。
 * value  —— 字面量解析后的 Java 值（NUMBER->Long/Double, STRING->String,
 *           TRUE/FALSE->Boolean, 其余为 null）。
 * pos    —— 在原始 SQL 中的字符偏移。
 * line   —— 1-based 行号；column —— 1-based 列号。
 *          对应指导书 Token 四元式输出 [种别码, 词素值, 行号, 列号]。
 */
public class Token {
    public final TokenType type;
    public final String text;
    public final Object value;
    public final int pos;
    public final int line;
    public final int column;

    /** 完整构造：带行号、列号（Lexer 产出一律走这里）。 */
    public Token(TokenType type, String text, Object value, int pos, int line, int column) {
        this.type = type;
        this.text = text;
        this.value = value;
        this.pos = pos;
        this.line = line;
        this.column = column;
    }

    /** 兼容旧调用：只有字符偏移时行列号记 -1（未知）。 */
    public Token(TokenType type, String text, Object value, int pos) {
        this(type, text, value, pos, -1, -1);
    }

    @Override public String toString() {
        return type + "(" + text + ")";
    }
}
