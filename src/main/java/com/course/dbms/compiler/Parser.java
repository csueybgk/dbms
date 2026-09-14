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
import com.course.dbms.engine.table.Constraint;
import com.course.dbms.engine.table.FieldType;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归下降语法分析器：把 token 流构建成语法树。
 * 支持四类语句：create table / insert / select / show（另有 delete / update / 事务控制）。
 * 对应图片"① SQL编译器 - 语法分析：构建语法树，支持四类语句"。
 *
 * 语法错误诊断（课程验收要点）：错误信息 = unexpected token + 期望符号集合 + 行号列号，例如
 *   unexpected token: ';', expected: IDENTIFIER at line 1, column 38
 *
 * 文法（附录在 三大模块详解.md）：
 *   stmt     := create | insert | select | show
 *   create   := CREATE TABLE IDENT '(' (colDef | tableConstraint) (',' ...)* ')'
 *             | CREATE INDEX IDENT ON IDENT '(' IDENT ')'
 *   colDef   := IDENT TYPE colConstraint*   (TYPE ∈ int32/int64/float64/bool/string/datetime)
 *   colConstraint := [CONSTRAINT IDENT] ( NOT NULL | NULL | PRIMARY KEY | UNIQUE
 *                                       | DEFAULT (value|NULL) | CHECK '(' cond ')' )
 *   tableConstraint := [CONSTRAINT IDENT] ( PRIMARY KEY '(' IDENT,... ')'
 *                                         | UNIQUE      '(' IDENT,... ')'
 *                                         | CHECK '(' cond ')' )
 *   insert   := INSERT INTO IDENT [ '(' IDENT (',' IDENT)* ')' ]
 *               VALUES '(' value (',' value)* ')'      (value 可为 NULL)
 *
 * 注意：NULL/DEFAULT/PRIMARY/KEY/UNIQUE/CHECK/CONSTRAINT 成为保留字后不可再用作列名或表名
 * （本项目没有转义标识符语法）。
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

    /** 从 SQL 文本解析：内部先做一次词法分析。 */
    public Parser(String sql) {
        this(new Lexer(sql).tokenize());
    }

    /** 从已有 Token 流解析（Tracer 复用词法结果，避免重复切词）。 */
    public Parser(List<Token> tokens) {
        this.tokens = tokens;
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
            default: throw err(peek(), "unexpected token: '" + peek().text
                    + "', expected statement: CREATE | INSERT | SELECT | DELETE | UPDATE | SHOW | BEGIN | COMMIT | ROLLBACK");
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
        List<Constraint> cons = new ArrayList<>();
        do {
            // 括号里的每一项要么是列定义，要么是表级约束 —— 靠首个 token 区分
            if (isConstraintStart(peek().type)) parseTableConstraint(cons);
            else parseColumnDef(cols, cons);
        } while (match(TokenType.COMMA));
        expect(TokenType.RPAREN);
        return new CreateStmt(name, cols, cons);
    }

    /**
     * 一个约束（列级或表级）可能以哪些 token 开头。
     * 列级要认全 NOT / NULL / DEFAULT，否则 `name string not null`、`age int32 default 18`
     * 会在列定义后直接跳出约束循环，报成 "expected ')' but got 'not'"。
     * FOREIGN / REFERENCES 只为了给出"暂不支持"的定向报错（parseTableConstraint / parseColConstraint 里）。
     */
    private static boolean isConstraintStart(TokenType t) {
        switch (t) {
            case CONSTRAINT: case PRIMARY: case UNIQUE: case CHECK: case FOREIGN:
            case NOT: case NULL: case DEFAULT: case REFERENCES:
                return true;
            default:
                return false;
        }
    }

    /** colDef := IDENT TYPE colConstraint* —— 列级约束顺带生成对应的 Constraint。 */
    private void parseColumnDef(List<Column> cols, List<Constraint> cons) {
        String colName = expectIdent();
        String typeName = expectIdent();
        FieldType ft = FieldType.fromName(typeName);       // 非法类型抛 SE-0002
        cols.add(new Column(colName, ft));
        while (isConstraintStart(peek().type)) {
            parseColConstraint(colName, cons);
        }
    }

    /**
     * colConstraint := [CONSTRAINT IDENT] ( NOT NULL | NULL | PRIMARY KEY | UNIQUE
     *                                     | DEFAULT (value|NULL) | CHECK '(' cond ')' )
     * 列级写法一律生成"只引用该列"的 Constraint（复合 PK/UNIQUE 只能写成表级）。
     */
    private void parseColConstraint(String colName, List<Constraint> cons) {
        String cname = parseConstraintName();
        if (match(TokenType.NOT)) {
            expect(TokenType.NULL);
            cons.add(Constraint.onColumn(Constraint.Kind.NOT_NULL, cname, colName, null));
        } else if (match(TokenType.NULL)) {
            // 显式 NULL = 可空，是默认行为；不生成约束，但要认下来（SQL 常见写法）
        } else if (match(TokenType.PRIMARY)) {
            expect(TokenType.KEY);
            cons.add(Constraint.onColumn(Constraint.Kind.PRIMARY_KEY, cname, colName, null));
        } else if (match(TokenType.UNIQUE)) {
            cons.add(Constraint.onColumn(Constraint.Kind.UNIQUE, cname, colName, null));
        } else if (match(TokenType.DEFAULT)) {
            cons.add(Constraint.onColumn(Constraint.Kind.DEFAULT, cname, colName, expectLiteralToken().text));
        } else if (match(TokenType.CHECK)) {
            expect(TokenType.LPAREN);
            Cond c = parseCond();
            expect(TokenType.RPAREN);
            cons.add(Constraint.onColumn(Constraint.Kind.CHECK, cname, colName, c.toSql()));
        } else if (peek().type == TokenType.FOREIGN || peek().type == TokenType.REFERENCES) {
            throw err(peek(), "FOREIGN KEY is not supported");
        } else {
            throw err(peek(), "unexpected token: '" + peek().text
                    + "', expected column constraint: NOT NULL | NULL | PRIMARY KEY | UNIQUE | DEFAULT | CHECK");
        }
    }

    /**
     * tableConstraint := [CONSTRAINT IDENT] ( PRIMARY KEY '(' IDENT,... ')'
     *                                       | UNIQUE      '(' IDENT,... ')'
     *                                       | CHECK '(' cond ')' )
     */
    private void parseTableConstraint(List<Constraint> cons) {
        String cname = parseConstraintName();
        if (match(TokenType.PRIMARY)) {
            expect(TokenType.KEY);
            cons.add(new Constraint(Constraint.Kind.PRIMARY_KEY, cname, parseIdentList(), null));
        } else if (match(TokenType.UNIQUE)) {
            cons.add(new Constraint(Constraint.Kind.UNIQUE, cname, parseIdentList(), null));
        } else if (match(TokenType.CHECK)) {
            expect(TokenType.LPAREN);
            Cond c = parseCond();
            expect(TokenType.RPAREN);
            // 表级 CHECK 可能引用多列，columns 留空表示"整个表"（显示时另起一行）
            cons.add(new Constraint(Constraint.Kind.CHECK, cname, new ArrayList<String>(), c.toSql()));
        } else if (peek().type == TokenType.FOREIGN || peek().type == TokenType.REFERENCES) {
            throw err(peek(), "FOREIGN KEY is not supported");
        } else {
            throw err(peek(), "unexpected token: '" + peek().text
                    + "', expected table constraint: PRIMARY KEY | UNIQUE | CHECK");
        }
    }

    /** 可选的 CONSTRAINT <名> 前缀；没写返回空串。 */
    private String parseConstraintName() {
        if (!match(TokenType.CONSTRAINT)) return "";
        return expectIdent();
    }

    /** '(' IDENT (',' IDENT)* ')' —— 复合键的列清单。 */
    private List<String> parseIdentList() {
        expect(TokenType.LPAREN);
        List<String> names = new ArrayList<>();
        do {
            names.add(expectIdent());
        } while (match(TokenType.COMMA));
        expect(TokenType.RPAREN);
        return names;
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

    /**
     * insert := INSERT INTO IDENT [ '(' IDENT,... ')' ] VALUES '(' value,... ')'
     * 列清单可省略；写了就只为清单里的列提供值，其余列由 DEFAULT 或 NULL 补全。
     */
    private Stmt parseInsert() {
        expect(TokenType.INSERT);
        expect(TokenType.INTO);
        String name = expectIdent();
        List<String> columns = peek().type == TokenType.LPAREN ? parseIdentList() : null;
        expect(TokenType.VALUES);
        expect(TokenType.LPAREN);
        List<Object> values = new ArrayList<>();
        do {
            values.add(expectLiteral());
        } while (match(TokenType.COMMA));
        expect(TokenType.RPAREN);
        return new InsertStmt(name, columns, values);
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

    /**
     * 字面量：数字 / 字符串 / TRUE / FALSE / NULL。报错时给出完整期望集合。
     * NULL 只能追加在最后 —— 期望集合文本（"expected: NUMBER | STR_LIT | TRUE | FALSE | NULL"）
     * 有回归用例断言其前缀。
     */
    private Object expectLiteral() {
        return expectLiteralToken().value;
    }

    /** 同 expectLiteral，但返回整个 token（DEFAULT 需要原文，以便区分 'null' 与 NULL）。 */
    private Token expectLiteralToken() {
        return expectAny(TokenType.NUMBER, TokenType.STR_LIT, TokenType.TRUE, TokenType.FALSE, TokenType.NULL);
    }

    /**
     * 从独立文本解析一个条件表达式（CHECK 约束落盘后重新解析用）。
     * 不套一层假 SELECT：那样出错时给出的行列号指向一句并不存在的 SQL。
     */
    public static Cond parseCondition(String text) {
        Parser p = new Parser(text);
        Cond c = p.parseCond();
        p.expect(TokenType.EOF);
        return c;
    }

    // ---- 工具 ----

    private Token peek() { return tokens.get(pos); }
    private Token next() { Token t = tokens.get(pos); pos++; return t; }
    private boolean match(TokenType tt) {
        if (peek().type == tt) { pos++; return true; }
        return false;
    }
    private Token expect(TokenType tt) {
        if (peek().type != tt) throw err(peek(), "expected " + describe(tt) + " but got '" + peek().text + "'");
        return next();
    }
    private String expectIdent() {
        Token t = expect(TokenType.IDENTIFIER);
        return (String) t.value;
    }

    /** 多选一匹配：不匹配时报 "unexpected token: 'x', expected: A | B | C"。 */
    private Token expectAny(TokenType... tts) {
        for (TokenType tt : tts) {
            if (peek().type == tt) return next();
        }
        StringBuilder exp = new StringBuilder();
        for (int k = 0; k < tts.length; k++) {
            if (k > 0) exp.append(" | ");
            exp.append(describe(tts[k]));
        }
        throw err(peek(), "unexpected token: '" + peek().text + "', expected: " + exp);
    }

    /** TokenType 的展示名：标点用符号本身，其余用枚举名（课程错误诊断格式）。 */
    private static String describe(TokenType t) {
        switch (t) {
            case LPAREN:   return "'('";
            case RPAREN:   return "')'";
            case COMMA:    return "','";
            case SEMICOLON: return "';'";
            case STAR:     return "'*'";
            case DOT:      return "'.'";
            case OPERATOR: return "OPERATOR(= <> < > <= >=)";
            default:       return t.name();
        }
    }

    /** 语法错误：错误类型 + 原因 + unexpected/expected + 行号列号定位。 */
    private Error err(Token t, String msg) {
        String at = t.line > 0 ? (" at line " + t.line + ", column " + t.column)
                : (" at " + t.pos);
        return new Error("SY-0001", msg + at);
    }
}
