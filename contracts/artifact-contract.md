# 产物契约

本文件定义每次运行必须产出的文件与字段。字段名不得改动；新增字段必须向后兼容。

## run 目录

```
runs/<run_id>/
  00_input.sql            输入快照
  statements.jsonl        切分结果（由 g2s split 产出）
  01_analysis.jsonl       分析记录
  02_conversion.jsonl     转换记录（含思考过程）
  03_review.jsonl         审核记录
  04_fix.jsonl            纠错记录（可缺省）
  approvals.jsonl         人工确认记录（可缺省）
  05_final.sql            最终 SQL（仅闸门放行时产出）
  06_process-log.md       转换过程文档
  07_audit-report.md      审计报告
  run_manifest.json       运行清单
  trace.jsonl             追加式事件流
```

## 通用约束

1. 所有 JSONL 文件一行一条记录，UTF-8 编码，不得有跨行 JSON。
2. 每条记录必须带 `statement_id`，且与 `statements.jsonl` 一致，不得重编号。
3. 引用规则时必须写规则库中的完整 `id`，不得写标题或缩写。
4. 枚举字段只能取本文件列出的值。

## statements.jsonl（工具产出）

由 `g2s split` 生成，字段见工具输出，关键字段：
`statement_id`、`index`、`kind`、`start_line`、`end_line`、`sha256`、
`is_pl_block`、`is_meta_command`、`split_confidence`、`sql`、`text`。

## 01_analysis.jsonl（分析节点产出）

```json
{
  "statement_id": "s0007",
  "kind": "CREATE_TABLE",
  "tier": "confirm",
  "features": ["nextval_default", "timestamp_without_time_zone"],
  "rules_loaded": ["core/010-type-mapping.yaml", "core/060-ddl.yaml", "unsupported.yaml"],
  "rules_hit": ["core.ddl.default-nextval", "block.sequence"],
  "risk": "high",
  "reason": "含 DEFAULT nextval，Spark 无序列；其余类型可直接映射",
  "route": "split"
}
```

| 字段 | 必填 | 取值 |
|---|---|---|
| `tier` | 是 | `auto` / `confirm` / `blocked` |
| `features` | 是 | 检出的构造标签，无则为空数组 |
| `rules_hit` | 是 | 命中的规则 id |
| `risk` | 是 | `low` / `medium` / `high` |
| `reason` | 是 | 该判定的依据，必须具体到构造名 |

## 02_conversion.jsonl（转换节点产出）

这是「过程文档」的数据源：每条语句都必须留下可复查的决策链。

```json
{
  "statement_id": "s0007",
  "decision": "converted",
  "sql_after": "CREATE TABLE retail.customer_profile (...)",
  "rules_used": ["core.type-map.scalar", "core.ddl.default-nextval"],
  "understanding": "建表语句，含两个序列默认值与一个无时区时间列",
  "strategy": "类型名逐项映射；删除 DEFAULT nextval 子句",
  "alternatives": [
    {"option": "把 nextval 改成 monotonically_increasing_id",
     "rejected_because": "该函数不保证连续且不可用于 DEFAULT 子句，会改变键值语义"}
  ],
  "risks": ["键值生成方式移出数据库后需由写入侧保证唯一"],
  "confidence": "medium",
  "evidence": [{"line": 20, "snippet": "DEFAULT nextval('retail.customer_id_seq')"}],
  "source": "rule"
}
```

| 字段 | 必填 | 取值 |
|---|---|---|
| `decision` | 是 | `converted` / `unchanged` / `blocked` / `needs_manual` |
| `sql_after` | 是 | 转换结果；`blocked` 时写空字符串 |
| `rules_used` | 是 | 实际应用的规则 id；若为模型自由发挥写空数组并在 `source` 标注 |
| `understanding` | 是 | 该语句在原方言下的语义 |
| `strategy` | 是 | 采用的转换策略 |
| `alternatives` | 是 | 考虑过但未采用的方案及原因；没有则空数组 |
| `risks` | 是 | 本次转换引入或残留的风险；没有则空数组 |
| `confidence` | 是 | `high` / `medium` / `low` |
| `evidence` | 是 | 支撑判断的原文片段与行号 |
| `source` | 是 | `rule`（规则驱动）或 `llm`（模型自由发挥） |

`source: llm` 的记录必须在审核节点被重点复核，并在审计报告中单独统计条数。

## 03_review.jsonl（审核节点产出）

```json
{
  "statement_id": "s0007",
  "independent_reading": "审核方独立读原文得到的语义，不看转换者的理由",
  "verdict": "revise",
  "findings": [
    {
      "id": "drift.null-ordering",
      "severity": "error",
      "message": "ORDER BY 未补 NULLS 子句，结果顺序将与源端不同",
      "evidence": [{"line": 55, "snippet": "ORDER BY order_id"}],
      "suggestion": "补 NULLS LAST"
    }
  ]
}
```

| 字段 | 必填 | 取值 |
|---|---|---|
| `independent_reading` | 是 | 独立复核得到的语义，不得复述转换者的理由 |
| `verdict` | 是 | `pass` / `revise` / `block` |
| `findings[].severity` | 是 | `error` / `warning` / `info` |
| `findings[].suggestion` | 是 | 具体到可直接执行的修改动作 |

## 04_fix.jsonl（纠错节点产出）

```json
{
  "statement_id": "s0007",
  "round": 1,
  "answers_finding": "drift.null-ordering",
  "change": "在 ORDER BY order_id 后追加 NULLS LAST",
  "sql_after": "SELECT ... ORDER BY order_id NULLS LAST",
  "residual_risk": ""
}
```

每轮修复必须有 `round`，同一语句最多 2 轮。第 2 轮后仍未解决的，
不得继续循环，必须转为 `needs_manual` 并进审批清单。

## approvals.jsonl（人工确认）

每行一条记录：

```json
{"statement_id":"s0007","action":"accept-converted","by":"张三","at":"2026-09-30T18:00:00+08:00","note":"键值改由写入侧生成，已确认","manual_sql":""}
```

`action` 取 `accept-converted`（接受转换结果）、`use-manual`（改用人工 SQL）、
`exclude`（排除该语句）。`action` 为 `use-manual` 时 `manual_sql` 必填；
`by` 与 `at` 必填，用于追溯确认人与确认时间。

## run_manifest.json（运行清单）

必须包含：输入路径与 sha256、编码与行数、源与目标方言版本、工具版本、
规则集版本与各文件 sha256、各阶段完成状态、闸门统计（auto/confirm/blocked 条数）、
**校验档位**、模型宿主与名称、创建时间。

## 06_process-log.md（转换过程文档）

按 `statement_id` 顺序呈现每条语句的决策链：
识别结果 → 命中的规则 → 采用的策略 → 备选方案与放弃原因 → 风险自评 → 证据片段 →
审核结论 → 纠错轮次。

默认只对 `confirm`、`blocked` 与审核有 findings 的语句展开全文，
其余语句汇总为表格。但 `02_conversion.jsonl` 等机器可读记录**永远全量**，
不得因为文档简写而丢失任何一条语句的决策记录。

## 07_audit-report.md（审计报告）

必须包含：运行清单摘要、各档位条数统计、`source: llm` 的记录数与清单、
阻断清单与处置状态、人工确认记录、规则库覆盖情况、以及校验档位声明
（未达 `execution-verified` 时必须写明结果未经真实数据执行验证）。
