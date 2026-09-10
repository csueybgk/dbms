package com.course.dbms.engine.index;

import com.course.dbms.engine.storage.StorageEngine;
import com.course.dbms.engine.table.Column;
import com.course.dbms.engine.table.Row;
import com.course.dbms.engine.table.Table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引管理器：所有内存索引的注册表 + 维护入口。
 *
 * 三件事：
 *   1) 建索引：扫一遍基表，把所有行的 (列值 → RID) 灌进 B+ 树；
 *   2) 维护：插入新行时把新 RID 追加进该表上的所有索引（本系统没有 delete/update，
 *      insert 是唯一写路径，所以一处钩子就够）；
 *   3) 查询：按"表 + 列"找到可用索引，交给 IndexScan 算子与规划器。
 *
 * 目录里的元数据由 {@link com.course.dbms.engine.table.Catalog} 负责，本类只管树。
 */
public class IndexManager {

    private final StorageEngine se;
    private final Map<String, Index> byName = new LinkedHashMap<>();     // 索引名(小写) -> 索引
    private final Map<Integer, List<Index>> byTable = new HashMap<>();   // 表 id -> 该表上的索引

    public IndexManager(StorageEngine se) {
        this.se = se;
    }

    /** 按列名建索引：扫基表填充 B+ 树并登记。列名不存在返回 null（由调用方先做语义校验）。 */
    public Index createIndex(String name, Table table, String columnName) {
        int colIndex = table.schema().indexOf(columnName);
        if (colIndex < 0) return null;
        return createIndex(name, table, colIndex);
    }

    /** 按列下标建索引（重建时直接用 sys_indexes 里记的 cid，避免再按名字找一遍）。 */
    public Index createIndex(String name, Table table, int colIndex) {
        Column col = table.schema().column(colIndex);
        Index idx = new Index(name, table.name(), table.tableId(), col.name(), colIndex, col.type());
        for (StorageEngine.Located loc : se.scanLocated(table)) {          // 全表扫描建树
            idx.insert(loc.row().get(colIndex), loc.pageNo(), loc.slot());
        }
        register(idx);
        return idx;
    }

    private void register(Index idx) {
        byName.put(idx.name().toLowerCase(), idx);
        List<Index> list = byTable.get(idx.tableId());
        if (list == null) {
            list = new ArrayList<>();
            byTable.put(idx.tableId(), list);
        }
        list.add(idx);
    }

    /** 插入一行的钩子：把新 RID 追加到该表上的每个索引。 */
    public void onInsert(Table table, Row row, int pageNo, int slot) {
        List<Index> list = byTable.get(table.tableId());
        if (list == null) return;
        for (Index idx : list) idx.insert(row.get(idx.colIndex()), pageNo, slot);
    }

    /** 某张表上的所有索引（无则空列表）。 */
    public List<Index> indexesOf(int tableId) {
        List<Index> list = byTable.get(tableId);
        return list == null ? new ArrayList<>() : list;
    }

    /** 找某表某列上的索引（列名大小写不敏感）；没有则 null。规划器据此决定走 IndexScan 还是 SeqScan。 */
    public Index findFor(Table table, String columnName) {
        for (Index idx : indexesOf(table.tableId())) {
            if (idx.columnName().equalsIgnoreCase(columnName)) return idx;
        }
        return null;
    }

    /** 索引名是否已被占用。 */
    public boolean hasIndex(String name) {
        return byName.containsKey(name.toLowerCase());
    }

    /** 全部索引（show indexes / 重建时遍历）。 */
    public List<Index> all() {
        return new ArrayList<>(byName.values());
    }

    /** 清空所有索引（重建前调用）。 */
    public void clear() {
        byName.clear();
        byTable.clear();
    }
}
