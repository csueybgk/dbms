package com.course.dbms.db;

import com.course.dbms.common.Consts;
import com.course.dbms.compiler.Analyzer;
import com.course.dbms.compiler.Lexer;
import com.course.dbms.compiler.Plan;
import com.course.dbms.compiler.PlanBuilder;
import com.course.dbms.compiler.Parser;
import com.course.dbms.compiler.ast.Stmt;
import com.course.dbms.compiler.token.Token;
import com.course.dbms.engine.Result;
import com.course.dbms.engine.exec.Executor;
import com.course.dbms.engine.storage.StorageEngine;
import com.course.dbms.engine.table.Catalog;
import com.course.dbms.storage.Cache;
import com.course.dbms.storage.DiskManager;
import com.course.dbms.storage.LruCache;
import com.course.dbms.storage.PageManager;
import com.course.dbms.txn.LockManager; // 共享锁管理器：多连接靠它协调

import java.util.List;

/**
 * 数据库门面：对外开放的唯一入口。
 * 持有 存储层(PageManager/StorageEngine/Catalog) 与 编译层(Parser/Analyzer/PlanBuilder) 与 执行层(Executor)，
 * execute(sql) 走完整管线：词法+语法(Parser) → 语义(Analyzer) → 逻辑计划(PlanBuilder) → 执行(Executor)。
 *
 * 一个 Database 对应磁盘上一个目录；重启后重新 open 即可恢复（Catalog 从目录元数据重载）。
 */
public class Database {

    private final PageManager pm;
    private final StorageEngine se;
    private final Catalog catalog;
    private final Analyzer analyzer;
    private final PlanBuilder planner;
    private final Executor executor;
    private final LockManager lockManager;   // 服务器所有连接共享，用于表级 S/X 锁协调

    /** 打开（不存在则创建）数据库目录；dir 为该库所在目录。 */
    public Database(String dir) {
        this(new PageManager(new DiskManager(dir), new LruCache(64)));
    }

    /** 允许外部注入自定义缓存策略（如切换 FIFO）用于演示。 */
    public Database(PageManager pm) {
        this.pm = pm;
        this.se = new StorageEngine(pm);
        this.catalog = new Catalog(se);
        this.analyzer = new Analyzer(catalog);
        this.planner = new PlanBuilder(catalog, se);
        this.executor = new Executor();
        this.lockManager = new LockManager(Consts.LOCK_TIMEOUT_MS);
    }

    /** 只做词法+语法解析，返回 AST。由 Session 先拿到语句类型以决定是否走事务/加锁。 */
    public Stmt parse(String sql) {
        return new Parser(sql).parse();
    }

    /** 只做词法分析，返回 Token 流（供 Tracer 展示四元式与错误定位测试）。 */
    public List<Token> tokenize(String sql) {
        return new Lexer(sql).tokenize();
    }

    /** 只做语义检查（供 Tracer 单独展示语义阶段）。 */
    public void analyze(Stmt stmt) {
        analyzer.analyze(stmt);
    }

    /** 语义检查 + 执行计划生成，返回逻辑执行计划但不执行（供 Tracer 展示计划阶段）。 */
    public Plan plan(Stmt stmt) {
        analyzer.analyze(stmt);
        return planner.build(stmt);
    }

    /** 执行一条【可执行语句】。是否走事务影子路径由 {@link com.course.dbms.txn.Txn#current()} 决定。 */
    public Result executeStmt(Stmt stmt) {
        analyzer.analyze(stmt);
        Plan plan = planner.build(stmt);
        return executor.execute(plan);
    }

    /** 执行一条 SQL，返回结构化结果（无事务上下文：语义+计划+执行；供测试裸调用与自动提交复用）。 */
    public Result execute(String sql) {
        return executeStmt(parse(sql));
    }

    public Catalog catalog() { return catalog; }
    public StorageEngine storage() { return se; }
    public PageManager pages() { return pm; }
    public Cache cache() { return pm.cache(); }
    public LockManager locks() { return lockManager; }

    /** 关闭所有的文件句柄（落盘）。 */
    public void close() {
        pm.close();
    }
}
