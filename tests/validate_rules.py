#!/usr/bin/env python3
"""规则库静态校验。

规则文件由模型阅读和应用，不由代码执行，因此结构合法性与 ID 唯一性必须靠机检保证。
本脚本检查 YAML 可解析、必需字段齐全、枚举取值合法、ID 不重复、索引指向的文件存在。

用法: python3 tests/validate_rules.py
"""

import sys
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent
RULES_DIR = REPO_ROOT / "rules"

KINDS = {"rewrite", "block", "note"}
TIERS = {"auto", "confirm", "blocked"}
STATUSES = {"enabled", "disabled", "candidate", "deprecated"}
CONFIDENCES = {"deterministic", "heuristic"}
RISKS = {"low", "medium", "high"}

errors = []
warnings = []
planned = []
seen_ids = {}
rule_count = 0


def err(msg):
    errors.append(msg)


def warn(msg):
    warnings.append(msg)


def check_rule(rule, source):
    global rule_count
    if not isinstance(rule, dict):
        err(f"{source}: 规则条目不是映射")
        return
    rid = rule.get("id")
    if not rid:
        err(f"{source}: 规则缺少 id")
        return
    rule_count += 1
    if rid in seen_ids:
        err(f"{source}: 规则 id 重复: {rid}（已在 {seen_ids[rid]} 出现）")
    else:
        seen_ids[rid] = source

    for field in ("title", "version", "kind", "tier", "status"):
        if not rule.get(field):
            err(f"{source}: {rid} 缺少必需字段 {field}")

    kind = rule.get("kind")
    if kind and kind not in KINDS:
        err(f"{source}: {rid} kind 非法: {kind}")
    tier = rule.get("tier")
    if tier and tier not in TIERS:
        err(f"{source}: {rid} tier 非法: {tier}")
    status = rule.get("status")
    if status and status not in STATUSES:
        err(f"{source}: {rid} status 非法: {status}")
    conf = rule.get("confidence")
    if conf and conf not in CONFIDENCES:
        err(f"{source}: {rid} confidence 非法: {conf}")
    risk = rule.get("risk")
    if risk and risk not in RISKS:
        err(f"{source}: {rid} risk 非法: {risk}")

    if status == "enabled":
        if not rule.get("confidence"):
            err(f"{source}: {rid} 已启用但缺少 confidence")
        if not rule.get("tier"):
            err(f"{source}: {rid} 已启用但缺少 tier")
        if not rule.get("evidence"):
            warn(f"{source}: {rid} 已启用但没有 evidence 事实依据")

    if kind == "rewrite":
        examples = rule.get("examples") or []
        if not examples:
            err(f"{source}: {rid} 是 rewrite 规则但没有 examples")
        for i, ex in enumerate(examples):
            if not isinstance(ex, dict) or "input" not in ex or "output" not in ex:
                err(f"{source}: {rid} 第 {i + 1} 个 example 缺少 input/output")

    # 高风险规则必须进 confirm 或 blocked，不允许 auto 自动放行
    if risk == "high" and tier == "auto":
        err(f"{source}: {rid} risk=high 却 tier=auto，高风险规则不得自动放行")


def main():
    index_path = RULES_DIR / "index.yaml"
    if not index_path.exists():
        err("缺少 rules/index.yaml")
        return report()

    try:
        index = yaml.safe_load(index_path.read_text(encoding="utf-8"))
    except yaml.YAMLError as e:
        err(f"rules/index.yaml 解析失败: {e}")
        return report()

    if not index.get("spark_target"):
        err("rules/index.yaml 缺少 spark_target")

    for topic in index.get("topics") or []:
        rel = topic.get("file")
        if not rel:
            err("rules/index.yaml 存在没有 file 的 topic")
            continue
        target = (RULES_DIR / rel).resolve()
        if not target.exists():
            if topic.get("status") == "planned":
                planned.append(rel)
            else:
                err(f"rules/index.yaml 指向的文件不存在: {rel}")

    for path in sorted(RULES_DIR.rglob("*.yaml")):
        if path.name == "index.yaml":
            continue
        rel = path.relative_to(REPO_ROOT)
        try:
            data = yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError as e:
            err(f"{rel}: YAML 解析失败: {e}")
            continue
        if data is None:
            warn(f"{rel}: 内容为空")
            continue
        if isinstance(data, dict) and "rules" in data:
            if not data.get("spark_target"):
                warn(f"{rel}: 缺少 spark_target")
            for rule in data["rules"] or []:
                check_rule(rule, str(rel))
        elif isinstance(data, list):
            for rule in data:
                check_rule(rule, str(rel))
        else:
            warn(f"{rel}: 既不是规则列表也没有 rules 键，跳过")

    return report()


def report():
    for p in planned:
        print(f"待补: {p}（索引已标注 status: planned）")
    for w in warnings:
        print(f"警告: {w}")
    for e in errors:
        print(f"错误: {e}")
    print()
    print(f"规则库校验: 共 {rule_count} 条规则，错误 {len(errors)}，警告 {len(warnings)}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
