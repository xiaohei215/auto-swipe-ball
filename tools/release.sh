#!/usr/bin/env bash
# ============================================================================
# 一键发版：改版本号 -> 构建 APK -> 生成 dist/version.json -> 提交 -> 推送
#
#   用法：
#     KEYSTORE_PASS='<签名口令>' bash tools/release.sh <版本名> ["<更新说明>"] [--no-push]
#
#   例：
#     KEYSTORE_PASS='android' bash tools/release.sh 1.0.3 "新增应用内检查更新"
#
#   做了什么：
#     1. 把版本名写进 tools/build_apk.sh 与 app/build.gradle（两处保持一致）
#     2. versionCode 自动 +1（手机靠它判断新旧，必须单调递增）
#     3. 调用 tools/build_apk.sh 出包
#     4. 计算 APK 的 SHA-256 与字节数，写出 dist/version.json
#        —— 应用内「检查更新」读的就是这个文件
#     5. git commit + push（推送走本仓库已配置好的 SSH）
#
#   为什么更新元数据放在仓库里而不是 GitHub Releases：
#     下载走 gitee/github 的「原始文件」地址即可，不需要 API token，
#     也不必每次发版去网页上点几下 —— 一条 git push 就完成了「发布」。
# ============================================================================
set -euo pipefail

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJ"

TC="${TOOLCHAIN:-E:/workbuddy-toolchain}"

VER="${1:-}"
NOTES="${2:-}"
PUSH=1
if [ "${3:-}" = "--no-push" ]; then
  PUSH=0
fi

if [ -z "$VER" ]; then
  cat <<'EOM'
用法：KEYSTORE_PASS='<签名口令>' bash tools/release.sh <版本名> ["<更新说明>"] [--no-push]

  <版本名>    形如 1.0.3，会写进源码与元数据
  <更新说明>  会显示在手机的更新提示里；留空则取最近一次提交的主题
  --no-push   只在本地产出与提交，不推送（便于先检查一遍）
EOM
  exit 1
fi

if ! printf '%s' "$VER" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "[FAIL] 版本名需形如 1.0.3（三段数字），当前是：$VER"
  exit 1
fi

if [ -z "${KEYSTORE_PASS:-}" ]; then
  echo "[FAIL] 未设置 KEYSTORE_PASS（签名密钥口令）。"
  echo "       例：KEYSTORE_PASS='你的口令' bash tools/release.sh $VER"
  exit 1
fi

PY="${PYTHON:-$(command -v python || command -v python3)}"
if [ -z "$PY" ]; then
  echo "[FAIL] 找不到 python，本脚本用它生成 version.json"
  exit 1
fi

OLD_CODE="$(grep -E '^VERSION_CODE=' tools/build_apk.sh | head -1 | cut -d= -f2)"
# 去掉可能残留的空白/回车（文件若是 CRLF，grep 出来的行尾会带 \r，
# 而 bash 的 $(( )) 遇到 \r 会直接报算术错误）
OLD_CODE="$(printf '%s' "$OLD_CODE" | tr -d '[:space:]')"

if ! printf '%s' "$OLD_CODE" | grep -qE '^[0-9]+$'; then
  echo "[FAIL] 无法从 tools/build_apk.sh 读出 VERSION_CODE（读到的是「$OLD_CODE」）"
  exit 1
fi
NEW_CODE=$((OLD_CODE + 1))

# ---------------------------------------------------------------------------
# 幂等保护
#
# 本脚本的「读版本号 -> +1 -> 写回」是有副作用的。若脚本因故被执行两次
# （中断重跑、误重复执行），versionCode 就会多跳一格。跳号本身对 Android 无害
# （只要求单调递增），但会让版本记录对不上，排查起来很烦。
#
# 所以这里做个判断：如果文件里的版本名已经等于目标版本，说明本次发版已经改过
# 一次，直接沿用现有 versionCode，不再自增。
# ---------------------------------------------------------------------------
CUR_NAME="$(grep -E '^VERSION_NAME=' tools/build_apk.sh | head -1 | cut -d= -f2 \
            | tr -d '[:space:]"')"
if [ "$CUR_NAME" = "$VER" ]; then
  echo "==> [1/6] 版本号：$VER（检测到已是该版本，按「重跑」处理：versionCode 保持 $OLD_CODE 不自增）"
  NEW_CODE="$OLD_CODE"
else
  echo "==> [1/6] 版本号：$VER（versionCode $OLD_CODE -> $NEW_CODE）"
fi
"$PY" - "$VER" "$NEW_CODE" <<'PYEOF'
import io, re, sys

ver, code = sys.argv[1], sys.argv[2]

# newline="" 表示不做换行转换，原样保留文件已有的换行风格
path = "tools/build_apk.sh"
src = io.open(path, encoding="utf-8", newline="").read()
src, n1 = re.subn(r'^VERSION_NAME="[^"]*"', 'VERSION_NAME="%s"' % ver, src, count=1, flags=re.M)
src, n2 = re.subn(r'^VERSION_CODE=\d+', 'VERSION_CODE=%s' % code, src, count=1, flags=re.M)
if not (n1 and n2):
    sys.exit("[FAIL] tools/build_apk.sh 中未找到 VERSION_NAME / VERSION_CODE")
io.open(path, "w", encoding="utf-8", newline="").write(src)

path = "app/build.gradle"
src = io.open(path, encoding="utf-8", newline="").read()
src, n3 = re.subn(r'versionCode \d+', 'versionCode %s' % code, src, count=1)
src, n4 = re.subn(r'versionName "[^"]*"', 'versionName "%s"' % ver, src, count=1)
if not (n3 and n4):
    sys.exit("[FAIL] app/build.gradle 中未找到 versionCode / versionName")
io.open(path, "w", encoding="utf-8", newline="").write(src)

print("       已同步 tools/build_apk.sh 与 app/build.gradle")
PYEOF

echo
echo "==> [2/6] 构建 APK"
bash tools/build_apk.sh

APK="dist/AutoSwipeBall-$VER.apk"
if [ ! -f "$APK" ]; then
  echo "[FAIL] 构建结束但找不到 $APK"
  exit 1
fi
echo "       产物：$APK"

echo
echo "==> [3/6] 生成 dist/version.json"
"$PY" - "$APK" "$VER" "$NEW_CODE" "$NOTES" <<'PYEOF'
import hashlib, io, json, os, subprocess, sys

apk, ver, code, notes = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
with open(apk, "rb") as fh:
    data = fh.read()

if not notes.strip():
    try:
        notes = subprocess.check_output(
            ["git", "log", "-1", "--pretty=%s"],
            stderr=subprocess.DEVNULL).decode("utf-8").strip()
    except Exception:
        notes = ""

meta = {
    "versionCode": code,
    "versionName": ver,
    "apkFileName": os.path.basename(apk),
    "sizeBytes": len(data),
    "sha256": hashlib.sha256(data).hexdigest(),
    "notes": notes,
}
io.open("dist/version.json", "w", encoding="utf-8", newline="\n").write(
    json.dumps(meta, ensure_ascii=False, indent=2) + "\n")

print("       versionCode=%d  sizeBytes=%d" % (code, len(data)))
print("       sha256=%s" % meta["sha256"])
print("       notes=%s" % (notes if notes else "(空)"))
PYEOF

echo
echo "==> [4/6] 交叉校验：APK 内嵌的版本号必须与元数据一致"
AAPT2="$TC/android-sdk/build-tools/34.0.0/aapt2.exe"
if [ -f "$AAPT2" ]; then
  APK_CODE="$("$AAPT2" dump badging "$APK" 2>/dev/null | head -1 \
              | sed -E "s/.*versionCode='([0-9]+)'.*/\1/")"
  META_CODE="$("$PY" -c "import io,json;print(json.load(io.open('dist/version.json',encoding='utf-8'))['versionCode'])")"
  echo "       APK 内嵌 versionCode = $APK_CODE"
  echo "       version.json         = $META_CODE"
  if [ "$APK_CODE" != "$META_CODE" ]; then
    cat >&2 <<EOM

[FAIL] 两者不一致，已中止提交。

       手机是靠 version.json 里的 versionCode 判断新旧的：
       若它比 APK 里实际的小，装了新版也仍会被提示「有新版本」；
       若比它大，则永远提示「已是最新」，更新推不下去。

       请检查 tools/build_apk.sh 与 app/build.gradle 的版本号是否同步，
       或先 git checkout 掉本次改动后重来。
EOM
    exit 1
  fi
  echo "       一致，可以发布"
else
  echo "       （未找到 aapt2，跳过校验）"
fi

echo
echo "==> [5/6] 清理临时文件"
rm -f dist/*.idsig
echo "       已删除 dist/*.idsig"

echo
echo "==> [5/6] 提交"
git add -A
if git diff --cached --quiet; then
  echo "       没有需要提交的改动"
else
  git commit -m "发布 v$VER（versionCode $NEW_CODE）" -m "${NOTES:-例行更新}" >/dev/null
  echo "       已提交：$(git log -1 --pretty=%h) $(git log -1 --pretty=%s)"
fi

echo
echo "==> [6/6] 推送"
if [ "$PUSH" = "1" ]; then
  git push
  echo "       已推送。手机端下次检查更新即可看到 v$VER。"
else
  echo "       已按 --no-push 跳过。手动推送：git push"
fi

echo
echo "完成。"
echo "  APK      : $APK"
echo "  元数据   : dist/version.json"
echo "  版本     : v$VER (versionCode $NEW_CODE)"
