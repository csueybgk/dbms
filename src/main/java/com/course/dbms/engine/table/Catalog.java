package com.course.dbms.engine.table;

import com.course.dbms.common.Error;
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
 * 保留三张内置表（属元数据，不出现在 show tables 里）：
 *   sys_tables  (table_id int32, name string)          —— 表名 -> 表id
 *   sys_columns (table_id int32, cid int32, name string, type int32) —— 表结构
 *   sys_indexes (table_id int32, name string, column_name string, cid int32) —— 索引元数据
 * 用户表 id 从 10 开始，1/2/3 让给系统表。
 *
 * 索引的元数据走 sys_indexes 这张普通表（因此落盘、随事务提交/回滚），
 * 而索引的【内容】（B+ 树）是内存中的派生数据，在 reload() 里从基表重建。
 */
public class Catalog {

    public static final int SYS_TABLES_ID = 1;
    public static final int SYS_COLUMNS_ID = 2;
    public static final int SYS_INDEXES_ID = 3;
    private static final int FIRST_USER_ID = 10;

    private final StorageEngine se;
    private final Table sysTables;
    private final Table sysColumns;
    private final Table sysIndexes;
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
            String column = (String) r.get(2);
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

    /** 建表：登记 sys_tables + sys_columns。 */
    public Table createTable(String name, Schema schema) {
        String key = name.toLowerCase();
        if (tables.containsKey(key)) {
            throw new Error("CT-0002", "table already exists: " + name);
        }
        int tid = nextTableId();
        se.insert(sysTables, Row.of(tid, name));
        int cid = 0;
        for (Column c : schema.columns()) {
            se.insert(sysColumns, Row.of(tid, cid++, c.name(), (int) c.type().tag()));
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
            throw new Error("SE-0004", "列不存在: " + columnName + "（表 " + t.name() + "）");
        }
        if (hasIndex(name)) {
            throw new Error("SE-0007", "索引名已存在: " + name);
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

    public Table getTable(String name) {
        Table t = tables.get(name.toLowerCase());
        if (t == null) {
            throw new Error("TB-0001", "table not found: " + name);
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
        int max = FIRST_USER_ID - 1;
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
        return s;
    }
}
