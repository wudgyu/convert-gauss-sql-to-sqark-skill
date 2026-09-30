# Spark 4.2.0 行为基线（实测记录）

本文件记录**实测**得到的 Spark 行为，作为规则库的权威依据。凡与本文件冲突的规则，
以本文件为准；规则库中的 `confidence`、`risk`、`tier` 字段应与这里的结论一致。

## 实测环境

| 项 | 值 |
|---|---|
| Spark | 4.2.0（`spark-4.2.0-bin-hadoop3`） |
| 安装路径 | `/home/wudgyu/program/spark-4.2.0-bin-hadoop3` |
| Java | 17.0.5 LTS（`JAVA_HOME=/home/wudgyu/program/java/jdk-17.0.5`） |
| 运行模式 | `local[1]`，无集群、无 Hive |
| 校验工具 | `validation/SparkBaselineProbe.java` |
| 实测日期 | 2026-09-30 |

重跑方式（注意：本机沙箱禁止创建本地 socket，Spark 必须在非沙箱环境执行）：

```bash
SPARK_HOME=/home/wudgyu/program/spark-4.2.0-bin-hadoop3
javac -cp "$SPARK_HOME/jars/*" -d /tmp/probe validation/SparkBaselineProbe.java
jar cf /tmp/probe.jar -C /tmp/probe .
"$SPARK_HOME/bin/spark-submit" --class SparkBaselineProbe --master 'local[1]' /tmp/probe.jar
```

## 一、引擎调用行为（实现必须遵守）

| 现象 | 结论 |
|---|---|
| 解析期错误 | **抛异常** `ParseException`，形如 `[PARSE_SYNTAX_ERROR]`、`[UNSUPPORTED_DATATYPE]` |
| 分析期错误 | **不抛异常**。`EXPLAIN` 正常返回一行，内容为 `Error occurred during query planning:` |

**这条直接决定校验器实现**：语法层靠捕获 `ParseException`，分析层必须去匹配
`EXPLAIN` 返回文本里的 `Error occurred during query planning`，否则会把分析失败误判为通过。

## 二、语法层实测（`parsePlan`，无需任何表）

| 用例 | 结果 | 对规则库的含义 |
|---|---|---|
| `SELECT a::int FROM t` | 通过 | `::` 是 Spark 原生语法，**不是**方言残留 |
| `SELECT a FROM t DISTRIBUTE BY a` | 通过 | 词同意不同：解析成功但语义是重分区，必须进黑名单 |
| `SELECT DISTINCT ON (a) a FROM t` | 通过（分析期失败） | 需改写为 `row_number()` |
| `SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY a)` | 通过（分析期也通过） | **Spark 4.2 原生支持**，不得再转 `percentile_approx` |
| `UPDATE` / `DELETE` / `MERGE INTO` | 通过（分析期失败） | 语法合法，是否可用取决于目标表格式 |
| `ALTER TABLE t RENAME COLUMN a TO b` | 通过（catalog 报不支持） | 同上，取决于目标表格式 |
| `ALTER TABLE t ALTER COLUMN a TYPE STRING` | 通过（可执行） | 可直接转换 |
| `CREATE TABLE t (a VARCHAR(10), b DECIMAL(10,2))` | 通过 | `VARCHAR(n)`、`DECIMAL(p,s)` 可直接保留，无需降级为 `STRING` |
| `SELECT a FROM t CONNECT BY PRIOR a = b` | 失败 | 不支持，阻断 |
| `SELECT a FROM t FOR UPDATE` | 失败 | 不支持，阻断 |
| `CREATE SEQUENCE seq1` | 失败 | 不支持，阻断 |
| `COPY t FROM '...'` | 失败 | 不支持，阻断 |

## 三、openGauss 类型名实测

| 用例 | 结果 |
|---|---|
| `SELECT a::int FROM nums` | 正常 |
| `SELECT a::int4 FROM nums` | `ParseException: [UNSUPPORTED_DATATYPE] Unsupported data type "INT4"` |
| `SELECT a::bpchar FROM nums` | `ParseException: [UNSUPPORTED_DATATYPE] Unsupported data type "BPCHAR"` |

**结论**：类型转换的问题不在 `::` 运算符，而在**类型名**。`int4`、`int8`、`float4`、
`bpchar`、`varchar2`、`numeric`、`text`、`bytea`、`uuid`、`jsonb`、`timestamptz`
等 openGauss/PostgreSQL 类型名必须在三个语法位置统一映射：`::` 转换、`CAST(x AS t)`、
DDL 列定义。漏掉的会被引擎在解析期直接拒绝，属于**能静态检出**的错误，安全性有兜底。

## 四、语义漂移实测（本节是规则库的核心）

| 行为 | openGauss/PG | Spark 4.2 | 判定 |
|---|---|---|---|
| `ORDER BY a`（升序）默认 NULL 位置 | NULLS LAST | **NULLS FIRST** | **漂移，必须改写** |
| `ORDER BY a DESC` 默认 NULL 位置 | NULLS FIRST | **NULLS LAST** | **漂移，必须改写** |
| `greatest(1, NULL)` | 1（忽略 NULL） | 1（忽略 NULL） | 一致，无需处理 |
| `least(1, NULL)` | 1（忽略 NULL） | 1（忽略 NULL） | 一致，无需处理 |
| `concat('a', NULL)` | `a`（忽略 NULL） | `NULL` | **漂移，必须改写** |
| `concat_ws('', 'a', NULL)` | — | `a` | 可用作 `concat` 的替代写法 |

实测证据（analyzed/physical plan 片段）：

```
Sort [a#14 ASC NULLS FIRST], true, 0      <- ORDER BY a
Sort [a#27 DESC NULLS LAST], true, 0      <- ORDER BY a DESC
Sort [a#40 ASC NULLS LAST], true, 0       <- ORDER BY a NULLS LAST（显式）
```

**最重要的一条**：Spark 4.2 的默认 NULL 排序与 openGauss **完全相反**。任何没有显式
写出 `NULLS FIRST`/`NULLS LAST` 的 `ORDER BY`（以及窗口函数里的排序）都必须补齐，
否则结果集顺序会变，且不会报任何错。

## 五、对规则库的直接影响

必须**新增**：

1. `ORDER BY` / 窗口排序补齐 NULLS 子句（升序补 `NULLS LAST`，降序补 `NULLS FIRST`）
2. `concat()` 的 NULL 语义改写
3. `DISTINCT ON` 改写为 `row_number()`
4. openGauss 类型名统一映射表（三处语法位置共用）

必须**删除或改写**（按实测结论，原计划是错的）：

5. `::` 作为"方言残留"黑名单项 —— `::` 是原生语法，只保留类型名映射
6. `percentile_cont` → `percentile_approx` —— Spark 4.2 原生支持，**改了反而降精度**
7. `GREATEST`/`LEAST` 的 NULL 漂移规则 —— 实测一致，降级为普通说明
8. `varchar` → `STRING` 的降级映射 —— 可直接保留 `VARCHAR(n)`

阻断理由需要**修正措辞**：

9. `UPDATE`/`DELETE`/`MERGE`/`RENAME COLUMN` 不是"语法不支持"，而是"取决于目标表
   格式（v2 / Delta / Iceberg）"。理由文案直接影响人工确认时的判断。

## 六、适用版本范围（已冻结为 4.2.0）

**当前仅支持 Spark 4.2.0**，本文件的全部结论只对 4.2.0 有效。

由此产生的约束：

1. 规则库的 `scope.spark` 字段统一标注 `4.2.0`；凡属特定版本的规则必须显式标出。
2. 运行时检测实际 `SPARK_HOME` 版本，与 4.2.0 不一致时在 `run_manifest.json`
   记录版本不符，并把校验档位降级为"未在目标版本验证"，不得按已验证处理。
3. `docs/02-使用说明文档.md` 必须显著声明"测试覆盖版本 = Spark 4.2.0"，
   并说明未覆盖版本的处置方式（不承诺正确性，需自行重跑基线）。
4. 将来扩展版本支持时，用 `validation/SparkBaselineProbe.java` 在目标版本上重跑，
   新建 `checklist/spark-<version>-baseline.md`，逐条比对差异后再放开。

## 七、类型映射实测（建表后 DESCRIBE 的解析结果）

| 写法 | 结果 | 结论 |
|---|---|---|
| `DECIMAL`（无精度） | `decimal(10,0)` | **静默截断**：openGauss 的 `numeric` 是任意精度，照搬会被截成 10 位整数部分 |
| `NUMERIC`（无精度） | `decimal(10,0)` | 同上，且**不报错**，比报错更危险 |
| `NUMERIC(10,2)` | `decimal(10,2)` | 正常 |
| `VARCHAR`（无长度） | `[DATATYPE_MISSING_SIZE]` 报错 | 必须补长度或改为 `STRING` |
| `VARCHAR(10)` / `CHAR(5)` | `varchar(10)` / `char(5)` | 可原样保留 |
| `STRING` / `BINARY` | `string` / `binary` | 正常 |
| `INT[]` 数组写法 | `[PARSE_SYNTAX_ERROR]` | 数组语法必须改写为 `ARRAY<INT>` |
| `ARRAY<INT>` | `array<int>` | 正常 |
| `TIME` | `[UNSUPPORTED_TIME_TYPE]` | **Spark 4.2 没有 TIME 类型**，必须阻断或降级为 STRING |
| `INTERVAL` 列类型 | `[UNSUPPORTED_DATA_TYPE_FOR_DATASOURCE]` | 间隔类型不能作为列类型 |
| `BYTEA` / `UUID` / `JSONB` / `TIMESTAMPTZ` / `MONEY` / `TEXT` / `INT4` / `FLOAT4` / `BPCHAR` | 全部 `[UNSUPPORTED_DATATYPE]` 解析报错 | openGauss 类型名一律不被接受，**必须逐个映射** |

两点值得单独强调：

1. **`numeric` 不报错但会截断**。这是整个类型映射里唯一一处"既不报错又丢数据"的组合，
   必须映射为显式精度（推荐 `DECIMAL(38,18)`）并归入 `confirm` 档要求人工确认。
2. **`text` 在 Spark 里不是合法类型名**。它是最常见的 openGauss 类型之一，
   漏映射会直接解析失败——好处是这类错误一定会被引擎拦下，不会静默通过。
