---
name: gauss2spark-analyze
description: Split an openGauss SQL file into statements, classify and risk-assess each statement, and decide which conversion rules apply and at which tier. Use as the first stage of a gauss2spark migration, before any SQL is rewritten.
---

# 分析节点

切分、分类、风险分级、确定适用规则。本节点不改写任何 SQL。

## 输入与输出

- 输入：00_input.sql
- 输出：statements.jsonl（工具产出）、01_analysis.jsonl（本节点产出）
- 字段定义见 ../../contracts/artifact-contract.md

## 步骤

1. 运行 ./g2s split --in 00_input.sql --out run目录。
   它同时给出编码、行数、语句数、路由判定（direct 或 split）与逐语句记录。
2. 读取 ../../rules/index.yaml 的 topics 段，按每条语句检出的特征只加载相关规则文件，
   不要一次把整个规则库读进来。
3. 对每条语句打三个标签：
   - features：检出的构造标签，如 nextval_default、order_by、jsonb_column
   - rules_hit：命中的规则 id
   - tier：按下面的顺序判定

## 档位判定顺序

按顺序检查，取最严的一档：

1. statements.jsonl 中 is_pl_block 或 is_meta_command 为真，判 blocked。
2. 命中 unsupported.yaml 中任一 blocked 档规则，判 blocked。
3. 命中 core/010-type-mapping.yaml 中 blocked 档的类型条目，判 blocked。
4. 命中任一 confirm 档规则，判 confirm。
5. 其余判 auto。

reason 字段必须写清是哪一处构造导致该档位，并引用具体规则 id。
拿不准时取更严的一档，并在 reason 里写明不确定点。

## 边界

- 不要因为看起来能改就降档；档位依据是规则库，不是印象。
- PL 块与元命令无条件 blocked，不要尝试拆解其中的语句。
- 本节点不产出 sql_after，改写属于转换节点。
