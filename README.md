# DBMS —— 数据库管理系统设计与实现（课程设计）

从零编写的一个**可运行、含客户端/服务器交互**的关系型数据库（Java，零第三方运行时依赖）。
严格对照课程图片的三大模块：**SQL 编译器**、**存储系统**、**数据库引擎**，并实现了客户端/服务端交互。

> 分模块实现讲解见 [三大模块详解.md](三大模块详解.md)（对照图片逐条讲，含关键代码与运行示例）。

---

## 一、特性总览（对照图片要求）

图片要求的三块能力，落地的类如下表：

| 图片条目 | 落地类 | 模块 |
|---------|--------|------|
| ① 词法分析：识别关键字 / 标识符 / 常量 / 运算符 | `Lexer` + `Token` + `TokenType` | SQL 编译器 |
| ① 语法分析：构建语法树，支持四类语句 | `Parser` + `ast/*`（create / insert / select / show） | SQL 编译器 |
| ① 语义分析：存在性 / 类型 / 列数检查 | `Analyzer` | SQL 编译器 |
| ① 执行计划生成：逻辑执行计划 | `PlanBuilder`（生成算子树） | SQL 编译器 |
| ② 页式存储：页分配 / 释放 / 读写 | `Page` + `DiskManager` + `PageManager` | 存储系统 |
| ② 缓存机制：LRU / FIFO、命中统计与日志 | `Cache` + `LruCache` + `FifoCache` | 存储系统 |
| ② 存储系统提供接口供数据库模块调用 | `Cache` / `PageManager` 接口，被 `StorageEngine` 调用 | 存储系统 |
| ③ 执行引擎：CreateTable / Insert / SeqScan / Filter / Project | `Executor` + `op/*` 七算子（另有 Sort/Join/Aggregate/IndexScan） | 数据库引擎 |
| ③ 存储引擎：行与页映射、磁盘组织 | `StorageEngine`（槽位化页）+ `DiskManager` | 数据库引擎 |
| ③ 系统目录：元数据作为特殊表存储 | `Catalog`（`sys_tables` / `sys_columns` / `sys_indexes` 用同一引擎存取） | 数据库引擎 |
| ③ 复杂索引：B+ 树 + 索引扫描 | `engine/index/*` + `op/IndexScan`（见 § 八） | 数据库引擎 |
| 客户端与服务器端交互 | `server/*` + `client/*` + `protocol/*` | —— |

**支持语句**（一个最小但完整的 SQL 子集）：

```sql
create table t (name type, ...);           -- 类型: int32 int64 float64 bool string datetime
insert into t values (v, ...);
select * | col, ... from t
       [where col op value (and|or col op value)*]
       [order by col asc|desc];
-- 多表联查 / 聚合（高级功能之二）：
select <列 | 聚合(列)>, ... from t1 [AS a]
       [INNER|LEFT] JOIN t2 [AS b] ON a.col = b.col   -- 可多表左深连接
       [where ...] [group by 列, ...] [order by 列|聚合输出名 asc|desc];
聚合函数: count(*) count(col) sum(col) avg(col) min(col) max(col)
show tables;
show table <name>;                          -- 操作符: = <> < > <= >= and or
-- 复杂索引（高级功能之三）：
create index <名> on <表>(<列>);             -- 在单列上建 B+ 树索引
show indexes [on <表>];                     -- 查看已建索引
begin;  commit;  rollback;                  -- 事务控制：每条语句默认自动提交，begin 开启显式事务
```

另外落地了图片底部**高级功能之一：事务与并发**（§ 六，并扩展出 **崩溃恢复 WAL** § 九）、**高级功能之二：多表联查与聚合**（§ 七）、**复杂索引**（§ 八），详见 [三大模块详解.md](三大模块详解.md)。

---

## 二、技术栈与构建

- 语言：Java（纯 JDK，无第三方 runtime 依赖）
- 构建：Maven，`groupId=com.course`，`artifactId=dbms`
- 编译：`maven.compiler.release=17`（代码保持 Java 8 兼容写法，JDK 8/17/21 均可编译）
- 测试：JUnit 4 + `maven-surefire-plugin` 3.2.5

```bash
cd dbms
mvn test                # 运行全部测试（共 138 个用例）
mvn package             # 打包出 target/dbms-1.0.0.jar
```

> 若本机 Maven 默认指向 JDK 8，代码仍兼容可编译；如需 `release=17` 语义请让 Maven 使用 JDK 17+。

---

## 三、快速开始（PowerShell 主推）

服务端与客户端通过 **Socket + 长度前缀分帧协议** 通信（默认端口 9999，见 `common/Consts`）。

```powershell
# 终端 1：新建一个库并启动服务端（前台）
.\run.ps1 create          # 等价于 java ...ServerLauncher create data\db 9999

# 终端 2：启动交互客户端
.\run.ps1 client

# 一键演示（自动后台起服务端 -> 跑 DEMO.sql -> 停服务端）
.\run.ps1 demo
```

交互客户端里：

```sql
dbms> create table users (id int32, name string, age int32, score float64);
dbms> insert into users values (1, 'alice', 23, 95.5);
dbms> insert into users values (2, 'bob', 30, 88.0);
dbms> insert into users values (3, 'carol', 22, 91.5);
dbms> select id, name from users where age > 24 order by score desc;
dbms> show tables;
dbms> \dt           -- 同 show tables
dbms> \d users      -- 同 show table users
dbms> \q            -- 退出
```

> 想**一条一条核对输出**（而不是一把梭 `DEMO.sql`），照 [手工测试清单.md](手工测试清单.md) 走：43 步，前 39 步的期望输出是从真实引擎实测抄下来的，覆盖 JOIN / NULL 三值逻辑 / 索引 / 聚合 / 事务 / 崩溃恢复 / 并发锁。

`run.ps1` 还支持 `open`（打开已有库重启恢复）、`build`、`test`；macOS/Linux 用 `run.sh`，cmd 用 `run.bat`。
跨平台的批处理/非交互用法：

```bash
java -cp target/dbms-1.0.0.jar com.course.dbms.client.ClientLauncher -f DEMO.sql   # 批量执行文件
java -cp target/dbms-1.0.0.jar com.course.dbms.client.ClientLauncher -e "select 1" # 单条执行
```

---

## 四、目录结构

```
dbms/
├── pom.xml, run.ps1, run.bat, run.sh, DEMO.sql
├── README.md, 三大模块详解.md, 项目理解手册.md, 手工测试清单.md   —— 四份文档
└── src/
    ├── main/java/com/course/dbms/
    │   ├── common/         Consts / Error / Log              —— 常量、统一异常、日志
    │   ├── storage/        Page / DiskManager / PageManager / Log  —— ② 存储系统（页式 + 缓存 + WAL）
    │   │                     Cache / LruCache / FifoCache / Log（崩溃恢复预写日志）
    │   ├── engine/
    │   │   ├── table/      FieldType / Schema / Row / Table / Catalog / CombinedSchema —— 行、结构、多表拼接列
    │   │   ├── index/      BPlusTree / Index / IndexManager —— B+ 树索引（内存树 + 元数据入目录）
    │   │   ├── storage/    StorageEngine  —— ③ 存储引擎（行↔页映射）
    │   │   └── exec/       Executor + op/*（SeqScan/IndexScan/Filter/Project/Sort/Join/Aggregate/CondEval/Compare）
    │   ├── compiler/       Lexer / Parser / Analyzer / PlanBuilder + token/ + ast/  —— ① SQL 编译器
    │   ├── db/             Database（门面 execute(sql)）/ Session（事务编排 + 加锁）
    │   ├── txn/            Lock / LockManager / Txn —— 表级 S/X 锁 + 影子页事务
    │   ├── protocol/       Package / Packager / Encoder / Decoder —— 客户端/服务端分帧协议
    │   ├── server/         Server / ConnectionHandler / ServerLauncher
    │   └── client/         ClientLauncher / Shell / Renderer
    └── test/java/com/course/dbms/    —— 分模块单元/集成/端到端测试（138 个用例）
```

---

## 五、一次 SQL 的完整生命周期

```
SQL 文本
  │  Lexer 词法分析（① 识别关键字/标识符/常量/运算符）
  ▼
Token 流
  │  Parser 语法分析（② 构建语法树，四类语句）
  ▼
AST 语法树
  │  Analyzer 语义分析（③ 存在性 / 类型 / 列数）
  ▼
校验通过
  │  PlanBuilder 执行计划生成（④ 逻辑执行计划 = 算子树）
  ▼
Plan（运算符树）
  │  Executor 执行引擎（⑤ 驱动五个算子执行）
  ▼
Result（列名 + 行）
  │  Encoder 编码 -> Socket 分帧 -> Decoder 解码
  ▼
客户端 Renderer 渲染为表格
```

串起全链路的门面是 `com.course.dbms.db.Database`：

```java
public Result execute(String sql) {
    Stmt stmt  = new Parser(sql).parse();     // 词法 + 语法
    analyzer.analyze(stmt);                   // 语义
    Plan plan  = planner.build(stmt);          // 执行计划
    return executor.execute(plan);             // 执行
}
```

---

## 六、事务与并发（图片底部高级功能①）

服务端本来就是多线程——每连接一个 `ConnectionHandler` 线程，共享同一个 `Database`（同一个 `PageManager`/`Catalog`）。为了让并发访问安全、并支持回滚，实现了**表级共享/排他锁** + **影子页事务**。

- **并发安全**：`txn/LockManager` 表级 S/X 锁。读-读共存；写-写、读-写互斥；同一事务重入；唯一共享持有者可升级为排他；FIFO 等待队列 + 等锁超时(`TX-0003`)。`LruCache/FifoCache/DiskManager/Catalog` 均线程安全。
- **显式事务**：`begin; ... commit;` / `rollback;`。事务内改动经 `txn/Txn` 的**影子页工作集**隔离——`PageManager` 通过 `ThreadLocal<Txn>` 感知当前线程是否有事务：无事务走原路径（与以前逐字节一致），有事务时 `getPage` 返回深拷贝、`persist`/`newPage` 只进工作集，`commit` 才写盘、`rollback` 直接丢弃。
- **隔离保证**：写事务对表拿 X 锁并**持有到提交**，其他会话读写都被挡 → 不脏读；事务内能读到自写数据(read-your-writes)；回滚干净。
- **自动提交**：`begin` 之外每条语句自动提交（短命持锁立即释放），裸调 `db.execute` 路径零改动，既有测试全绿。

`Session` 是编排中枢：事务控制语句(`BEGIN/COMMIT/ROLLBACK`)由它直接处理，数据语句由它加锁后交 `Database.executeStmt`。三种隔离来源、设计取舍、错误码与运行演示见 [三大模块详解.md](三大模块详解.md) 的「事务与并发」一节。

```sql
dbms> begin;
dbms> insert into users values (4, 'dave', 35, 99);
dbms> select * from users;   -- 事务内：能看到 dave（读己之写）
dbms> rollback;
dbms> select * from users;   -- 回滚后：dave 没了
```

---

## 七、多表联查与聚合（图片底部高级功能②）

把「查询」从单表扫描提升到**多表连接 + 聚集计算**。语法：

```sql
select <列 | 聚合(列)>, ... from t1 [AS a]
       [INNER|LEFT] JOIN t2 [AS b] ON a.col = b.col   -- 可多表左深连接
       [where ...] [group by 列, ...] [order by 列|聚合输出名 asc|desc];
聚合函数: count(*) count(col) sum(col) avg(col) min(col) max(col)
```

- **JOIN**：`op/Join` 算子对左行 × 右行做嵌套循环，满足 `ON a.col=b.col` 则拼接成一行（左列在前、右列在后）。列-列比较由扩展后的 `Cond`（新增 `column2/qualifier2`）+ `CondEval` 统一求值。无 `ON` 即笛卡尔积；`LEFT JOIN` 在右表无匹配时仍保留左侧行（右侧列置 `NULL`）。
- **表别名 / 限定列**：`FROM t a` 后可用 `a.col`；`CombinedSchema` 记录每列所属的限定名（别名或表名），`resolve` 解析限定名或裸名（裸名须全局唯一，否则报歧义 `SE-0004`）。
- **聚合 / GROUP BY**：`op/Aggregate` 按分组键分组，每组产出一行；`COUNT→Long`、`SUM→Long/整型或 Double/浮点`、`AVG→Double`、`MIN/MAX→原值`。有聚合时非聚合列必须出现在 GROUP BY（否则 `SE-0006`）；`ORDER BY` 可用分组键、输出别名或聚合名（`order by count(*)`）。
- **管线**：`SeqScan(t0) → [Join ×N] → [Filter(WHERE)] → [Aggregate | Project] → [Sort(ORDER BY)]`；纯单表无特性查询走原有 `SeqScan→Filter→Sort→Project` 路径（`isRich()` 区分），既有的三模块行为与测试零改动。

```sql
dbms> select u.name, u.dept, o.amount
        from users u left join orders o on u.id = o.uid
        where u.dept = 'mkt' order by o.amount desc;
dbms> select u.dept, count(*), avg(o.amount)
        from users u join orders o on u.id = o.uid
        group by u.dept order by count(*) desc;
```

**暂不支持**（作为扩展方向，见 § 十一）：`RIGHT/FULL JOIN`、`HAVING`、`DISTINCT`、多表达式 `GROUP BY`、子查询、标量函数、`ORDER BY <序号>`。

---

## 八、B+ 树索引与 IndexScan

把「查询」从"只会全表扫"提升到"能按范围取行"。落在 `engine/index/`（结构）+ `op/IndexScan`（算子）+ `PlanBuilder`（选不选索引）。

```sql
dbms> create index idx_age on users(age);
dbms> show indexes;                                  -- idx_age / users / age
dbms> select id, name from users where age = 30;     -- 走 IndexScan
dbms> select id from users where age > 24 order by age;
```

- **结构（`BPlusTree`，阶 5 / 每节点最多 4 键）**：所有数据只在叶子，内部节点只存分隔键；叶子用 `next` 串成有序链表，范围查找沿链表右扫。节点键数超限即分裂（叶子**复制上提**分隔键、内部节点**移出上提**），根分裂则树高 +1。叶子条目是 `(键, RID)` 且**允许重复键**（age 这种列重复值很多），键比较复用 `Compare.compare`，与 `ORDER BY`/`MIN`/`MAX` 完全一致。
- **物理计划选择（`PlanBuilder.indexScanFor`）**：在 `WHERE` 的**合取项**里找一个能用的索引 —— 顶层 `CMP` 或 `AND` 子树里的 `CMP`，**不钻进 `OR`**（OR 的任一支不成立就不能缩小范围，硬拆会漏行）；只认 `列 op 字面量` 且 `op ∈ {= < > <= >=}`。命中就按 op 换算扫描区间（`=`→闭区间 `[v,v]`，`age>=24`→`[24,+∞)`），生成 `IndexScan`；否则仍是 `SeqScan`。**没建索引时行为与以前逐字节一致**。
- **Filter 始终保留在 IndexScan 之上**：`IndexScan → Filter(WHERE) → [Sort] → Project`。索引只负责"少读页"，行还是要过一遍 `WHERE` —— 所以索引退化、区间取宽了都不会算错，只是慢了。
- **管线形状对比**（`PlanBuilderTest` 断言）：

```
建索引前  Project ← Filter ← SeqScan(users)
建索引后  Project ← Filter ← IndexScan(users)      -- 同一句 SQL，行结果完全相同
```

- **持久化取舍**：索引是**派生数据**，只有元数据进系统目录表 `sys_indexes`，B+ 树本身在内存里，`Catalog.reload()` 时**重扫基表重建**。好处是与影子页事务/WAL 天然解耦：提交后重建即生效，回滚后重建即撤销（`Session` 的 `ROLLBACK` 本来就会调 `reload()`）。`Insert` 算子插入成功后顺手把新 RID 记进该表所有索引（系统没有 delete/update，`insert` 是唯一写路径，一处钩子即可）。
- **并发**：建索引对目标表加 X 锁（要扫全表，必须挡住并发写），所以内存索引不会被别的会话边扫边改。

相关用例见 § 十 `engine/index/BPlusTreeTest`（分裂/树高/重复键/范围/有序）与 `db/IndexTest`（建索引前后结果一致、重启重建、事务回滚撤销）。

---

## 九、数据落盘与崩溃恢复

- 一个数据库对应磁盘上一个目录（默认 `data/db`）。
- 每个表一个文件 `table-{tableId}.db`，文件即定长页数组（页大小 4096 字节）。
- 系统目录 `sys_tables` / `sys_columns` 用**同一存储引擎**读写，因此建表/插数据均落盘。
- 重启后用 `run.ps1 open` 打开同一目录，`Catalog` 从目录元数据重载，数据仍在（见 `DatabaseTest.persistenceAcrossReopen`）。

```bash
.\run.ps1 open data\db       # 端口 9999
.\run.ps1 client
dbms> select * from users;   # 之前插入的数据仍在
```

**崩溃恢复（WAL 预写日志）**：只靠"落盘"还不够——`commit` 会把工作集逐页写进数据文件，若进程在写页**中途崩溃**（电源断、`kill`），磁盘上可能是**撕裂态**（有的页新、有的页旧），重启后无法判断该事务到底算不算提交成功，甚至丢数据。为此给事务加了 `storage/Log`（`wal.log`）：

- **提交协议**（教科书 WAL）：`commit` 先把事务工作集的所有页后像 + `COMMIT` 标记**追加到 WAL 并 fsync**，再把页写到数据文件、刷进共享缓存。即"先写日志、再落页"。
- **崩溃恢复**：`PageManager` 构造时、对外服务前执行 `Log.recover` —— 逐条解析 WAL（长度前缀定界，容忍撕裂尾部），只**重放有 `COMMIT` 标记**的事务的页（`disk.writePage(表,页号,后像)`），完成后清空 WAL。没有 `COMMIT` 标记（未提交）的事务被**自然丢弃**。
- **效果**：已提交事务崩溃后仍在（持久性）；未提交事务崩溃后消失（原子性）。影子页的隔离/回滚机制**原样保留**，WAL 只在其上补"崩溃安全"。

```sql
dbms> begin;
dbms> insert into users values (5, 'eve', 27, 88.0);
dbms> commit;            -- 先写 WAL 再落页；此刻 kill 掉服务端，重启后 eve 仍在
```

演示：`.\run.ps1 create data\db` → 起服务端 → 上面 `begin/insert/commit` → 直接结束服务端进程（模拟崩溃）→ `.\run.ps1 open data\db` 重启 → `eve` 仍在；若在途中未 COMMIT 就崩溃，重启后该行消失。相关用例见 § 十 `LogTest` / `CrashRecoveryTest`。

---

## 十、测试

`mvn test` 全绿，共 **138 个用例**，按模块覆盖：

| 测试类 | 数量 | 覆盖 |
|--------|-----|------|
| `PageTest` | 7 | 页读写、跨字节、页满返回 -1、落盘重载、LRU 淘汰、命中统计 |
| `storage/LogTest` | 4 | 崩溃恢复：已提交事务重放、未提交丢弃、撕裂尾部容错、重放幂等 |
| `StorageTest` | 3 | 多行读写、跨页变长字符串、重开恢复 |
| `CatalogTest` | 4 | 建表取表、重复建表报错、表不存在、目录持久化重开 |
| `engine/index/BPlusTreeTest` | 7 | B+ 树：多次分裂与树高增长、重复键返回全部 RID、范围开闭区间、跨叶子有序、清空、空树 |
| `ParserTest` | 18 | 四类语句解析、分号容错、语法错误、未知类型、JOIN/聚合/GROUP BY、CREATE INDEX / SHOW INDEXES |
| `AnalyzerTest` | 20 | 存在性 / 类型 / 列数 / 列名重复、JOIN 限定/歧义、聚合分组规则、索引列与索引名（SE-0004/SE-0007） |
| `PlanBuilderTest` | 19 | 计划树形状、JOIN 左链、Aggregate/Sort 根、**索引选择**（有索引→IndexScan、OR/<>/无索引→SeqScan、区间端点） |
| `DatabaseTest` | 5 | 全链路 create/insert/select/show、where 与 or、重开恢复、错误传播 |
| `ProtocolTest` | 3 | Result 编解码回环、分帧粘包防护 |
| `ServerClientTest` | 2 | 真实 Socket 往返、错误标志回传 |
| `E2ETest` | 1 | 客户端 `-f DEMO.sql` 端到端联调（含 CREATE INDEX / SHOW INDEXES） |
| `db/JoinAggTest` | 7 | 端到端：INNER/LEFT JOIN、COUNT/SUM/AVG/MIN/MAX、GROUP BY、聚合排序 |
| `db/NullSemanticsTest` | 8 | NULL 三值逻辑：六个运算符都不放行 NULL、`x = x` 不匹配、列列比较、升序垫底/降序最前、聚合忽略 NULL |
| `db/CrashRecoveryTest` | 2 | 崩溃重开：已提交事务仍在、未提交事务消失 |
| `db/IndexTest` | 13 | 端到端：建索引前后结果一致、等值/范围/排序、insert 维护、show indexes、重启重建、事务回滚撤销 |
| `txn/LockManagerTest` | 6 | 表级 S/X 锁：共存、互斥超时、阻塞后释放、重入升级、跨表释放 |
| `db/TxnTest` | 7 | 事务：回滚无痕、提交重启仍在、读己之写、建表回滚、重复 BEGIN、双线程隔离 |
| `server/ConcurrencyTest` | 2 | 真实双连接：写锁阻塞读、并行 insert 不丢更新 |

---

## 十一、扩展方向（图片底部高级功能，作为加分项）

**事务与并发**（§ 六）、**多表联查与聚合**（§ 七）、**复杂索引**（§ 八）已实现，其余**高级功能**属于加分项：

- **查询优化**：`PlanBuilder` 已会做最简单的物理计划选择（索引 vs 全表扫）；可继续做谓词下推、投影裁剪、**代价估算**（现在只要命中索引就走，不看选择率）、**多条件组合选索引**（现在只挑第一个能用的合取项）、`OR` 改写成 `UNION` 以便各支分别走索引。
- **索引深化**：多列（复合）索引、唯一索引、索引下推（把 `WHERE` 的其余条件也交给索引过滤）、把 B+ 树的分裂/合并真正落到【页】上（现在树在内存、元数据在目录表）、`DROP INDEX`。
- **JOIN/聚合深化**：`RIGHT/FULL JOIN`、`HAVING`、`DISTINCT`、多表达式 `GROUP BY`、`ORDER BY <序号>`、子查询、标量函数。
- **权限控制**：`Session` 可加分角色判断后 `Database.execute` 拦截。
- 事务侧可再往深处做：`SAVEPOINT`、多隔离级别（READ COMMITTED/REPEATABLE READ）、undo 日志与 checkpoint（WAL redo 已实现，见 § 九）、页级锁/意向锁。

这些在 [三大模块详解.md](三大模块详解.md) 末尾均标注为扩展方向。
