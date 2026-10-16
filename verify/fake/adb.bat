@echo off
rem 假 adb（有状态）：按命令返回预设输出，用于无手机压测 grant_permissions.cmd
rem FAKE_VER 控制手机上的版本：1.0.1=已声明权限；1.0.0=未声明权限
rem settings put 会把值写进同目录 state_*.txt，put 后再 get 能读回 —— 用于验证脚本的端到端回读校验
rem
rem 真机 iQOO Neo9 Pro（Android 16）实测：dumpsys 里同一条权限会有 3 行 ——
rem   机主空间 granted=true（无 userId 后缀）
rem   XSpace   granted=false, userId=666
rem   应用分身 granted=false, userId=999
rem 这里刻意把这三行都复现出来，用来守住「不能把成功误报成失败」这条回归。
setlocal enabledelayedexpansion
set "ARGS=%*"
set "ARGS=%ARGS:"=%"
set "VER=%FAKE_VER%"
if "%VER%"=="" set "VER=1.0.1"
set "ST1=%~dp0state_a11y.txt"
set "ST2=%~dp0state_en.txt"

if /i "%ARGS%"=="get-state" ( echo device& goto :eof )
if /i "%ARGS%"=="start-server" goto :eof
if /i "%ARGS%"=="shell pm list packages com.autoswipe.ball" ( echo package:com.autoswipe.ball& goto :eof )
if /i "%ARGS%"=="shell pm grant com.autoswipe.ball android.permission.WRITE_SECURE_SETTINGS" goto :eof

rem dumpsys 要一次输出多行：版本号 + requested 权限 + 三个用户空间的授予状态
if /i "%ARGS%"=="shell dumpsys package com.autoswipe.ball" (
  echo   versionName=!VER!
  if not "!VER!"=="1.0.0" echo     android.permission.WRITE_SECURE_SETTINGS
  if not "!VER!"=="1.0.0" echo   android.permission.WRITE_SECURE_SETTINGS: granted=true
  if not "!VER!"=="1.0.0" echo   android.permission.WRITE_SECURE_SETTINGS: granted=false, userId=666
  if not "!VER!"=="1.0.0" echo   android.permission.WRITE_SECURE_SETTINGS: granted=false, userId=999
  goto :eof
)

if /i "%ARGS%"=="shell settings get secure enabled_accessibility_services" (
  if exist "!ST1!" ( type "!ST1!" ) else ( echo com.other.app/com.other.app.OtherService )
  goto :eof
)
if /i "%ARGS%"=="shell settings put secure enabled_accessibility_services com.other.app/com.other.app.OtherService:com.autoswipe.ball/com.autoswipe.ball.service.AutoSwipeAccessibilityService" (
  >"!ST1!" echo com.other.app/com.other.app.OtherService:com.autoswipe.ball/com.autoswipe.ball.service.AutoSwipeAccessibilityService
  echo OK-APPENDED
  goto :eof
)
if /i "%ARGS%"=="shell settings get secure accessibility_enabled" (
  if exist "!ST2!" ( type "!ST2!" ) else ( echo 0 )
  goto :eof
)
if /i "%ARGS%"=="shell settings put secure accessibility_enabled 1" (
  >"!ST2!" echo 1
  echo OK-ENABLED
  goto :eof
)
