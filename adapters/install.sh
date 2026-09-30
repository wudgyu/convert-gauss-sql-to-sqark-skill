#!/usr/bin/env bash
# 把六个 skill 安装到指定 CLI 宿主的技能目录。
#
# 用符号链接而不是复制：skill 正文引用的 contracts/、rules/、checklist/ 按仓库根解析，
# 复制出去会让这些引用失效。因此仓库必须保留在原地。
#
# 用法:
#   adapters/install.sh --target claude
#   adapters/install.sh --target opencode --dir <实际技能目录>
#   adapters/install.sh --target claude --dry-run
#   adapters/install.sh --target claude --uninstall

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGETS="$REPO_ROOT/adapters/targets.yaml"
SKILLS=(
    gauss2spark
    gauss2spark-analyze
    gauss2spark-convert
    gauss2spark-review
    gauss2spark-fix
    gauss2spark-rules
)

TARGET=""
DIR=""
DRY_RUN=0
UNINSTALL=0

while [ $# -gt 0 ]; do
    case "$1" in
        --target) TARGET="${2:-}"; shift 2 ;;
        --dir)    DIR="${2:-}"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --uninstall) UNINSTALL=1; shift ;;
        -h|--help) sed -n '2,14p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "未知参数: $1" >&2; exit 2 ;;
    esac
done

if [ -z "$TARGET" ]; then
    echo "需要 --target <宿主>" >&2
    echo "已登记宿主:" >&2
    python3 - "$TARGETS" <<'PY' >&2
import sys, yaml
hosts = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))["hosts"]
for name, info in hosts.items():
    flag = "已实测" if info.get("verified") else "未实测"
    print(f"  {name:<12} {info.get('display_name','')}  （{flag}）")
PY
    exit 2
fi

# 目标目录：优先显式 --dir，否则查表；未实测且没有 --dir 时拒绝安装
if [ -n "$DIR" ]; then
    SKILLS_DIR="$DIR"
else
    SKILLS_DIR="$(python3 - "$TARGETS" "$TARGET" <<'PY'
import sys, yaml
hosts = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))["hosts"]
h = hosts.get(sys.argv[2])
if not h:
    print("")
elif h.get("verified"):
    print(h["skills_dir"])
else:
    print("UNVERIFIED:" + str(h.get("skills_dir", "")))
PY
)"
    case "$SKILLS_DIR" in
        UNVERIFIED:*)
            guess="${SKILLS_DIR#UNVERIFIED:}"
            echo "宿主 $TARGET 的技能目录约定尚未实测（猜测位置: $guess）。" >&2
            echo "请确认实际目录后用 --dir 指定，例如:" >&2
            echo "  adapters/install.sh --target $TARGET --dir /path/to/skills" >&2
            echo "确认后请更新 adapters/targets.yaml 的 verified 字段。" >&2
            exit 2
            ;;
        "")
            echo "未见过的宿主: $TARGET" >&2
            exit 2
            ;;
    esac
fi

# 展开 ~
case "$SKILLS_DIR" in
    "~"/*) SKILLS_DIR="$HOME/${SKILLS_DIR#\~/}" ;;
esac

echo "仓库根    : $REPO_ROOT"
echo "目标技能目录: $SKILLS_DIR"
if [ "$DRY_RUN" = "1" ]; then
    echo "模式      : 预演（不会改动文件系统）"
else
    mkdir -p "$SKILLS_DIR"
fi
echo

for skill in "${SKILLS[@]}"; do
    src="$REPO_ROOT/skills/$skill"
    dst="$SKILLS_DIR/$skill"
    if [ "$UNINSTALL" = "1" ]; then
        if [ -L "$dst" ]; then
            if [ "$DRY_RUN" = "1" ]; then
                echo "  将移除链接 $dst"
            else
                rm -f "$dst"
                echo "  已移除 $dst"
            fi
        else
            echo "  跳过 $skill（不是本仓库创建的链接）"
        fi
        continue
    fi
    if [ ! -d "$src" ]; then
        echo "  跳过 $skill（仓库中不存在）"
        continue
    fi
    if [ "$DRY_RUN" = "1" ]; then
        echo "  将链接 $dst -> $src"
    else
        ln -sfn "$src" "$dst"
        echo "  已链接 $dst -> $src"
    fi
done

echo
if [ "$UNINSTALL" = "1" ]; then
    echo "卸载完成。"
    exit 0
fi

echo "安装后的两件事："
echo "  1. 让工具能被找到：export G2S_HOME=\"$REPO_ROOT\""
echo "     （或直接把仓库根加入 PATH，skill 会按仓库根定位 ./g2s）"
echo "  2. 首次使用前构建工具：\"$REPO_ROOT/tools/build.sh\""
echo
echo "注意：仓库必须保留在原地。skill 正文引用的 contracts/、rules/、checklist/"
echo "按仓库根解析，移动或删除仓库会让这些引用失效。"
