# dbms 补丁：行列号 + 期望符号集 + 编译四阶段 trace

对照《大型平台软件设计实习》指导书与 SQL 编译器 PPT 的验收要求，本补丁补齐三项编译器细节：

| # | 课程要求 | 补丁内容 |
|---|---|---|
| 1 | Token 四元式 `[种别码, 词素值, 行号, 列号]`；词法错误"类型+位置+行列号" | `Token` 增加 `line/column`；`Lexer` 用前进式游标算行列号，错误报 `at line X, column Y` |
| 2 | 语法错误含"出错位置 + 期望符号"（PPT：`unexpected token: ';', expected: IDENTIFIER \| CONST \| '(' \| NOT`） | `Parser` 增加 `expectAny`/`describe`，全部语法错误带行列号 |
| 3 | "对正确的 SQL 依次输出 Token 流 → AST → 语义检查结果 → 执行计划" | 新增 `Tracer`（四阶段文本），协议加 trace 包，客户端 `\trace` / `-t` 开关 |

顺手补齐（PPT 词法难点清单要求）：`--` 行注释、`/* */` 块注释（未闭合报词法错误）。

## 改动文件清单

覆盖到仓库同名路径即可（`src/` 与仓库根对齐）：

| 文件 | 改动 |
|---|---|
| `compiler/token/Token.java` | 新增 `line`、`column` 字段（保留旧 4 参构造兼容） |
| `compiler/Lexer.java` | 行列号计算、注释跳过、错误带 `line:column` |
| `compiler/Parser.java` | `Parser(List<Token>)` 构造、`expectAny` 期望集合、错误带行列号 |
| `compiler/Tracer.java` | **新增**：编译四阶段追踪输出 |
| `db/Database.java` | 暴露 `tokenize/analyze/plan` 三个阶段接口 |
| `protocol/Package.java` / `Packager.java` | 帧 flag 增加 `2=trace`（0=结果，1=错误；两端同步更新） |
| `server/ConnectionHandler.java` | 识别 `trace ` 前缀：先回 trace 包，再正常执行 |
| `client/Shell.java` | `\trace` 开关；收包循环（trace 包打印后继续读结果包） |
| `client/ClientLauncher.java` | 新增 `-t` 批处理 trace 开关 |
| `test/.../compiler/TracerTest.java` | **新增**：6 个验收测试（四元式/错误定位/期望集合/注释/trace） |

## 应用步骤

```powershell
# 1. 把 src/ 覆盖到仓库（同名文件替换）
# 2. 全量回归
mvn test          # 原有 138 个用例应全绿 + 新增 TracerTest 6 个
mvn package
```

> 注意：`Packager` 帧格式变了（flag 语义扩展），**服务端和客户端必须一起更新**；
> 不带 trace 前缀的 SQL 走 flag=0/1，行为与以前完全一致。

## 使用与验收演示

```powershell
.\run.ps1 create data\db   # 终端 1
.\run.ps1 client           # 终端 2

dbms> create table users (id int32, name string, age int32);
dbms> insert into users values (1, 'alice', 23);
dbms> \trace               # 打开编译追踪
trace on
dbms> select id, name from users where age > 20;

--① 词法分析 Token 流 [种别码, 词素值, 行号, 列号]
SELECT       'select'                 (1,1)
IDENTIFIER   'id'                     (1,8)
...
--② 语法分析 AST
  SelectStmt
--③ 语义分析: OK
--④ 逻辑执行计划 (S-表达式)
  Project(Filter(SeqScan(users)))

 id | name
----+-------
  1 | alice
(1 row)

dbms> \trace               # 关闭
```

批处理/报告截图用：

```powershell
java -cp target/dbms-1.0.0.jar com.course.dbms.client.ClientLauncher -t -e "select id from users where age >= 24 order by id"
```

错误定位演示（现场 Debug 环节可直接展示阶段归属）：

```
dbms> select id from users where age > ;
✗ [SY-0001] unexpected token: ';', expected: NUMBER | STR_LIT | TRUE | FALSE at line 1, column 39
dbms> insert into users values ('abc', 1, 20);
✗ [SE-0003] ...                        ← 语义阶段，类型不匹配
dbms> select id from @;
✗ [LX-0001] unexpected character '@' at line 1, column 18   ← 词法阶段
```

`--① ~ --④` 四个阶段 + `LX/SY/SE/PL` 错误码前缀，正好对应 PPT"不知道错误属于哪个阶段就不能通过验收"的判定点。

## 测试对照（TracerTest，6 个用例）

1. `traceShowsAllFourStages` — 一条 select 的四阶段产物齐全，计划含 `Project(Filter(SeqScan(t)))`
2. `tokenCarriesLineAndColumn` — 跨行 SQL 的 Token 行列号正确
3. `lexErrorReportsLineColumn` — `LX-0001` 带 `at line 1, column 18`
4. `unterminatedStringReportsLineColumn` — 未闭合字符串不崩溃、带定位
5. `syntaxErrorHasExpectedSet` — `expected: NUMBER | STR_LIT | TRUE | FALSE` + 行列号
6. `commentsAreSkipped` — 行/块注释不产出 Token，行列号不受注释影响

## 仍未做（可选）

- **访问控制**：README 已列为扩展方向（Session 加角色判断 → Database.execute 拦截）
- EXPLAIN 独立语句、规则式优化展示（常量折叠等）——目前计划阶段已能展示 IndexScan/SeqScan 物理选择
