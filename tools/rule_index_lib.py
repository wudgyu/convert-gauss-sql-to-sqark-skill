#!/usr/bin/env python3
"""规则库读取与索引构建的公共逻辑。

规则库是 YAML（便于人工维护），工具链只读 JSON。本模块负责把前者转成后者，
供 tools/gen_rule_index.py 生成、tests/validate_rules.py 校验一致性。
"""

import hashlib
import json
from pathlib import Path

import yaml


class StrictLoader(yaml.SafeLoader):
    """拒绝重复键的加载器。

    YAML 默认允许重复键且后者覆盖前者，这会让规则里出现两个 tier 时静默生效一个，
    正是本规则库最需要避免的歧义，因此必须当成错误处理。
    """


def _construct_mapping_no_duplicates(loader, node):
    loader.flatten_mapping(node)
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=True)
        if key in mapping:
            raise yaml.constructor.ConstructorError(
                None, None, f"重复的键: {key}", key_node.start_mark
            )
        mapping[key] = loader.construct_object(value_node, deep=True)
    return mapping


StrictLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_mapping_no_duplicates
)


def load_yaml(path):
    return yaml.load(Path(path).read_text(encoding="utf-8"), Loader=StrictLoader)


def sha256_file(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def collect_rules(rules_dir):
    """返回 (index_yaml, [(相对路径, 规则列表)])。"""
    rules_dir = Path(rules_dir)
    index_yaml = load_yaml(rules_dir / "index.yaml")
    collected = []
    for path in sorted(rules_dir.rglob("*.yaml")):
        if path.name == "index.yaml":
            continue
        data = load_yaml(path)
        if isinstance(data, dict) and "rules" in data:
            rules = data.get("rules") or []
        elif isinstance(data, list):
            rules = data
        else:
            rules = []
        collected.append((path, rules))
    return index_yaml, collected


def build_index(repo_root):
    """构建机器可读索引。不含时间戳，保证内容稳定可比对。"""
    repo_root = Path(repo_root)
    rules_dir = repo_root / "rules"
    index_yaml, collected = collect_rules(rules_dir)

    files = {}
    rules = {}
    for path, rule_list in collected:
        rel = str(path.relative_to(repo_root))
        files[rel] = sha256_file(path)
        for rule in rule_list:
            if not isinstance(rule, dict) or not rule.get("id"):
                continue
            rules[rule["id"]] = {
                "tier": rule.get("tier"),
                "status": rule.get("status"),
                "kind": rule.get("kind"),
                "risk": rule.get("risk"),
                "file": rel,
            }

    # 索引文件自身也纳入哈希，这样任何规则库改动都会反映到 index.json 里
    index_path = rules_dir / "index.yaml"
    files[str(index_path.relative_to(repo_root))] = sha256_file(index_path)

    return {
        "generated_by": "tools/gen_rule_index.py",
        "index_version": index_yaml.get("version"),
        "spark_target": index_yaml.get("spark_target"),
        "source_dialect": index_yaml.get("source_dialect"),
        "files": files,
        "rules": dict(sorted(rules.items())),
    }


def index_json(index):
    return json.dumps(index, ensure_ascii=False, indent=2) + "\n"
