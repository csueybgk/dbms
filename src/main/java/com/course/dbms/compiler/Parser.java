package com.course.dbms.compiler;

import com.course.dbms.common.Error;
import com.course.dbms.compiler.ast.ColRef;
import com.course.dbms.compiler.ast.Cond;
import com.course.dbms.compiler.ast.CreateIndexStmt;
import com.course.dbms.compiler.ast.CreateStmt;
import com.course.dbms.compiler.ast.InsertStmt;
import com.course.dbms.compiler.ast.DeleteStmt;
import com.course.dbms.compiler.ast.UpdateStmt;
import com.course.dbms.compiler.ast.SelectItem;
import com.course.dbms.compiler.ast.SelectStmt;
import com.course.dbms.compiler.ast.ShowStmt;
import com.course.dbms.compiler.ast.Stmt;
import com.course.dbms.compiler.ast.TableRef;
import com.course.dbms.compiler.ast.TxOp;
import com.course.dbms.compiler.ast.TxnStmt;
import com.course.dbms.compiler.token.Token;
import com.course.dbms.compiler.token.TokenType;
import com.course.dbms.engine.table.Column;
import com.course.dbms.engine.table.FieldType;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归下降语法分析器：把 token 流构建成语法树。
 * 支持四类语句：create table / insert / select / show。
 * 对应图片"① SQL编译器 - 语法分析：构建语法树，支持四类语句"。
 *
 * 文法（附录在 三大模块详解.md）：
 *   stmt     := create | insert | select | show
 *   create   := CREATE TABLE IDENT '(' colDef (',' colDef)* ')'
 *             | CREATE INDEX IDENT ON IDENT '(' IDENT ')'
 *   colDef   := IDENT TYPE      (TYPE ∈ int32/int64/float64/bool/string/datetime)
 *   insert   := INSERT INTO IDENT VALUES '(' value (',' value)* ')'
 *   select   := SELECT '*' | selItem (',' selItem)*
 *              FROM table (join)* [WHERE cond] [GROUP BY colRef (',' colRef)*]
 *              [ORDER BY orderKey [ASC|DESC]]
 *   selItem  := colRef [AS IDENT] | aggFunc '(' [*|colRef] ')' [AS IDENT]
 *   table    := IDENT [AS IDENT]                （隐式别名 IDENT 亦可）
 *   join     := [INNER|LEFT] JOIN table [ON cond]
 *   orderKey := colRef | aggFunc '(' [*|colRef] ')'   （聚合排序只取函数名）
 *   colRef   := IDENT | IDENT '.' IDENT
 *   cond     := cmp (OR cmp)* ; cmp := cmp' (AND cmp')* ; cmp' := colRef op (colRef|value)
 *   show     := SHOW TABLES | SHOW TABLE IDENT | SHOW INDEX[ES] [ON IDENT]
 */
public class Parser {

    private final List<Token> tokens;
    private int pos = 0;

    public Parser(String sql) {
        this.tokens = new Lexer(sql).tokenize();
    }

    public Stmt parse() {
        Stmt s = parseStatement();
        // 允许句尾一个或多个分号
        while (peek().type == TokenType.SEMICOLON) next();
        expect(TokenType.EOF);
        return s;
    }

    private Stmt parseStatement() {
        switch (peek().type) {
            case CREATE:   return parseCreate();
            case INSERT:   return parseInsert();
            case DELETE:   return parseDelete();
            case UPDATE:   return parseUpdate();
            case SELECT:   return parseSelect();
            case SHOW:     return parseShow();
            case BEGIN:    return parseTx(TxOp.BEGIN);
            case COMMIT:   return parseTx(TxOp.COMMIT);
            case ROLLBACK: return parseTx(TxOp.ROLLBACK);
            default: throw err(peek(), "expected statement, got " + peek().text);
        }
    }

    /** 事务控制语句（由 Session 拦截处理，这里只做语法识别）。 */
    private Stmt parseTx(TxOp op) {
        TokenType kw = op == TxOp.BEGIN ? TokenType.BEGIN
                     : op == TxOp.COMMIT ? TokenType.COMMIT : TokenType.ROLLBACK;
        expect(kw);
        // 可选词：BEGIN [TRANSACTION|WORK]、COMMIT [WORK]、ROLLBACK [WORK]
        if (peek().type == TokenType.IDENTIFIER) {
            String w = String.valueOf(peek().value).toLowerCase();
            if (w.equals("transaction") || w.equals("work")) next();
        }
        // ROLLBACK TO SAVEPOINT（本期不支持，明确报错而非静默吞掉）
        if (op == TxOp.ROLLBACK && peek().type == TokenType.IDENTIFIER
                && String.valueOf(peek().value).equalsIgnoreCase("to")) {
            throw err(peek(), "ROLLBACK TO SAVEPOINT is not supported");
        }
        return new TxnStmt(op);
    }

    private Stmt parseCreate() {
        expect(TokenType.CREATE);
        if (match(TokenType.INDEX)) return parseCreateIndex();
        expect(TokenType.TABLE);
        String name = expectIdent();
        expect(TokenType.LPAREN);
        List<Column> cols = new ArrayList<>();
        do {
            String colName = expectIdent();
            String typeName = expectIdent();
            FieldType ft = FieldType.fromName(typeName);   // 非法类型抛 SE-0002
            cols.add(new Column(colName, ft));
        } while (match(TokenType.COMMA));
        expect(TokenType.RPAREN);
        return new CreateStmt(name, cols);
    }

    /** CREATE INDEX <名> ON <表> '(' <列> ')' —— 单列索引。 */
    private Stmt parseCreateIndex() {
        String indexName = expectIdent();
        expect(TokenType.ON);
        String table = expectIdent();
        expect(TokenType.LPAREN);
        String column = expectIdent();
        expect(TokenType.RPAREN);
        return new CreateIndexStmt(indexName, table, column);
    }

    private Stmt parseInsert() {
        expect(TokenType.INSERT);
        expect(TokenType.INTO);
        String name = expectIdent();
        expect(TokenType.VALUES);
        expect(TokenType.LPAREN);
        List<Object> values = new ArrayList<>();
        do {
            values.add(expectLiteral());
        } while (match(TokenType.COMMA));
        expect(TokenType.RPAREN);
        return new InsertStmt(name, values);
    }

    private Stmt parseDelete() {
        expect(TokenType.DELETE);
        expect(TokenType.FROM);
        String table = expectIdent();
        return new DeleteStmt(table, match(TokenType.WHERE) ? parseCond() : null);
    }

    private Stmt parseUpdate() {
        expect(TokenType.UPDATE);
        String table = expectIdent();
        expect(TokenType.SET);
        java.util.Map<String, Object> assignments = new java.util.LinkedHashMap<>();
        do {
            String column = expectIdent().toLowerCase(java.util.Locale.ROOT);
            Token op = expect(TokenType.OPERATOR);
            if (!"=".equals(op.value)) throw err(op, "expected = in SET");
            if (assignments.containsKey(column)) throw err(op, "duplicate assignment: " + column);
            assignments.put(column, expectLiteral());
        } while (match(TokenType.COMMA));
        return new UpdateStmt(table, match(TokenType.WHERE) ? parseCond() : null, assignments);
    }

    private Stmt parseSelect() {
        expect(TokenType.SELECT);
        boolean all = false;
        List<String> cols = new ArrayList<>();       // 旧路径用的裸列名
        List<SelectItem> items = new ArrayList<>();
        if (match(TokenType.STAR)) {
            all = true;
        } else {
            do {
                SelectItem it = parseSelectItem();
                items.add(it);
                if (it.isAgg()) cols.add(it.alias != null ? it.alias : it.func);
                else cols.add(it.col.name);
            } while (match(TokenType.COMMA));
        }
        expect(TokenType.FROM);
        List<TableRef> from = new ArrayList<>();
        from.add(tableRef());                        // 首表 + 可选别名
        List<Cond> joinOn = new ArrayList<>();
        List<Boolean> joinOuter = new ArrayList<>();
        while (true) {
            boolean left = false;
            if (match(TokenType.LEFT)) {
                left = true;
                expect(TokenType.JOIN);
            } else if (match(TokenType.INNER)) {
                expect(TokenType.JOIN);
            } else if (match(TokenType.JOIN)) {
                // 裸 JOIN = 内连接
            } else {
                break;
            }
            from.add(tableRef());
            Cond on = match(TokenType.ON) ? parseCond() : null;
            if (left && on == null) throw err(peek(), "LEFT JOIN 需要 ON 条件");
            joinOn.add(on);
            joinOuter.add(Boolean.valueOf(left));
        }
        Cond where = null;
        if (match(TokenType.WHERE)) where = parseCond();
        List<ColRef> groupBy = new ArrayList<>();
        if (match(TokenType.GROUP)) {
            expect(TokenType.BY);
            do { groupBy.add(parseColRef()); } while (match(TokenType.COMMA));
        }
        String orderBy = null;
        boolean desc = false;
        if (match(TokenType.ORDER)) {
            expect(TokenType.BY);
            orderBy = parseOrderKey();
            if (match(TokenType.ASC)) { /* 默认升序 */ }
            else if (match(TokenType.DESC)) desc = true;
        }
        return new SelectStmt(all, cols, from.get(0).name, where, orderBy, desc,
                from, joinOn, joinOuter, items, groupBy.isEmpty() ? null : groupBy);
    }

    /** SELECT 列表的一项：列（可限定）或聚合函数调用。 */
    private SelectItem parseSelectItem() {
        String first = expectIdent();
        if (peek().type == TokenType.LPAREN) {          // ident '(' [*|ident] ')' 视为聚合
            String func = first.toLowerCase();
            if (!isAggFunc(func)) throw err(peek(), "unknown function: " + first);
            expect(TokenType.LPAREN);
            ColRef arg = null;
            if (match(TokenType.STAR)) { arg = null; }   // COUNT(*)
            else arg = parseColRef();
            expect(TokenType.RPAREN);
            return SelectItem.agg(func, arg, optionalAlias());
        }
        ColRef col = match(TokenType.DOT) ? new ColRef(first, expectIdent()) : new ColRef(null, first);
        return SelectItem.col(col, optionalAlias());
    }

    private TableRef tableRef() {
        String name = expectIdent();
        String alias = null;
        if (match(TokenType.AS)) alias = expectIdent();
        else if (peek().type == TokenType.IDENTIFIER) alias = expectIdent(); // 隐式别名 t a
        return new TableRef(name, alias);
    }

    private ColRef parseColRef() {
        String first = expectIdent();
        if (match(TokenType.DOT)) return new ColRef(first, expectIdent());
        return new ColRef(null, first);
    }

    private String optionalAlias() {
        if (match(TokenType.AS)) return expectIdent();
        if (peek().type == TokenType.IDENTIFIER) return expectIdent(); // 隐式别名
        return null;
    }

    /** ORDER BY 的排序键：列名 / 限定列 / 聚合输出名（count(*) 之类只取函数名）。 */
    private String parseOrderKey() {
        String name = expectIdent();
        if (match(TokenType.DOT)) name = name + "." + expectIdent();
        if (match(TokenType.LPAREN)) {
            if (match(TokenType.STAR)) { /* 忽略参数 */ }
            else expectIdent();
            expect(TokenType.RPAREN);
        }
        return name;
    }

    private boolean isAggFunc(String lower) {
        return lower.equals("count") || lower.equals("sum") || lower.equals("avg")
                || lower.equals("min") || lower.equals("max");
    }

    private Stmt parseShow() {
        expect(TokenType.SHOW);
        if (match(TokenType.TABLES)) return new ShowStmt(true, null);
        // show index/indexes [on <表>]：不带 ON 即列出全部
        if (match(TokenType.INDEXES) || match(TokenType.INDEX)) {
            String t = match(TokenType.ON) ? expectIdent() : null;
            return ShowStmt.indexes(t);
        }
        expect(TokenType.TABLE);
        return new ShowStmt(false, expectIdent());
    }

    // ---- WHERE 条件 -----

    private Cond parseCond() {
        return parseOr();
    }
    private Cond parseOr() {
        List<Cond> cs = new ArrayList<>();
        cs.add(parseAnd());
        while (match(TokenType.OR)) cs.add(parseAnd());
        return cs.size() > 1 ? Cond.or(cs) : cs.get(0);
    }
    private Cond parseAnd() {
        List<Cond> cs = new ArrayList<>();
        cs.add(parseCmp());
        while (match(TokenType.AND)) cs.add(parseCmp());
        return cs.size() > 1 ? Cond.and(cs) : cs.get(0);
    }
    private Cond parseCmp() {
        ColRef lhs = parseColRef();
        Token opTok = expect(TokenType.OPERATOR);
        String op = (String) opTok.value;
        // 右操作数：列引用（列 vs 列，见于 JOIN ON）或字面量
        if (peek().type == TokenType.IDENTIFIER) {
            ColRef rhs = parseColRef();
            return Cond.cmp2(lhs.qualifier, lhs.name, op, rhs.qualifier, rhs.name);
        }
        Object val = expectLiteral();
        return Cond.cmp(lhs.qualifier, lhs.name, op, val);
    }

    private Object expectLiteral() {
        Token t = next();
        switch (t.type) {
            case NUMBER:  return t.value;
            case STR_LIT: return t.value;
            case TRUE:    return Boolean.TRUE;
            case FALSE:   return Boolean.FALSE;
            default: throw err(t, "expected literal, got " + t.text);
        }
    }

    // ---- 工具 ----

    private Token peek() { return tokens.get(pos); }
    private Token next() { Token t = tokens.get(pos); pos++; return t; }
    private boolean match(TokenType tt) {
        if (peek().type == tt) { pos++; return true; }
        return false;
    }
    private Token expect(TokenType tt) {
        if (peek().type != tt) throw err(peek(), "expected " + tt + " but got " + peek().text);
        return next();
    }
    private String expectIdent() {
        Token t = expect(TokenType.IDENTIFIER);
        return (String) t.value;
    }

    private Error err(Token t, String msg) {
        return new Error("SY-0001", msg + " at " + t.pos);
    }
}
