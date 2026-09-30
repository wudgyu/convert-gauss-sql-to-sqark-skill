#!/usr/bin/env bash
# 构建 g2s 工具。
#
# g2s-core 不依赖任何第三方库，永远可编译；
# g2s-spark 是可选适配器，只有 SPARK_HOME 可用时才编译。
#
# 用法:
#   SPARK_HOME=/path/to/spark tools/build.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_DIR="$REPO_ROOT/tools"
OUT_DIR="$TOOLS_DIR/bin"

mkdir -p "$OUT_DIR"

echo "[1/3] 编译 g2s-core（零依赖）"
CORE_SRC_DIR="$TOOLS_DIR/g2s-core/src/main/java"
CORE_CLASSES="$TOOLS_DIR/g2s-core/build/classes"
rm -rf "$CORE_CLASSES"
mkdir -p "$CORE_CLASSES" "$TOOLS_DIR/g2s-core/build"
find "$CORE_SRC_DIR" -name '*.java' > "$TOOLS_DIR/g2s-core/build/sources.txt"
javac -encoding UTF-8 -d "$CORE_CLASSES" @"$TOOLS_DIR/g2s-core/build/sources.txt"
jar cf "$OUT_DIR/g2s-core.jar" -C "$CORE_CLASSES" .
echo "      -> $OUT_DIR/g2s-core.jar"

echo "[2/3] 检查 SPARK_HOME"
SPARK_HOME_VALUE="${SPARK_HOME:-}"
HAVE_SPARK=0
if [ -n "$SPARK_HOME_VALUE" ] && [ -d "$SPARK_HOME_VALUE/jars" ]; then
    HAVE_SPARK=1
    echo "      SPARK_HOME=$SPARK_HOME_VALUE"
else
    echo "      未设置可用的 SPARK_HOME，跳过 g2s-spark（仅静态检查档可用）"
fi

echo "[3/3] 编译 g2s-spark 适配器"
if [ "$HAVE_SPARK" = "1" ]; then
    SPARK_SRC_DIR="$TOOLS_DIR/g2s-spark/src/main/java"
    SPARK_CLASSES="$TOOLS_DIR/g2s-spark/build/classes"
    rm -rf "$SPARK_CLASSES"
    mkdir -p "$SPARK_CLASSES" "$TOOLS_DIR/g2s-spark/build"
    find "$SPARK_SRC_DIR" -name '*.java' > "$TOOLS_DIR/g2s-spark/build/sources.txt"
    if [ -s "$TOOLS_DIR/g2s-spark/build/sources.txt" ]; then
        javac -encoding UTF-8 -cp "$SPARK_HOME_VALUE/jars/*" -d "$SPARK_CLASSES" \
            @"$TOOLS_DIR/g2s-spark/build/sources.txt"
        jar cf "$OUT_DIR/g2s-spark.jar" -C "$SPARK_CLASSES" .
        echo "$SPARK_HOME_VALUE" > "$OUT_DIR/spark-home.txt"
        echo "      -> $OUT_DIR/g2s-spark.jar"
    else
        echo "      适配器源码为空，跳过"
    fi
else
    echo "      跳过"
fi

echo
echo "构建完成。运行方式:"
echo "  ./g2s split --in <file> --out runs/<id>"
