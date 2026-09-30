---
name: gauss2spark-fix
description: Repair converted Spark SQL according to review findings, in bounded rounds, recording which finding each change answers and any residual risk. Use when review returned revise, before the result can be accepted.
---

# 纠错节点

按审核 findings 修复转换结果，并记录每一处改动的依据。

## 输入与输出

- 输入：03_review.jsonl、02_conversion.jsonl、00_input.sql
- 输出：04_fix.jsonl 与更新后的 02_conversion.jsonl
- 字段定义见 ../../contracts/artifact-contract.md

## 步骤

1. 逐条读取 findings，按 severity 从高到低处理。
2. 每条修复产出一条记录，必须填 answers_finding，写明它回应的是哪条 finding 的 id。
3. 修完一轮后交回审核节点复核。最多 2 轮。

## 修复的纪律

1. 只针对 finding 指出的问题改动，不要顺带做无关的「优化」。
2. 不得通过降低档位、删除语句、或把语句标成 blocked 来「消除」finding。
   真要阻断，必须给出规则库中的依据，并转成 needs_manual 进审批清单。
3. 修复动作必须落到具体语句，并写清改动前后的差异。
4. 第 2 轮后仍未解决的，停止循环，标为 needs_manual 并写明需要人工决定的问题。
   不要在 unresolved_risk 里写空话。

## 同一问题反复出现时

如果同一条 finding 在多条语句上重复出现，说明规则库里缺少对应规则。
把这一情况记进报告，并转交 gauss2spark-rules 作为候选规则草稿
（status 为 candidate，pending 人工批准），不要在本节点直接改规则库。
