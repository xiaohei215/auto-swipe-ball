#!/usr/bin/env bash
# ============================================================================
# 一次性授权脚本：让「自动上滑助手」在被系统清理后能自动恢复无障碍服务
#
#   bash tools/grant_permissions.sh
#
# 做的事：
#   1) 给应用授予 WRITE_SECURE_SETTINGS（该权限 protectionLevel 含 development，
#      只能由 ADB 授予，授予后长期有效，除非卸载重装）
#   2) 顺手把本无障碍服务加进系统启用名单（保留名单里其他服务的条目）
#
# 需要：手机已开启「开发者选项 → USB 调试」，并用数据线连上电脑。
# 该授权只影响本应用自己的无障碍开关读写，不涉及其他权限或数据。
# ============================================================================
set -uo pipefail

PKG="com.autoswipe.ball"
SVC="$PKG/$PKG.service.AutoSwipeAccessibilityService"
TC="${TOOLCHAIN:-E:/workbuddy-toolchain}"

# 依次尝试：环境变量指定 → 工具链自带 → PATH
if [ -n "${ADB:-}" ] && [ -x "${ADB:-}" ]; then
  ADB_BIN="$ADB"
elif [ -x "$TC/android-sdk/platform-tools/adb.exe" ]; then
  ADB_BIN="$TC/android-sdk/platform-tools/adb.exe"
elif command -v adb >/dev/null 2>&1; then
  ADB_BIN="$(command -v adb)"
else
  echo "[FAIL] 找不到 adb。请安装 Android SDK platform-tools，或用 ADB=<路径> 指定。"
  exit 1
fi

echo "使用 adb: $ADB_BIN"
echo

run() { "$ADB_BIN" "$@"; }

echo "==> 检查设备连接"
DEVICES=$(run devices | sed '1d' | grep -c "device$")
run devices -l
if [ "$DEVICES" -eq 0 ]; then
  echo
  echo "[FAIL] 没有检测到已授权的设备。请："
  echo "       1) 手机开启「设置 → 关于手机 → 连点版本号 7 次」进入开发者模式"
  echo "       2) 打开「开发者选项 → USB 调试」"
  echo "       3) 插上数据线，手机上弹出「允许 USB 调试吗？」时点允许"
  exit 1
fi
if [ "$DEVICES" -gt 1 ]; then
  echo "[WARN] 检测到多台设备，将作用于默认设备；如需指定请用：adb -s <序列号>"
fi
echo

echo "==> 1/3 授予 WRITE_SECURE_SETTINGS"
if run shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS; then
  echo "    已授予"
else
  echo "    [FAIL] 授予失败。请确认应用已安装且包名为 $PKG"
  exit 1
fi

echo
echo "==> 2/3 把本无障碍服务加入系统启用名单（保留其他服务）"
CUR=$(run shell settings get secure enabled_accessibility_services | tr -d '\r')
echo "    当前名单: ${CUR:-<空>}"
case "$CUR" in
  *"$SVC"*)
    echo "    名单中已包含本服务，跳过"
    ;;
  *)
    if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then
      NEW="$SVC"
    else
      NEW="$CUR:$SVC"
    fi
    run shell settings put secure enabled_accessibility_services "$NEW"
    echo "    已写入: $NEW"
    ;;
esac
run shell settings put secure accessibility_enabled 1
echo "    无障碍总开关已置为 1"

echo
echo "==> 3/3 校验"
echo "    --- 无障碍设置状态 ---"
run shell dumpsys accessibility 2>/dev/null | grep -iE "mIsEnabled|Enabled services|Bound services" | head -6
echo "    --- 应用侧权限确认（应输出 granted=true）---"
run shell dumpsys package "$PKG" 2>/dev/null | grep -A1 "WRITE_SECURE_SETTINGS" | head -4

echo
echo "完成。之后无障碍服务被系统清理掉时，打开本应用会自动恢复，无需再手动设置。"
echo "提示：授权会在卸载重装后失效，重装后重跑一次本脚本即可。"
