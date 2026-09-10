package com.course.dbms.db;

import com.course.dbms.common.Error;
import com.course.dbms.compiler.ast.CreateIndexStmt;
import com.course.dbms.compiler.ast.CreateStmt;
import com.course.dbms.compiler.ast.InsertStmt;
import com.course.dbms.compiler.ast.DeleteStmt;
import com.course.dbms.compiler.ast.UpdateStmt;
import com.course.dbms.compiler.ast.SelectStmt;
import com.course.dbms.compiler.ast.ShowStmt;
import com.course.dbms.compiler.ast.Stmt;
import com.course.dbms.compiler.ast.TableRef;
import com.course.dbms.compiler.ast.TxOp;
import com.course.dbms.compiler.ast.TxnStmt;
import com.course.dbms.engine.Result;
import com.course.dbms.engine.table.Catalog;
import com.course.dbms.engine.table.Table;
import com.course.dbms.txn.Lock;
import com.course.dbms.txn.LockManager;
import com.course.dbms.txn.Txn;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次会话：每个客户端连接对应一个 Session，封装它对同一个 {@link Database} 的访问。
 *
 * 会话持有【当前事务】(通过 {@link Txn} 的 ThreadLocal)，并驱动共享 {@link LockManager} 加/放锁：
 *   - 事务控制语句(BEGIN/COMMIT/ROLLBACK) 由这里直接处理，不生成执行计划；
 *   - 数据语句：显式事务内 → 加锁并持有到 COMMIT/ROLLBACK（事务内改动经影子页隔离）；
 *               自动提交 → 加锁执行后立即放锁（并发安全，行为与无事务一致）。
 *
 * 并发安全：对同一张表的写-写、读-写互斥；读-读不互斥；隔离靠"事务写锁持有到提交"。
 */
public class Session {

    private static final int SYS_TABLES = Catalog.SYS_TABLES_ID;
    private static final int SYS_COLUMNS = Catalog.SYS_COLUMNS_ID;
    private static final int SYS_INDEXES = Catalog.SYS_INDEXES_ID;

    private final Database db;
    private final LockManager locks;

    public Session(Database db) {
        this.db = db;
        this.locks = db.locks();
    }

    /** 执行一条 SQL，返回结果。 */
    public Result execute(String sql) {
        Stmt stmt = db.parse(sql);
        if (stmt instanceof TxnStmt) return handleTx((TxnStmt) stmt);
        return runData(stmt);
    }

    public Database database() { return db; }

    // ---- 事务控制 ----

    private Result handleTx(TxnStmt t) {
        switch (t.op) {
            case BEGIN:
                if (Txn.current() != null && Txn.current().active()) {
                    throw new Error("TX-0002", "已在事务中，请先 COMMIT 或 ROLLBACK");
                }
                Txn tx = new Txn(locks.nextId());
                tx.setState(Txn.State.ACTIVE);
                Txn.setCurrent(tx);
                return Result.message("transaction started");
            case COMMIT:
                requireActive();
                Txn cur = Txn.current();
                db.pages().commitTxn(cur);          // 写盘 + 刷进共享缓存
                locks.releaseTxn(cur.id());
                cur.setState(Txn.State.COMMITTED);
                Txn.clearCurrent();
                return Result.message("transaction committed");
            case ROLLBACK:
                requireActive();
                Txn cur2 = Txn.current();
                locks.releaseTxn(cur2.id());
                Txn.clearCurrent();                 // 先脱离事务，让 reload 读磁盘而非影子页
                db.pages().rollbackTxn(cur2);       // 丢弃工作集（从不写盘）
                db.catalog().reload();              // 撤销事务内未提交的建表
                cur2.setState(Txn.State.ABORTED);
                return Result.message("transaction rolled back");
            default:
                throw new Error("TX-0005", "unknown transaction op: " + t.op);
        }
    }

    private void requireActive() {
        Txn cur = Txn.current();
        if (cur == null || !cur.active()) {
            throw new Error("TX-0001", "没有活动事务");
        }
    }

    // ---- 数据语句 ----

    private Result runData(Stmt stmt) {
        Txn active = Txn.current();
        boolean inTxn = active != null && active.active();
        if (inTxn) {
            // 显式事务：current 已由 BEGIN 设好，锁跨语句持有到 COMMIT/ROLLBACK
            acquireLocks(stmt, active.id());
            return db.executeStmt(stmt);
        }
        // 自动提交：短命持锁，执行后立即放锁（无事务上下文，走原有直接路径）
        long lockId = locks.nextId();
        acquireLocks(stmt, lockId);
        try {
            return db.executeStmt(stmt);
        } finally {
            locks.releaseTxn(lockId);
        }
    }

    /**
     * 依据语句类型计算需加的表锁集合，并按 tableId 升序取得（避免跨表死锁）。
     * 每个条目 = {tableId, modeInt}，modeInt: 0=SHARED(S)，1=EXCLUSIVE(X)。
     * 统一对 sys_tables 加 S：串行化对目录页的读写，避免并发 DDL 破坏目录一致性。
     */
    private void acquireLocks(Stmt stmt, long lockId) {
        for (int[] r : lockRequest(stmt)) {
            locks.acquire(lockId, r[0], (r[1] == 1) ? Lock.EXCLUSIVE : Lock.SHARED);
        }
    }

    private List<int[]> lockRequest(Stmt stmt) {
        List<int[]> req = new ArrayList<>();
        if (stmt instanceof SelectStmt) {
            req.add(new int[]{SYS_TABLES, 0});
            SelectStmt sel = (SelectStmt) stmt;
            if (sel.isRich()) {
                // 多表联查：对 FROM 里的每一张表都加共享读锁，避免读到并发写
                for (TableRef tr : sel.from) {
                    Table t = db.catalog().getTable(tr.name);
                    req.add(new int[]{t.tableId(), 0});
                }
            } else {
                // 语法保证 select 必有 FROM <表>，tableName 不会为 null
                Table t = db.catalog().getTable(sel.tableName);
                req.add(new int[]{t.tableId(), 0});
            }
        } else if (stmt instanceof InsertStmt || stmt instanceof DeleteStmt || stmt instanceof UpdateStmt) {
            String name = stmt instanceof InsertStmt ? ((InsertStmt) stmt).tableName
                    : stmt instanceof DeleteStmt ? ((DeleteStmt) stmt).tableName : ((UpdateStmt) stmt).tableName;
            Table t = db.catalog().getTable(name);
            req.add(new int[]{SYS_TABLES, 0});
            req.add(new int[]{t.tableId(), 1});
        } else if (stmt instanceof CreateStmt) {
            req.add(new int[]{SYS_TABLES, 1});
            req.add(new int[]{SYS_COLUMNS, 1});
        } else if (stmt instanceof CreateIndexStmt) {
            // 建索引要扫全表，必须挡住并发写（否则扫描期间插入的行会漏进索引）
            CreateIndexStmt ci = (CreateIndexStmt) stmt;
            Table t = db.catalog().getTable(ci.tableName);
            req.add(new int[]{SYS_TABLES, 0});
            req.add(new int[]{SYS_INDEXES, 1});
            req.add(new int[]{t.tableId(), 1});
        } else if (stmt instanceof ShowStmt) {
            ShowStmt s = (ShowStmt) stmt;
            if (s.indexes) {
                req.add(new int[]{SYS_INDEXES, 0});
                if (s.tableName != null) {
                    Table t = db.catalog().getTable(s.tableName);
                    req.add(new int[]{t.tableId(), 0});
                }
            } else {
                req.add(new int[]{SYS_TABLES, 0});
                if (!s.list) {
                    Table t = db.catalog().getTable(s.tableName);
                    req.add(new int[]{t.tableId(), 0});
                }
            }
        }
        // 按 tableId 升序：谁都在同一全局顺序上抢锁，谁也不会互相等成环
        req.sort((a, b) -> Integer.compare(a[0], b[0]));
        return req;
    }
}
