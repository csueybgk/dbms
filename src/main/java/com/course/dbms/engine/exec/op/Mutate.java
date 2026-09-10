package com.course.dbms.engine.exec.op;

import com.course.dbms.compiler.ast.Cond;
import com.course.dbms.engine.index.IndexManager;
import com.course.dbms.engine.storage.StorageEngine;
import com.course.dbms.engine.table.*;
import java.util.*;

/** DELETE / UPDATE 共用目标行扫描；赋值为 null 表示删除。 */
public class Mutate extends Operator {
    private final StorageEngine storage;
    private final Table table;
    private final Cond where;
    private final Map<String, Object> assignments;
    private final IndexManager indexes;

    public Mutate(StorageEngine storage, Table table, Cond where,
                  Map<String, Object> assignments, IndexManager indexes) {
        super(null);
        this.storage = storage;
        this.table = table;
        this.where = where;
        this.assignments = assignments;
        this.indexes = indexes;
    }

    @Override public List<Row> execute() {
        CombinedSchema schema = new CombinedSchema();
        for (Column column : table.schema().columns()) schema.add(table.name(), column.name(), column.type());
        List<StorageEngine.Located> targets = new ArrayList<>();
        List<Row> replacements = new ArrayList<>();
        // 先求值、检查所有新行，再执行写入，类型或长度错误不会留下部分修改。
        for (StorageEngine.Located located : storage.scanLocated(table)) {
            if (where != null && !CondEval.eval(where, table.schema(), located.row(), schema::resolve)) continue;
            targets.add(located);
            if (assignments != null) {
                Row row = new Row(located.row().values());
                for (Map.Entry<String, Object> entry : assignments.entrySet()) {
                    int column = table.schema().indexOf(entry.getKey());
                    row.values().set(column, StorageEngine.cast(table.schema(), column, entry.getValue()));
                }
                storage.validateRecord(storage.rowToBytes(table.schema(), row));
                replacements.add(row);
            }
        }
        try {
            for (int i = 0; i < targets.size(); i++) {
                if (assignments == null) storage.delete(table, targets.get(i));
                else storage.update(table, targets.get(i), replacements.get(i));
            }
        } finally {
            if (!targets.isEmpty()) indexes.rebuild(table);
        }
        return Collections.singletonList(Row.of(targets.size()));
    }

    @Override public String name() { return (assignments == null ? "Delete(" : "Update(") + table.name() + ")"; }
}
