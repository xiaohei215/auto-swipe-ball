#!/usr/bin/env bash
# ============================================================================
# 无 Android Studio 的 APK 构建脚本
#
#   依赖：JDK 17 + Android SDK（build-tools 34.0.0 / platforms android-34）
#   流程：aapt2 编译资源 -> aapt2 链接 -> javac 编译 -> d8 转 dex
#         -> 塞回 APK -> zipalign -> apksigner 签名
#
#   用法：
#     KEYSTORE_PASS='<签名密钥口令>' bash tools/build_apk.sh
#   可用环境变量覆盖：
#     KEYSTORE_PASS=<口令>                【必填】签名密钥口令
#                                        刻意不写进源码 —— 本仓库是公开的，代码里不留任何口令
#     TOOLCHAIN=E:/workbuddy-toolchain   （工具链根目录）
#     KEYSTORE=<path>                    （自定义签名密钥，默认自动生成）
#     JDK_HOME / ANDROID_SDK_ROOT        （工具链位置覆盖）
# ============================================================================
set -euo pipefail

# ---------------------------------------------------------------------------
# 本脚本依赖 MSYS / Git-Bash 的路径自动转换：把 /e/xxx 转成 E:\xxx 再交给
# javac / aapt2 / d8 / apksigner 这些原生 Windows 程序。
# 若外部环境设了 MSYS_NO_PATHCONV=1（例如为了让 adb 的 /sdcard 参数不被改写），
# 路径会原样传过去，Java 工具就会报
#   FileNotFoundException: \e\workbuddy workplace\...
# 这里临时关掉它，并明确提示，免得排查半天。
# ---------------------------------------------------------------------------
if [ -n "${MSYS_NO_PATHCONV:-}" ]; then
  echo "[i] 检测到 MSYS_NO_PATHCONV=1：已在本脚本内部临时关闭。"
  echo "    原因：本脚本要把 /e/xxx 形式的路径交给原生 Windows 工具，"
  echo "    关掉路径转换会导致它们找不到文件。"
  unset MSYS_NO_PATHCONV
fi

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
TC="${TOOLCHAIN:-E:/workbuddy-toolchain}"
JDK_HOME="${JDK_HOME:-$TC/jdk-17}"
SDK="${ANDROID_SDK_ROOT:-$TC/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM_JAR="$SDK/platforms/android-34/android.jar"

# ---------------------------------------------------------------------------
# 签名口令：只从环境变量读，源码里不留任何口令（本仓库是公开的）
# ---------------------------------------------------------------------------
if [ -z "${KEYSTORE_PASS:-}" ]; then
  cat >&2 <<'EOM'

[FAIL] 未设置环境变量 KEYSTORE_PASS（签名密钥口令）。

       本脚本刻意不把口令写进源码 —— 本仓库公开，代码里不留任何口令。
       请这样调用：

         KEYSTORE_PASS='你的口令' bash tools/build_apk.sh

       若本机还没有签名密钥，脚本会在首次构建时自动生成一把，
       并用你传入的这个口令保护它。

       注意：若本机已有旧密钥，必须传入与它一致的口令，否则签名失败。
       换口令 = 换密钥，装了旧包的设备会因签名不一致而无法覆盖升级。

EOM
  exit 1
fi

AAPT2="$BT/aapt2.exe"
D8="$BT/d8.bat"
ZIPALIGN="$BT/zipalign.exe"
APKSIGNER="$BT/apksigner.bat"
JAVAC="$JDK_HOME/bin/javac.exe"
KEYTOOL="$JDK_HOME/bin/keytool.exe"
PY="${PYTHON:-$(command -v python || command -v python3)}"

APP_NAME="AutoSwipeBall"
VERSION_NAME="1.0.4"
VERSION_CODE=7
MIN_SDK=26
TARGET_SDK=34
PKG="com.autoswipe.ball"

# 构建目录刻意不含空格：d8.bat / apksigner.bat 对带空格的路径支持不佳
WORK="$TC/work"
OUT="$WORK/out"
DIST="$PROJ/dist"

step() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }

step "0/8 校验工具链"
for f in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$JAVAC" "$KEYTOOL" "$PLATFORM_JAR" "$PY"; do
  [ -f "$f" ] || [ -x "$f" ] || { echo "[FAIL] 缺少: $f"; exit 1; }
done
echo "JDK  : $("$JAVAC" -version 2>&1)"
echo "SDK  : $SDK"

step "1/8 准备构建目录"
rm -rf "$WORK/src" "$OUT"
mkdir -p "$WORK/src" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$DIST"
cp -r "$PROJ/app/src/main/java" "$WORK/src/java"
cp -r "$PROJ/app/src/main/res"  "$WORK/src/res"

# 源清单不带 package 属性（AGP 8 要求 namespace 走 build.gradle），
# 手工构建时把 package 注入到构建副本里，源文件保持不动
sed "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$PKG\">|" \
  "$PROJ/app/src/main/AndroidManifest.xml" > "$WORK/src/AndroidManifest.xml"
grep -q "package=\"$PKG\"" "$WORK/src/AndroidManifest.xml" \
  || { echo "[FAIL] package 注入失败，请检查 AndroidManifest.xml 的 <manifest> 标签"; exit 1; }
echo "清单已注入 package=$PKG"

step "2/8 aapt2 编译资源"
"$AAPT2" compile --dir "$WORK/src/res" -o "$OUT/res.zip" \
  || { echo "[FAIL] 资源编译失败"; exit 1; }

step "3/8 aapt2 链接资源与清单（生成 R.java 与基础 APK）"
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM_JAR" \
  --manifest "$WORK/src/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  "$OUT/res.zip" \
  || { echo "[FAIL] 资源链接失败"; exit 1; }

step "4/8 javac 编译 Java 源码"
SRC_FILES=$(find "$WORK/src/java" -name '*.java'; find "$OUT/gen" -name '*.java')
# shellcheck disable=SC2086
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -classpath "$PLATFORM_JAR" \
  -d "$OUT/classes" $SRC_FILES 2>&1 | grep -v "^warning:" || true

PKG_DIR="${PKG//./\/}"
[ -f "$OUT/classes/$PKG_DIR/R.class" ] \
  || { echo "[FAIL] Java 编译未产出 R.class"; exit 1; }
echo "已编译 class 文件: $(find "$OUT/classes" -name '*.class' | wc -l) 个"

step "5/8 d8 转换 classes.dex"
# shellcheck disable=SC2046
"$D8" --release --min-api "$MIN_SDK" --lib "$PLATFORM_JAR" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class') \
  || { echo "[FAIL] d8 转换失败"; exit 1; }
[ -f "$OUT/dex/classes.dex" ] || { echo "[FAIL] 未生成 classes.dex"; exit 1; }

step "6/8 把 classes.dex 打包进 APK"
"$PY" - "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/unsigned.apk" <<'PYEOF'
import shutil, sys, zipfile
base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(base, out)
with zipfile.ZipFile(out, 'a', zipfile.ZIP_DEFLATED) as z:
    if 'classes.dex' in z.namelist():
        raise SystemExit('base.apk 已包含 classes.dex，构建目录可能未清理')
    z.write(dex, 'classes.dex')
print("已写入 classes.dex ->", out)
PYEOF

step "7/8 zipalign 对齐"
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk" \
  || { echo "[FAIL] zipalign 失败"; exit 1; }

step "8/8 签名"
KEYSTORE="${KEYSTORE:-$TC/autoswipe-release.jks}"
if [ ! -f "$KEYSTORE" ]; then
  echo "首次构建，生成自签名密钥: $KEYSTORE"
  # :env 修饰符让口令从环境变量读取，不出现在命令行参数里
  # （避免被 ps 看到、或落进构建日志）
  "$KEYTOOL" -genkeypair -v \
    -keystore "$KEYSTORE" -alias autoswipe \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass:env KEYSTORE_PASS -keypass:env KEYSTORE_PASS \
    -dname "CN=AutoSwipeBall, OU=Personal, O=Personal, L=NA, ST=NA, C=CN" >/dev/null 2>&1 \
    || { echo "[FAIL] 生成密钥失败"; exit 1; }
fi
FINAL="$DIST/${APP_NAME}-${VERSION_NAME}.apk"
"$APKSIGNER" sign \
  --ks "$KEYSTORE" --ks-key-alias autoswipe \
  --ks-pass env:KEYSTORE_PASS --key-pass env:KEYSTORE_PASS \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$FINAL" "$OUT/aligned.apk" \
  || { echo "[FAIL] 签名失败"; exit 1; }

echo
echo "================ 校验产物 ================"
"$APKSIGNER" verify --print-certs "$FINAL" | head -8
echo
ls -lh "$FINAL"
echo
echo "安装： adb install -r \"$FINAL\""
