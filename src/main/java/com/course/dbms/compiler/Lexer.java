package com.course.dbms.compiler;

import com.course.dbms.common.Error;
import com.course.dbms.compiler.token.Token;
import com.course.dbms.compiler.token.TokenType;

import java.util.ArrayList;
import java.util.List;


public class Lexer {

    private final String sql;
    private int i = 0;

    // 行列游标：只前进不后退，多次调用总计 O(n)
    private int line = 1;
    private int lineStart = 0;   // 当前行首字符在 sql 中的 offset

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
        skipWhitespaceAndComments();
        if (i >= sql.length()) {
            int[] lc = lineCol(i);
            return new Token(TokenType.EOF, "", null, i, lc[0], lc[1]);
        }

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

    private void skipWhitespaceAndComments() {
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) throw err(i, "unterminated block comment");
                i = end + 2;
                continue;
            }
            break;
        }
    }

    /** offset -> [1-based 行号, 1-based 列号]。游标只前进，多次调用总计 O(n)。 */
    private int[] lineCol(int offset) {
        while (lineStart < offset) {
            if (sql.charAt(lineStart) == '\n') line++;
            lineStart++;
        }
        return new int[] { line, offset - lineStart + 1 };
    }

    private Token tok(TokenType type, String text, Object value, int pos) {
        int[] lc = lineCol(pos);
        return new Token(type, text, value, pos, lc[0], lc[1]);
    }

    /** 词法错误：错误类型 + 原因 + 行号列号定位（指导书要求）。 */
    private Error err(int pos, String msg) {
        int[] lc = lineCol(pos);
        return new Error("LX-0001", msg + " at line " + lc[0] + ", column " + lc[1]);
    }
}
