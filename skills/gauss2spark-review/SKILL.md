---
name: gauss2spark-review
description: Independently review converted Spark SQL against the openGauss original, applying the semantic drift checklist and the residual dialect scan to produce findings with concrete fixes. Use after conversion, before the result is accepted.
---

# 审核节点

独立复核转换结果。本节点不修改转换产物，只产出 findings 与结论。

你是这条流水线上唯一的质量防线：转换者可能错、规则可能不完整，
而目标引擎不会告诉你结果对不对。所以要按本节点的方法重新推一遍。

## 输入与输出

- 输入：00_input.sql、statements.jsonl、02_conversion.jsonl
- 输出：03_review.jsonl，字段定义见 ../../contracts/artifact-contract.md
- 逐项核对的清单见 ../../checklist/semantic-drift-checklist.md

## 复核方法（顺序很重要）

1. 先独立读源语句，写出你自己的理解，填进 independent_reading 字段。
   此时不要看转换者的 understanding 与 strategy。
2. 再读转换结果，判断它是否与你独立推导的语义一致。
3. 最后才看转换者的理由，核对它是否解释了你发现的所有差异。

跳过第 1 步会使复核退化为「读一遍转换者的解释并点头」，失去意义。

## 必查项

1. 逐项核对漂移清单。清单里每一条都要过，命中就产出 finding。
2. 对转换结果做残留方言扫描，扫描清单在同目录的检查清单里。
   注意其中列出的两个例外，不要把 Spark 原生可用的写法误判为残留。
3. 核对每条 rules_used 引用的规则 id 在规则库中确实存在且处于 enabled 状态。
4. 核对档位一致性：分析判为 blocked 的语句，转换结果不应出现改写后的 SQL。
5. 对每条 source 为 llm 的记录逐条严查——这部分没有规则依据，风险最高。

## 结论

- pass：无 finding，或只有 info 级。
- revise：存在 warning 或 error 级的可修复问题。
- block：无法通过修复解决，必须人工介入。

每条 finding 必须给出可直接执行的修改动作。不接受「建议优化」这类模糊结论；
无法判定时给 warning，并提出具体的人工确认问题，不得默认放过。
