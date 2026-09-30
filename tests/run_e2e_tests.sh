#!/usr/bin/env bash
# 端到端回归测试：驱动一次完整的 run 目录、闸门与产物组装。
#
# 覆盖点：契约校验、闸门默认阻断、审批后放行、最终 SQL 组装、
# 过程文档与审计报告内容，以及两处负例（缺字段、引用不存在的规则）。
#
# 用法: tests/run_e2e_tests.sh
# 依赖: java 运行 g2s、python3 构造负例

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
G2S="$REPO_ROOT/g2s"
E2E="$REPO_ROOT/tests/e2e"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass=0
fail=0

ok() { printf '  ok    %s\n' "$1"; pass=$((pass + 1)); }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail + 1)); }

expect_exit() { # <期望退出码> <描述> <命令...>
    local expect="$1" label="$2"
    shift 2
    local out code
    out="$("$@" 2>&1)"
    code=$?
    if [ "$code" = "$expect" ]; then
        ok "$label（退出码 $code）"
    else
        bad "$label：期望退出码 $expect，实际 $code"
        printf '%s\n' "$out" | sed 's/^/        /' | head -10
    fi
}

contains() { # <文件> <关键词> <描述>
    if grep -qF -- "$2" "$1" 2>/dev/null; then
        ok "$3"
    else
        bad "$3（未在 $(basename "$1") 中找到 $2）"
    fi
}

not_contains() { # <文件> <关键词> <描述>
    if grep -qF -- "$2" "$1" 2>/dev/null; then
        bad "$3（$(basename "$1") 中不应出现 $2）"
    else
        ok "$3"
    fi
}

prepare_run() { # <run 目录> <是否带审批>
    local run="$1"
    mkdir -p "$run"
    cp "$E2E/input.sql" "$run/00_input.sql"
    "$G2S" split --in "$run/00_input.sql" --out "$run" > /dev/null || return 1
    cp "$E2E/01_analysis.jsonl" "$E2E/02_conversion.jsonl" "$E2E/03_review.jsonl" \
       "$E2E/04_fix.jsonl" "$run/" || return 1
    if [ "${2:-}" = "approvals" ]; then
        cp "$E2E/approvals.jsonl" "$run/approvals.jsonl" || return 1
    fi
    return 0
}

echo "### 端到端回归测试"
echo
echo "[1] 未审批：闸门必须阻断"
RUN="$WORK/run"
prepare_run "$RUN" || { bad "准备 run 目录失败"; exit 1; }
expect_exit 0 "契约校验通过" "$G2S" check --run "$RUN"
expect_exit 3 "闸门阻断" "$G2S" gate --run "$RUN"
expect_exit 3 "组装被拒绝" "$G2S" assemble --run "$RUN"
if [ -f "$RUN/05_final.sql" ]; then
    bad "未放行时不应产出 05_final.sql"
else
    ok "未放行时未产出最终 SQL"
fi

echo
echo "[2] 审批后：闸门放行并产出全部产物"
cp "$E2E/approvals.jsonl" "$RUN/approvals.jsonl"
expect_exit 0 "闸门放行" "$G2S" gate --run "$RUN"
expect_exit 0 "组装成功" "$G2S" assemble --run "$RUN" --model-host claude --model-name test

contains "$RUN/05_final.sql" "ORDER BY order_id NULLS LAST" "ORDER BY 已补 NULLS LAST"
contains "$RUN/05_final.sql" "payload      STRING" "JSONB 已映射为 STRING"
contains "$RUN/05_final.sql" "[排除 s0003]" "被排除的语句留有标记"
contains "$RUN/05_final.sql" "UPDATE retail.demo_order" "确认后的 UPDATE 进入最终 SQL"
not_contains "$RUN/05_final.sql" "nextval" "最终 SQL 中不残留 nextval"

contains "$RUN/06_process-log.md" "备选方案与放弃原因" "过程文档含备选方案"
contains "$RUN/06_process-log.md" "drift.json-operator" "过程文档含审核发现"
contains "$RUN/06_process-log.md" "纠错第 1 轮" "过程文档含纠错轮次"

contains "$RUN/07_audit-report.md" "未经真实数据执行验证" "审计报告声明了校验档位"
contains "$RUN/07_audit-report.md" "| blocked 档 | 1 |" "审计报告统计 blocked 条数"
contains "$RUN/07_audit-report.md" "accept-converted" "审计报告含人工确认记录"

contains "$RUN/run_manifest.json" '"passed":true' "运行清单记录闸门通过"
contains "$RUN/run_manifest.json" '"verification_tier":"static-only"' "运行清单记录校验档位"
contains "$RUN/run_manifest.json" '"spark_target"' "运行清单记录目标版本"

echo
echo "[3] 负例：契约缺陷必须被拦下"
RUN2="$WORK/run2"
prepare_run "$RUN2" || { bad "准备负例目录失败"; exit 1; }
python3 - "$E2E/01_analysis.jsonl" "$RUN2/01_analysis.jsonl" <<'PY'
import json, sys
src, dst = sys.argv[1], sys.argv[2]
rows = [json.loads(line) for line in open(src, encoding="utf-8")]
rows[0].pop("reason", None)          # 去掉必需字段
with open(dst, "w", encoding="utf-8") as f:
    for r in rows:
        f.write(json.dumps(r, ensure_ascii=False) + "\n")
PY
expect_exit 1 "缺少 reason 被拦下" "$G2S" check --run "$RUN2"

RUN3="$WORK/run3"
prepare_run "$RUN3" || { bad "准备负例目录失败"; exit 1; }
python3 - "$E2E/02_conversion.jsonl" "$RUN3/02_conversion.jsonl" <<'PY'
import json, sys
src, dst = sys.argv[1], sys.argv[2]
rows = [json.loads(line) for line in open(src, encoding="utf-8")]
rows[0]["rules_used"] = ["core.not-exist.rule"]   # 引用不存在的规则
with open(dst, "w", encoding="utf-8") as f:
    for r in rows:
        f.write(json.dumps(r, ensure_ascii=False) + "\n")
PY
expect_exit 1 "引用不存在的规则被拦下" "$G2S" check --run "$RUN3"

echo
echo "### 结果: 通过 $pass，失败 $fail"
[ "$fail" -eq 0 ] || exit 1
