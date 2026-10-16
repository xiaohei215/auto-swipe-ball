#!/usr/bin/env bash
# 一键验证脚本（无需 Android SDK）
#
#   bash verify/run_verify.sh
#
# 三项检查：
#   1) 核心纯逻辑单元测试（坐标计算 / 安全区回退 / 参数收敛 / 状态机 / 事件总线）
#   2) 静态资源一致性审计（R 引用 / 布局 id / 格式化参数 / Manifest）
#   3) 全量源码语法级编译检查（符号缺失属预期，出现语法错误即失败）
set -u
cd "$(dirname "$0")/.." || exit 1

JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"
PYTHON="${PYTHON:-python}"

PURE_CORE=()
for f in app/src/main/java/com/autoswipe/ball/core/*.java; do
  # 与 Android SDK 无关的类才能脱离 SDK 编译
  if ! grep -q "^import android\." "$f"; then
    PURE_CORE+=("$f")
  fi
done

FAIL=0

echo "=============================================================="
echo "[1/3] 核心纯逻辑单元测试（${#PURE_CORE[@]} 个纯 Java 类）"
echo "=============================================================="
rm -rf verify/build && mkdir -p verify/build
if ! "$JAVAC" -encoding UTF-8 -source 1.8 -target 1.8 -d verify/build \
      verify/CoreLogicTest.java "${PURE_CORE[@]}" 2>&1 | grep -v "^Note:" | head -20; then
  : # 忽略「no output」分支
fi
if [ ! -f verify/build/CoreLogicTest.class ]; then
  echo "[FAIL] 核心类编译失败"
  FAIL=1
else
  "$JAVA" -Dfile.encoding=UTF-8 -cp verify/build CoreLogicTest || FAIL=1
fi

echo
echo "=============================================================="
echo "[2/3] 静态资源一致性审计"
echo "=============================================================="
"$PYTHON" verify/resource_audit.py || FAIL=1

echo
echo "=============================================================="
echo "[3/3] 全量源码语法级编译检查"
echo "=============================================================="
rm -rf verify/syntax && mkdir -p verify/syntax
"$JAVAC" -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 \
  -source 1.8 -target 1.8 -Xmaxerrs 3000 -d verify/syntax \
  $(find app/src/main/java -name "*.java") 2> verify/syntax/raw.txt
ALL_ERR=$(grep -c "error:" verify/syntax/raw.txt)
SYNTAX_ERR=$(grep -E "error: " verify/syntax/raw.txt \
  | grep -Ec "expected|illegal start|illegal character|not a statement|unclosed|reached end of file|class, interface, enum, or record expected|incompatible types")
echo "编译报错总数: $ALL_ERR（全部应为缺 Android SDK 导致的符号未解析）"
echo "语法类错误数: $SYNTAX_ERR（必须为 0）"
if [ "$SYNTAX_ERR" -ne 0 ]; then
  grep -E "error: " verify/syntax/raw.txt | grep -Ei "expected|illegal|unclosed|reached end" | head -20
  FAIL=1
fi

echo
if [ "$FAIL" -eq 0 ]; then
  echo "全部验证通过 (ALL PASS)"
else
  echo "存在失败项，请查看上方输出"
fi
exit $FAIL
