package com.course.dbms.compiler.token;

/**
 * 词法单元类型。
 * 保留字用一个枚举常量（CREATE/TABLE/...），识别时一眼看懂；类型名（int32 等）
 * 保留为 IDENTIFIER，由语义层通过 FieldType.fromName 识别，减少枚举膨胀。
 */
public enum TokenType {
    // 关键字
    DELETE, UPDATE, SET, CREATE, TABLE, INSERT, INTO, VALUES, SELECT, FROM,
    WHERE, AND, OR, NOT, ORDER, BY, ASC, DESC, SHOW, TABLES,
    JOIN, ON, AS, INNER, LEFT, GROUP,    // 多表联查 / 聚合
    INDEX, INDEXES,            // 索引
    TRUE, FALSE,
    BEGIN, COMMIT, ROLLBACK,   // 事务控制

    // 基本单元
    IDENTIFIER,        // 标识符 / 类型名（含 int32/string 等）
    NUMBER,            // 整数或浮点数常量
    STR_LIT,           // 单引号字符串字面量（不与类型名 string 冲突）
    OPERATOR,          // = <> < > <= >=

    // 结构标点
    LPAREN, RPAREN, COMMA, SEMICOLON, STAR, DOT,

    EOF;

    /** 由小写关键字文本反查；非关键字返回 null。 */
    public static TokenType fromKeyword(String lower) {
        for (TokenType t : values()) {
            if (t.name().equalsIgnoreCase(lower)) return t;
        }
        return null;
    }
}
