#!/usr/bin/env bash
# 运行全部校验与回归测试。
#
# 用法: tests/run_all.sh
# 依赖: JDK 17+（构建与运行工具）、python3 + PyYAML（规则库与 skill 校验）

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

fails=0

step() {
    local label="$1"
    shift
    echo "=== $label ==="
    if "$@"; then
        echo "-> 通过"
    else
        echo "-> 失败"
        fails=$((fails + 1))
    fi
    echo
}

step "构建工具"        tools/build.sh
step "规则库校验"      python3 tests/validate_rules.py
step "Skill 结构校验"  python3 tests/validate_skills.py
step "切分器回归"      tests/run_splitter_tests.sh
step "端到端回归"      tests/run_e2e_tests.sh

if [ "$fails" -eq 0 ]; then
    echo "全部通过。"
    exit 0
fi
echo "有 $fails 项失败。"
exit 1
