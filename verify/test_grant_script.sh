#!/usr/bin/env bash
# ============================================================================
# 对 tools/grant_permissions.cmd 的无设备回归测试（两个场景）
#
#   bash verify/test_grant_script.sh
#
# 原理：造一个「假 adb」（verify/fake/adb.bat，按命令返回预设输出），
#       再把真实 .cmd 里的 adb 定位块替换成指向这个假 adb，跑完整流程并断言结果。
#       用环境变量 FAKE_VER 模拟手机上装的是哪个版本。
#
# 场景 1（FAKE_VER=1.0.1）：APK 已声明 WRITE_SECURE_SETTINGS → 应走成功路径
# 场景 2（FAKE_VER=1.0.0）：APK 未声明该权限 → 应识别出「无法授予」并引导安装 1.0.1
#
# 背景：这个测试抓到过三个真实问题 ——
#   ① .cmd 里不加 call 直接调用另一个 .bat，控制权不返回、脚本提前结束；
#   ② 变量为空时 `echo !VAR!` 会打印「ECHO 处于关闭状态」，看着像报错；
#   ③ 【真机踩到】权限判定用 for /f 只保留最后一行，而真机 dumpsys 里
#      同一条权限有 3 行（机主 granted=true / XSpace userId=666 false /
#      应用分身 userId=999 false），最后一行恰好是应用分身的 false，
#      于是「其实已经授予成功」被误报成「授权未成功」。
#      实测机型 iQOO Neo9 Pro（V2339A / Android 16）存在 0 / 666 / 999 三个用户空间。
#      修法：只认 "WRITE_SECURE_SETTINGS: granted=true" 那一行。
#      场景 1 的假 adb 会同时输出这三行，用来守住这条回归。
# ============================================================================
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PY="${PYTHON:-python}"
[ -x "$PY" ] || PY="$(command -v python || command -v python3)"

FAIL=0

# 开跑前先清掉上一轮的残留（状态文件 / 上轮输出），避免偶发误判
rm -f verify/fake/state_a11y.txt verify/fake/state_en.txt \
      verify/fake/out_*.txt verify/fake/out.txt verify/fake/test_run.cmd

echo "==> 生成测试副本（真实脚本 -> 指向假 adb）"
"$PY" - <<'PYEOF' || exit 1
import io
src = io.open("tools/grant_permissions.cmd", "rb").read().decode("gbk")
lines, out, skip = src.split("\r\n"), [], False
for ln in lines:
    if ln.startswith('set "ADB_EXE="'):
        out.append('set "ADB_EXE=%~dp0adb.bat"')
        skip = True
        continue
    if skip:
        if ln.startswith('if not defined ADB_EXE') or ln.startswith('if exist "E:'):
            continue
        skip = False
    out.append(ln)
test = "\r\n".join(out)
assert 'set "ADB_EXE=%~dp0adb.bat"' in test, "测试副本未指向假 adb"
io.open("verify/fake/test_run.cmd", "wb").write(test.encode("gbk"))
print("    ok")
PYEOF

run_case() {
  local ver="$1" expect="$2"
  echo
  echo "==> 场景 $ver（$expect）"
  rm -f verify/fake/state_a11y.txt verify/fake/state_en.txt
  export FAKE_VER="$ver"
  printf '\r\n\r\n\r\n\r\n' | cmd //c "verify\\fake\\test_run.cmd" > "verify/fake/out_$ver.txt" 2>&1
  local rc=$?
  [ "$rc" -eq 0 ] || { echo "    [FAIL] 脚本退出码 $rc"; FAIL=1; }
  unset FAKE_VER
}

assert_case() {
  local ver="$1"
  shift
  "$PY" - "$ver" "$@" <<'PYEOF'
import io, sys
ver = sys.argv[1]
want = sys.argv[2:]
text = io.open("verify/fake/out_%s.txt" % ver, "rb").read().decode("gbk", "replace")
bad = []
for item in want:
    kind, _, needle = item.partition(":")
    hit = needle in text
    ok = (hit if kind == "has" else not hit)
    print(("    [ok]   " if ok else "    [FAIL] ") + kind + " " + needle[:60])
    if not ok:
        bad.append(needle)
sys.exit(1 if bad else 0)
PYEOF
  [ $? -ne 0 ] && FAIL=1
}

run_case 1.0.1 "APK 已声明权限，应走成功路径（含多用户空间的干扰行）"
assert_case 1.0.1 \
  "has:[2/5] 设备已连接" \
  "has:[3/5] 应用已安装" \
  "has:手机上的版本：1.0.1" \
  "has:granted=true" \
  "has:granted=false, userId=999" \
  "has:[ok] 权限已授予（机主空间）" \
  "has:写入值：com.other.app/com.other.app.OtherService:com.autoswipe.ball/" \
  "has:OK-ENABLED" \
  "has:[ok] 名单中已包含本服务" \
  "has:结论：成功" \
  "hasnot:结论：授权未成功" \
  "hasnot:ECHO 处于关闭状态" \
  "hasnot:没有声明 WRITE_SECURE_SETTINGS"

run_case 1.0.0 "APK 未声明权限，应识别并引导安装新版本"
assert_case 1.0.0 \
  "has:手机上的版本：1.0.0" \
  "has:没有声明 WRITE_SECURE_SETTINGS" \
  "has:安装 1.0.1 之后重跑本脚本" \
  "has:已取消" \
  "hasnot:写入值" \
  "hasnot:结论：成功" \
  "hasnot:ECHO 处于关闭状态"

echo
if [ "$FAIL" -eq 0 ]; then
  echo "结果: ALL PASS（cmd 授权脚本在模拟设备下两个场景均符合预期）"
else
  echo "结果: FAILED"
fi
exit $FAIL
