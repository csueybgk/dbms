package com.course.dbms.engine.table;

import com.course.dbms.common.Error;
import com.course.dbms.common.ErrorCode;
import com.course.dbms.engine.index.Index;
import com.course.dbms.engine.index.IndexManager;
import com.course.dbms.engine.storage.StorageEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统目录：维护所有表的结构（元数据），并把它作为两张"特殊表"存进数据库——
 * 用的正是与普通表完全一样的 {@link StorageEngine}。对应图片"系统目录：维护元数据，作为特殊表存储"。
 *
 * 保留四张内置表（属元数据，不出现在 show tables 里）：
 *   sys_tables      (table_id int32, name string)          —— 表名 -> 表id
 *   sys_columns     (table_id int32, cid int32, name string, type int32) —— 表结构
 *   sys_indexes     (table_id int32, name string, column_name string, cid int32) —— 索引元数据
 *   sys_constraints (table_id int32, name string, kind string, cols string, detail string) —— 约束
 * 用户表 id 从 10 开始，1/2/3/4 让给系统表。
 *
 * 索引的元数据走 sys_indexes、约束走 sys_constraints（都是普通表，因此落盘、
 * 随事务提交/回滚）；而索引的【内容】（B+ 树）是内存中的派生数据，在 reload() 里从基表重建。
 *
 * 约束为什么单开一张表、而不是给 sys_columns 加一列：rowFromBytes 是按
 * schema.columnCount() 逐列 readInt+readFully 的，给 sys_columns 加第 5 列会让
 * 已有库里那些 4 字段的旧记录读第 5 个字段时 EOF（SE-0008）。单开一张新表则
 * 完全向后兼容：旧库加载后这些表就是"没有约束"。
 */
public class Catalog {

    public static final int SYS_TABLES_ID = 1;
    public static final int SYS_COLUMNS_ID = 2;
    public static final int SYS_INDEXES_ID = 3;
    public static final int SYS_CONSTRAINTS_ID = 4;
    private static final int FIRST_USER_ID = 10;

    private final StorageEngine se;
    private final Table sysTables;
    private final Table sysColumns;
    private final Table sysIndexes;
    private final Table sysConstraints;
    private final IndexManager indexManager;

    // 小写表名 -> Table（运行时缓存，启动时全量加载）；线程安全：多连接共享目录
    private final Map<String, Table> tables = new ConcurrentHashMap<>();

    public Catalog(StorageEngine se) {
        this.se = se;
        this.sysTables = new Table(SYS_TABLES_ID, "sys_tables", new Schema()
                .add("table_id", FieldType.INT32)
                .add("name", FieldType.STRING));
        this.sysColumns = new Table(SYS_COLUMNS_ID, "sys_columns", new Schema()
                .add("table_id", FieldType.INT32)
                .add("cid", FieldType.INT32)
                .add("name", FieldType.STRING)
                .add("type", FieldType.INT32));
        this.sysIndexes = new Table(SYS_INDEXES_ID, "sys_indexes", new Schema()
                .add("table_id", FieldType.INT32)
                .add("name", FieldType.STRING)
                .add("column_name", FieldType.STRING)
                .add("cid", FieldType.INT32));
        this.sysConstraints = new Table(SYS_CONSTRAINTS_ID, "sys_constraints", new Schema()
                .add("table_id", FieldType.INT32)
                .add("name", FieldType.STRING)
                .add("kind", FieldType.STRING)
                .add("cols", FieldType.STRING)
                .add("detail", FieldType.STRING));
        this.indexManager = new IndexManager(se);
        reload();
    }

    /**
     * 从系统表重新加载全部元数据（重启恢复 / 事务回滚撤销未提交改动）。
     * 建表、建索引都在 sys_* 里留了记录，所以重载一次就能把"未提交的 DDL"一并撤销；
     * 索引内容属于派生数据，重载时整体重建。
     */
    public synchronized void reload() {
        tables.clear();
        for (Row r : se.scan(sysTables)) {
            int tid = (Integer) r.get(0);
            String name = (String) r.get(1);
            tables.put(name.toLowerCase(), new Table(tid, name, schemaOf(tid)));
        }
        rebuildIndexes();
    }

    /** 重建全部内存索引：清空后按 sys_indexes 的记录逐条重扫基表建树。 */
    private void rebuildIndexes() {
        indexManager.clear();
        for (Row r : se.scan(sysIndexes)) {
            int tid = (Integer) r.get(0);
            String name = (String) r.get(1);
            int cid = (Integer) r.get(3);
            Table t = tableById(tid);
            if (t == null) continue;                            // 表已被删（本系统无 drop，防御性）
            if (cid < 0 || cid >= t.schema().columnCount()) continue;
            indexManager.createIndex(name, t, cid);
        }
    }

    private Table tableById(int tableId) {
        for (Table t : tables.values()) {
            if (t.tableId() == tableId) return t;
        }
        return null;
    }

    /** 建表：登记 sys_tables + sys_columns + sys_constraints。 */
    public Table createTable(String name, Schema schema) {
        String key = name.toLowerCase();
        if (tables.containsKey(key)) {
            throw new Error(ErrorCode.CT_TABLE_EXISTS, "表已存在: " + name + "（换个表名或先 drop 掉）");
        }
        int tid = nextTableId();
        se.insert(sysTables, Row.of(tid, name));
        int cid = 0;
        for (Column c : schema.columns()) {
            se.insert(sysColumns, Row.of(tid, cid++, c.name(), (int) c.type().tag()));
        }
        for (Constraint c : schema.constraints()) {
            // detail 可为 null（NOT NULL / PRIMARY KEY / UNIQUE 没有附加信息）
            se.insert(sysConstraints, Row.of(tid, c.name(), c.kind().sql(),
                    String.join(",", c.columns()), c.detail()));
        }
        Table t = new Table(tid, name, schema);
        tables.put(key, t);
        return t;
    }

    /**
     * 建索引：把元数据写进 sys_indexes，并在内存里扫一遍基表建出 B+ 树。
     * 事务内调用时元数据落在影子页上 —— 提交则一起落盘，回滚则 reload() 重建时自然消失。
     */
    public Index createIndex(String name, String tableName, String columnName) {
        Table t = getTable(tableName);                       // TB-0001 if missing
        int cid = t.schema().indexOf(columnName);
        if (cid < 0) {
            throw new Error(ErrorCode.SE_COLUMN_NOT_FOUND,
                    "索引的列不存在: " + columnName + "（表 " + t.name() + "；该表列为 "
                            + t.schema().columnNames() + "）");
        }
        if (hasIndex(name)) {
            throw new Error(ErrorCode.SE_INDEX_EXISTS, "索引名已存在: " + name);
        }
        se.insert(sysIndexes, Row.of(t.tableId(), name, t.schema().column(cid).name(), cid));
        return indexManager.createIndex(name, t, cid);
    }

    /** 索引名是否已被占用。 */
    public boolean hasIndex(String name) {
        return indexManager.hasIndex(name);
    }

    /** 全部索引（show indexes 用）。 */
    public List<Index> indexes() {
        return indexManager.all();
    }

    /** 索引管理器（Insert 算子靠它维护索引、PlanBuilder 靠它选索引）。 */
    public IndexManager indexManager() {
        return indexManager;
    }

    /** 用户表名清单，用于"表不存在"时报出候选（系统表不列，用户建不了也 drop 不掉）。 */
    public String tableNames() {
        List<String> names = new ArrayList<>();
        for (Table t : tables.values()) {
            if (t.tableId() >= FIRST_USER_ID) names.add(t.name());
        }
        java.util.Collections.sort(names);
        return names.isEmpty() ? "（空库，还没有表）" : String.join(", ", names);
    }

    public Table getTable(String name) {
        Table t = tables.get(name.toLowerCase());
        if (t == null) {
            throw new Error(ErrorCode.TB_TABLE_NOT_FOUND,
                    "表不存在: " + name + "（当前库中有: " + tableNames() + "）");
        }
        return t;
    }

    public boolean exists(String name) {
        return tables.containsKey(name.toLowerCase());
    }

    public List<Table> listTables() {
        return new ArrayList<>(tables.values());
    }

    public int tableCount() { return tables.size(); }

    private int nextTableId() {
        // 下限显式取到系统表 id，别只依赖 FIRST_USER_ID-1 这个巧合：新加系统表时
        // 忘了调这里就会把用户表的 id 撞到系统表文件上
        int max = Math.max(FIRST_USER_ID - 1, SYS_CONSTRAINTS_ID);
        for (Row r : se.scan(sysTables)) {
            max = Math.max(max, (Integer) r.get(0));
        }
        return max + 1;
    }

    private Schema schemaOf(int tableId) {
        TreeMap<Integer, Column> byCid = new TreeMap<>();
        for (Row r : se.scan(sysColumns)) {
            int tid = (Integer) r.get(0);
            if (tid != tableId) continue;
            int cid = (Integer) r.get(1);
            String name = (String) r.get(2);
            FieldType type = FieldType.fromTag((byte) (int) (Integer) r.get(3));
            byCid.put(cid, new Column(name, type));
        }
        Schema s = new Schema();
        for (Column c : byCid.values()) s.add(c.name(), c.type());
        for (Constraint c : constraintsOf(tableId)) s.addConstraint(c);
        return s;
    }

    /** 读回某张表的约束（CHECK 的条件树在这里按 detail 的 SQL 文本重新解析）。 */
    private List<Constraint> constraintsOf(int tableId) {
        List<Constraint> out = new ArrayList<>();
        for (Row r : se.scan(sysConstraints)) {
            int tid = (Integer) r.get(0);
            if (tid != tableId) continue;
            String name = (String) r.get(1);
            Constraint.Kind kind = Constraint.Kind.fromSql((String) r.get(2));
            String cols = (String) r.get(3);
            String detail = (String) r.get(4);
            out.add(new Constraint(kind, name, splitCols(cols), detail));
        }
        return out;
    }

    /** "0,1" 风格的列名清单（空串 = 空列表，对应表级 CHECK 这种不绑定单列的约束）。 */
    private static List<String> splitCols(String cols) {
        List<String> out = new ArrayList<>();
        if (cols == null || cols.isEmpty()) return out;
        for (String s : cols.split(",")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
