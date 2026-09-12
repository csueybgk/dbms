package com.course.dbms.compiler;

import com.course.dbms.compiler.ast.Stmt;
import com.course.dbms.compiler.ast.TxnStmt;
import com.course.dbms.compiler.token.Token;
import com.course.dbms.compiler.token.TokenType;
import com.course.dbms.db.Database;

import java.util.List;

/**
 * 编译过程追踪：对一条 SQL 依次输出四个阶段的产物（指导书验收要求）——
 *   ① 词法：Token 流     —— [种别码, 词素值, 行号, 列号] 四元式
 *   ② 语法：AST          —— 语句节点（自定义了 toString 时展示细节）
 *   ③ 语义：检查结果     —— OK，或抛出带错误码/定位的 SE- 错误
 *   ④ 计划：逻辑执行计划 —— 算子树的 S-表达式（Plan.toString，含 IndexScan/SeqScan 物理选择）
 *
 * 触发方式：SQL 文本前加 trace 前缀（客户端开 \trace 后自动加），
 * 由 ConnectionHandler 先回传 trace 包、再回传正常执行结果。
 */
public final class Tracer {

    private Tracer() {}

    public static String trace(Database db, String sql) {
        StringBuilder sb = new StringBuilder();

        // ① 词法分析
        List<Token> tokens = db.tokenize(sql);
        sb.append("--① 词法分析 Token 流 [种别码, 词素值, 行号, 列号]\n");
        for (Token t : tokens) {
            if (t.type == TokenType.EOF) break;
            sb.append(String.format("%-12s %-24s (%d,%d)%n",
                    t.type, "'" + t.text + "'", t.line, t.column));
        }

        // ② 语法分析
        Stmt stmt = new Parser(tokens).parse();
        sb.append("--② 语法分析 AST\n  ").append(astText(stmt)).append('\n');

        // 事务控制语句由 Session 拦截，不进语义分析与计划生成
        if (stmt instanceof TxnStmt) {
            sb.append("--③ 语义分析: 跳过（事务控制语句由 Session 拦截处理）\n");
            sb.append("--④ 执行计划: 无（BEGIN/COMMIT/ROLLBACK 不生成执行计划）");
            return sb.toString();
        }

        // ③ 语义分析（失败会抛 Error，由上层作为错误包回传）
        db.analyze(stmt);
        sb.append("--③ 语义分析: OK\n");

        // ④ 执行计划：算子树 S-表达式
        Plan plan = db.plan(stmt);
        sb.append("--④ 逻辑执行计划 (S-表达式)\n  ").append(plan);
        return sb.toString();
    }

    /** AST 文本：节点类自定义了 toString 时展示细节，否则只展示节点类型名。 */
    private static String astText(Stmt stmt) {
        try {
            if (stmt.getClass().getMethod("toString").getDeclaringClass() != Object.class) {
                return stmt.toString();
            }
        } catch (NoSuchMethodException ignored) {
            // 任何类都有 toString，不会走到这里
        }
        return stmt.getClass().getSimpleName();
    }
}
