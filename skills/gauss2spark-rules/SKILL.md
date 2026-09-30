---
name: gauss2spark-rules
description: Add, update, disable, remove and validate rules in the openGauss to Spark conversion rule library, keeping the topic index and evidence in sync. Use when the rule library needs maintenance or when review findings expose a missing rule.
---

# 规则库维护

维护规则库的增删改查。规则是数据，不是散文：改规则就是改
../../rules/ 下的 YAML 文件，并且每次改动都要通过校验。

## 规则库结构

- ../../rules/index.yaml：主题索引与字段规范，同时定义 auto / confirm / blocked 三个档位
- ../../rules/core/：按主题分组的规则文件（类型映射、排序 NULL、NULL 语义、标量函数、
  日期格式、DDL）
- ../../rules/unsupported.yaml：语句级不支持清单，权威来源
- ../../checklist/spark-4.2.0-baseline.md：实测行为基线，规则的 evidence 必须能追溯到它

字段含义与档位语义见 index.yaml 顶部的注释，改规则前先读它。

## 改动的通用要求

1. 每条规则必须有 id、title、version、kind、tier、confidence、risk 与 evidence。
2. kind 为 rewrite 的规则必须有 examples，且每个 example 带 input 与 output。
3. risk 为 high 的 rewrite 规则不得设为 tier auto——校验器会直接报错。
4. evidence 必须指向实测依据。没有实测依据的规则，状态只能是 candidate，不得启用。
5. 改动完成后必须运行 python3 tests/validate_rules.py，错误未清零不算完成。

## 四种操作

新增

- 先确认现有规则是否已覆盖；多数情况是在已有文件里加条目，而不是新建文件。
- 确实需要新建主题文件时，同步在 index.yaml 的 topics 中登记，
  否则校验器会报「索引指向的文件不存在」。
- 新规则的 status 一律先写 candidate，补齐实测依据与金样例后再改 enabled。

修改

- 任何语义变更都要递增 version，便于追溯某次转换用的是哪一版规则。
- 只调整措辞不算语义变更，可以不递增版本。

停用

- 把 status 改为 disabled，不要直接删除，保留历史便于追溯。

删除

- 仅用于重复或彻底写错的规则。删除前确认没有其它规则引用它。

## 候选规则的转正流程

审核或纠错阶段发现规则库覆盖不到的情况时，会产出候选规则草稿。转正流程：

1. 用 validation/SparkBaselineProbe.java 在目标版本上补跑实测。
2. 把实测结论写进 checklist/spark-4.2.0-baseline.md。
3. 补齐规则的 examples 与 evidence，档位按后果定：能机械保证等价的用 auto，
   有语义偏差或依赖前置条件的用 confirm，无法转换的用 blocked。
4. 运行 python3 tests/validate_rules.py。
5. 人工批准后才把 status 从 candidate 改成 enabled。

未完成第 1 至 4 步的规则一律保持 candidate。
