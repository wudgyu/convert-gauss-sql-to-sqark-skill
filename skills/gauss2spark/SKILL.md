---
name: gauss2spark
description: Orchestrate an end-to-end migration of a GaussDB/openGauss SQL file to Spark SQL 4.2.0, driving the analyze, convert, review and fix stages with an approval gate and complete process records. Use when a whole SQL file must be migrated rather than a single statement.
---

# gauss2spark 工作流编排

把一个 openGauss SQL 文件端到端转换为 Spark 4.2.0 SQL，并产出可审计的过程记录。

## 适用范围

openGauss（DBCOMPATIBILITY = PG）到 Spark SQL 4.2.0。
PL 块与 psql 元命令明确不支持。测试覆盖版本只有 4.2.0，其他版本不得声称已验证。

## 先读这些

- 工作流契约 ../../contracts/workflow.md：节点、放行规则、保真度声明
- 产物契约 ../../contracts/artifact-contract.md：产物清单与字段定义
- 行为基线 ../../checklist/spark-4.2.0-baseline.md：全部实测结论
- 规则路由 ../../rules/index.yaml：档位定义与主题索引

## 执行步骤

1. 定位仓库根（顺序见工作流契约），确认 tools/bin/g2s-core.jar 存在；
   不存在就先运行 tools/build.sh，构建失败则中止报告，不要绕过工具继续。
2. 建立 run 目录 runs/UTC 时间戳-输入文件名，把输入复制为 00_input.sql。
3. 运行 ./g2s split --in 输入文件 --out run目录，得到 statements.jsonl 与路由判定。
4. 依次执行 gauss2spark-analyze、gauss2spark-convert、gauss2spark-review。
   审核产出 revise 时执行 gauss2spark-fix 并回到审核，最多 2 轮。
5. 执行闸门：运行 ./g2s gate --run run目录。它统计三个档位的条数、比对
   approvals.jsonl，并给出退出码（放行 0，未放行 3）。
6. 组装产物：放行时产出 05_final.sql；无论是否放行都要产出 06_process-log.md、
   07_audit-report.md 与 run_manifest.json。

## 闸门（必须严格执行）

只有 auto 档自动放行。任一 confirm 或 blocked 语句缺少审批记录时：不产出最终 SQL、
退出码非零、输出阻断清单与建议处置。只有显式传入 --allow-partial 才产出残缺版本，
且必须带显著提示头。

同一构造在多条语句中重复出现时（真实语料里的 DEFAULT nextval(...) 就是典型），
按构造归纳后一次性确认策略，不要逐条询问。

## 不要做的事

- 不要为了让流程跑完而降低档位，或把 blocked 静默改成 confirm。
- 不要在缺少审批时产出最终 SQL。
- 不要声称做过未做过的验证；run_manifest.json 里的校验档位必须如实填写。
