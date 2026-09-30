#!/usr/bin/env bash
# 切分器断言测试。对 tests/local/ 下的样例逐条校验切分结果。
#
# 用法: tests/run_splitter_tests.sh
#
# 期望值来自人工核对样例原文得到的结论，不是从当前输出反推的，
# 因此这些断言能在切分逻辑被改坏时真正报警。
#
# 依赖: java（运行 g2s）、python3（仅用于解析 JSON 断言，不参与被测逻辑）

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
G2S="$REPO_ROOT/g2s"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# 样例目录：优先 tests/local（原始未脱敏，默认不入库），其次 tests/corpus（脱敏后入库）。
# 两者都没有时跳过，而不是报失败——否则新克隆的仓库会因样例缺失而误报。
FIXTURES_DIR="${G2S_FIXTURES_DIR:-}"
if [ -z "$FIXTURES_DIR" ]; then
    if compgen -G "$REPO_ROOT/tests/local/*.sql" > /dev/null; then
        FIXTURES_DIR="$REPO_ROOT/tests/local"
    elif compgen -G "$REPO_ROOT/tests/corpus/*.sql" > /dev/null; then
        FIXTURES_DIR="$REPO_ROOT/tests/corpus"
    fi
fi
if [ -z "$FIXTURES_DIR" ]; then
    echo "跳过: 未找到 SQL 样例（tests/local/ 与 tests/corpus/ 均为空）。"
    echo "      原始样例默认不入库，请把脱敏样例放入 tests/corpus/，"
    echo "      或用 G2S_FIXTURES_DIR=<dir> 指定样例目录。"
    exit 0
fi

pass=0
fail=0

field() { # <summary.json> <key>
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))[sys.argv[2]])' "$1" "$2"
}

hist() { # <summary.json> <kind>
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["kind_histogram"].get(sys.argv[2], 0))' "$1" "$2"
}

check() { # <label> <actual> <expected>
    if [ "$2" = "$3" ]; then
        printf '  ok    %-26s %s\n' "$1" "$2"
        pass=$((pass + 1))
    else
        printf '  FAIL  %-26s 实际=%s 期望=%s\n' "$1" "$2" "$3"
        fail=$((fail + 1))
    fi
}

run_case() { # <file> <stmts> <pl> <route> <inserts> <selects>
    local file="$1"
    local out="$WORK_DIR/$file"
    echo "== $file"
    "$G2S" split --in "$FIXTURES_DIR/$file" --out "$out" > /dev/null || {
        printf '  FAIL  切分执行失败\n'
        fail=$((fail + 1))
        return
    }
    local summary="$out/split_summary.json"
    check "语句数" "$(field "$summary" statement_count)" "$2"
    check "PL 块数" "$(field "$summary" pl_block_count)" "$3"
    check "路由" "$(field "$summary" route)" "$4"
    if [ -n "${5:-}" ]; then
        check "INSERT 数" "$(hist "$summary" INSERT)" "$5"
    fi
    if [ -n "${6:-}" ]; then
        check "SELECT 数" "$(hist "$summary" SELECT)" "$6"
    fi
}

echo "### 切分器回归测试"
echo

# 小文件：注释分号、字符串分号、多行 VALUES 均不得造成误切
run_case crud_dml_small.sql  8  0 direct  2 2
run_case ddl_objects_small.sql  4  1 direct

# 大文件：超过阈值应走分片路径
run_case crud_dml_large.sql  26  0 split  10
run_case ddl_objects_large.sql  23  1 split

# PL 块：美元引用体内部的分号不得切开语句，且必须整体标记阻断
run_case pl_blocks_small.sql  2  2 direct
run_case pl_blocks_large.sql  12  12 split

# 边界回归：行尾注释归上一条语句，且不得污染下一条语句的主体
echo "== 注释归属边界"
"$G2S" split --in "$FIXTURES_DIR/crud_dml_small.sql" --out "$WORK_DIR/boundary" > /dev/null
boundary_out="$WORK_DIR/boundary/statements.jsonl"

if python3 - "$boundary_out" <<'PY' 2>/dev/null
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1], encoding='utf-8')]
first = rows[0]
assert len(first['leading_comments']) == 2, '第一条应有两个前导注释'
assert any('insert one order' in c['text'] for c in first['trailing_comments']), '行尾注释应归第一条'
second = rows[1]
assert second['kind'] == 'SELECT', '第二条第类型应为 SELECT'
assert not second['sql'].startswith('--'), '第二条主体不得含前导注释残留'
assert 'web order; needs review' in rows[5]['sql'], '字符串内的分号必须保留'
PY
then
    printf '  ok    %-26s\n' "注释归属与字符串分号"
    pass=$((pass + 1))
else
    printf '  FAIL  %-26s\n' "注释归属与字符串分号"
    fail=$((fail + 1))
fi

echo
echo "### 结果: 通过 $pass，失败 $fail"
[ "$fail" -eq 0 ] || exit 1
