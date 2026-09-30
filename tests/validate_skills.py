#!/usr/bin/env python3
"""Skill 结构与跨文件引用校验。

skill 的正文会引用仓库级文件（契约、规则库、检查清单）。这些引用写错时不会报错，
只会让工作流静默降级，因此必须机检。

用法: python3 tests/validate_skills.py
"""

import re
import sys
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent
SKILLS_DIR = REPO_ROOT / "skills"

EXPECTED_SKILLS = [
    "gauss2spark",
    "gauss2spark-analyze",
    "gauss2spark-convert",
    "gauss2spark-review",
    "gauss2spark-fix",
    "gauss2spark-rules",
]

# 匹配正文里的相对路径引用，例如 ../../rules/index.yaml
PATH_PATTERN = re.compile(r"(?:\.\./)+[A-Za-z0-9_./-]+")

errors = []
checked_refs = 0


def main():
    actual = sorted(p.name for p in SKILLS_DIR.iterdir() if p.is_dir())
    if actual != sorted(EXPECTED_SKILLS):
        errors.append(f"skills 目录与预期不符: 实际 {actual}")

    for name in EXPECTED_SKILLS:
        skill_dir = SKILLS_DIR / name
        skill_md = skill_dir / "SKILL.md"
        if not skill_md.exists():
            errors.append(f"{name}: 缺少 SKILL.md")
            continue

        text = skill_md.read_text(encoding="utf-8")
        if not text.startswith("---"):
            errors.append(f"{name}: 缺少 frontmatter")
            continue
        match = re.match(r"^---\n(.*?)\n---", text, re.DOTALL)
        front = yaml.safe_load(match.group(1))
        if front.get("name") != name:
            errors.append(f"{name}: frontmatter name 与目录名不一致: {front.get('name')}")
        desc = front.get("description") or ""
        if len(desc) < 40:
            errors.append(f"{name}: description 过短，无法用于技能选择")
        if "TODO" in text:
            errors.append(f"{name}: 正文残留 TODO 占位符")

        if not (skill_dir / "agents" / "openai.yaml").exists():
            errors.append(f"{name}: 缺少 agents/openai.yaml")

        global checked_refs
        for ref in set(PATH_PATTERN.findall(text)):
            checked_refs += 1
            target = (skill_dir / ref).resolve()
            if not target.exists():
                errors.append(f"{name}: 引用的文件不存在 -> {ref}")

    return report()


def report():
    for e in errors:
        print(f"错误: {e}")
    print()
    print(f"Skill 校验: 检查 {len(EXPECTED_SKILLS)} 个 skill、{checked_refs} 处跨文件引用，"
          f"错误 {len(errors)}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
