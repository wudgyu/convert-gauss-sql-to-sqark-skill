#!/usr/bin/env python3
"""从 YAML 规则库生成机器可读索引 rules/index.json。

Java 侧只读 JSON，因此工具链不需要 YAML 解析库。
改动规则库后必须重新运行本脚本，tests/validate_rules.py 会校验两者一致。

用法: python3 tools/gen_rule_index.py [--repo <仓库根>]
"""

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from rule_index_lib import build_index, index_json  # noqa: E402


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parent.parent))
    args = parser.parse_args()

    repo_root = Path(args.repo).resolve()
    index = build_index(repo_root)
    out = repo_root / "rules" / "index.json"
    out.write_text(index_json(index), encoding="utf-8")
    print(f"已生成 {out}")
    print(f"  规则 {len(index['rules'])} 条，文件 {len(index['files'])} 个，"
          f"目标版本 {index['spark_target']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
