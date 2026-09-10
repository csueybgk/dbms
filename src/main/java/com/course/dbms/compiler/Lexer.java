package com.course.dbms.compiler;

import com.course.dbms.common.Error;
import com.course.dbms.compiler.token.Token;
import com.course.dbms.compiler.token.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * 词法分析器：把 SQL 文本切成 token 流。
 * 识别：关键字、标识符、数字常量（整型/浮点）、字符串常量、比较运算符、括号逗号分号。
 * 对应图片"① SQL编译器 - 词法分析：识别关键字、标识符、常量、运算符"。
 */
public class Lexer {

    private final String sql;
    private int i = 0;

    public Lexer(String sql) {
        this.sql = sql;
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            Token t = next();
            tokens.add(t);
            if (t.type == TokenType.EOF) break;
        }
        return tokens;
    }

    private Token next() {
        skipWhitespace();
        if (i >= sql.length()) return tok(TokenType.EOF, "", null, i);

        char c = sql.charAt(i);
        int start = i;

        // 标识符或关键字
        if (Character.isLetter(c) || c == '_') {
            while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
            String word = sql.substring(start, i);
            TokenType kt = TokenType.fromKeyword(word);
            if (kt != null) return tok(kt, word, boolValue(kt), start);
            return tok(TokenType.IDENTIFIER, word, word, start);
        }

        // 数字
        if (Character.isDigit(c)) {
            while (i < sql.length() && Character.isDigit(sql.charAt(i))) i++;
            boolean isFloat = false;
            if (i < sql.length() && sql.charAt(i) == '.' && i + 1 < sql.length() && Character.isDigit(sql.charAt(i + 1))) {
                isFloat = true;
                i++; // '.'
                while (i < sql.length() && Character.isDigit(sql.charAt(i))) i++;
            }
            String num = sql.substring(start, i);
            // 注意：不能用三元表达式。Double/Long 都是数值包装类型，条件运算符会做
            // 二元数值提升（拆箱加宽）导致整数也被转成 double。用 if/else 保留原类型。
            Object val;
            if (isFloat) val = Double.valueOf(num);
            else val = Long.valueOf(num);
            return tok(TokenType.NUMBER, num, val, start);
        }

        // 字符串字面量（单引号，允许多行；不支持转义，' 用 ' 结束）
        if (c == '\'') {
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < sql.length() && sql.charAt(i) != '\'') {
                sb.append(sql.charAt(i));
                i++;
            }
            if (i >= sql.length()) throw err(start, "unterminated string literal");
            i++; // skip closing quote
            return tok(TokenType.STR_LIT, "'" + sb + "'", sb.toString(), start);
        }

        // 运算符
        if (c == '=' || c == '<' || c == '>' || c == '!' ) {
            if (c == '!' && i + 1 < sql.length() && sql.charAt(i + 1) == '=') { i += 2; return tok(TokenType.OPERATOR, "!=", "<>", start); }
            if (c == '<' && i + 1 < sql.length() && sql.charAt(i + 1) == '=') { i += 2; return tok(TokenType.OPERATOR, "<=", "<=", start); }
            if (c == '<' && i + 1 < sql.length() && sql.charAt(i + 1) == '>') { i += 2; return tok(TokenType.OPERATOR, "<>", "<>", start); }
            if (c == '>' && i + 1 < sql.length() && sql.charAt(i + 1) == '=') { i += 2; return tok(TokenType.OPERATOR, ">=", ">=", start); }
            i++;
            return tok(TokenType.OPERATOR, String.valueOf(c), String.valueOf(c), start);
        }

        // 标点
        switch (c) {
            case '(': i++; return tok(TokenType.LPAREN, "(", null, start);
            case ')': i++; return tok(TokenType.RPAREN, ")", null, start);
            case ',': i++; return tok(TokenType.COMMA, ",", null, start);
            case ';': i++; return tok(TokenType.SEMICOLON, ";", null, start);
            case '*': i++; return tok(TokenType.STAR, "*", null, start);
            case '.': i++; return tok(TokenType.DOT, ".", null, start);
            default: throw err(start, "unexpected character '" + c + "'");
        }
    }

    /** TRUE/FALSE 关键字直接生成布尔值。 */
    private Object boolValue(TokenType kt) {
        if (kt == TokenType.TRUE) return Boolean.TRUE;
        if (kt == TokenType.FALSE) return Boolean.FALSE;
        return null;
    }

    private void skipWhitespace() {
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) i++;
    }

    private Token tok(TokenType type, String text, Object value, int pos) {
        return new Token(type, text, value, pos);
    }

    private Error err(int pos, String msg) {
        return new Error("LX-0001", msg + " at " + pos);
    }
}
