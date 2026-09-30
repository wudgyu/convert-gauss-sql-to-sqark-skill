---
name: gauss2spark-convert
description: Convert analyzed openGauss statements into Spark SQL 4.2.0 by applying the rule library, recording per-statement decisions, alternatives, risks and evidence. Use after analysis, when statements already carry a tier assignment.
---

# 转换节点

按规则库改写语句，并为每条语句留下可复查的决策链。

## 输入与输出

- 输入：statements.jsonl、01_analysis.jsonl，以及按索引加载的规则文件
- 输出：02_conversion.jsonl，字段定义见 ../../contracts/artifact-contract.md

## 每条语句必须交代清楚

understanding（源端语义）、strategy（采用的策略）、
alternatives（考虑过但放弃的方案及原因）、risks（引入或残留的风险）、
confidence、evidence（原文片段与行号）、rules_used（应用的规则 id）。

缺任何一项都不算完成。这些字段就是「转换过程可审计」的数据来源，
审核节点会逐条复核它们。

## 改写纪律

1. 只应用规则库中存在的规则，写出完整 id；规则没覆盖的地方不要顺手改写。
2. 不要应用规则库明令禁止的改写。core.functions.native 列出了原生可用、禁止改写的构造：
   nvl、nvl2、ifnull、decode、string_agg、listagg、array_agg、position(x in y)、
   substring(x from a for b)、ilike、date_trunc。改写这些只会增加风险。
3. 日期格式串必须严格按 core.datetime.format-tokens 逐 token 翻译。特别注意：
   SS 必须小写（大写会被当成秒的小数位，静默出错）、HH24 翻译成 HH、
   源端 HH 翻译成小写 hh、MI 翻译成小写 mm。
4. 类型名映射必须逐项比对表格，不得凭印象。无精度的 numeric 与 decimal 必须补显式精度。
5. 判为 blocked 的语句：decision 写 blocked，sql_after 留空，
   在 risks 里说明为什么无法转换并给出人工处置建议。不要猜一个能跑的写法。

## 关于 source 字段

- rule：改写完全由规则库推导得出，rules_used 非空。
- llm：规则库未覆盖、由你自行判断完成的改写，rules_used 留空。

source 为 llm 不是错误，但审核节点会逐条复核，审计报告会单独统计条数。
能用规则解决就不要自由发挥。
