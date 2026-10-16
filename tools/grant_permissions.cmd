@echo off
chcp 936 >nul 2>&1
setlocal enabledelayedexpansion
title 自动上滑助手 - 一次性 ADB 授权

echo ============================================================
echo   自动上滑助手 · 一次性 ADB 授权
echo   作用：让应用在无障碍被系统清理后能自动恢复，无需再手动开
echo ============================================================
echo.

set "PKG=com.autoswipe.ball"
set "SVC=com.autoswipe.ball/com.autoswipe.ball.service.AutoSwipeAccessibilityService"
set "PERM=android.permission.WRITE_SECURE_SETTINGS"
set "APK=%~dp0..\dist\AutoSwipeBall-1.0.1.apk"
set "GRANTED=no"
set "LISTED=no"

rem ---------- 定位 adb ----------
set "ADB_EXE="
if exist "E:/workbuddy-toolchain/android-sdk/platform-tools/adb.exe" set "ADB_EXE=E:/workbuddy-toolchain/android-sdk/platform-tools/adb.exe"
if not defined ADB_EXE if defined ADB if exist "%ADB%" set "ADB_EXE=%ADB%"
if not defined ADB_EXE if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB_EXE=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB_EXE for %%i in (adb.exe) do if not "%%~$PATH:i"=="" set "ADB_EXE=%%~$PATH:i"
if not defined ADB_EXE goto NOADB
echo [1/5] adb 位置：!ADB_EXE!
call "%ADB_EXE%" start-server >nul 2>&1

rem ---------- 检查设备 ----------
set "STATE="
for /f "delims=" %%s in ('call "%ADB_EXE%" get-state 2^>nul') do set "STATE=%%s"
if /i "!STATE!"=="device" goto DEVOK
echo [2/5] 设备状态：!STATE!
echo.
echo   [X] 没有检测到已授权的设备，请检查：
echo       1. 手机「开发者选项 - USB 调试」是否已打开
echo       2. 数据线是否已插好（插上后手机通知栏应有「正在通过 USB 充电/传输文件」）
echo       3. 手机上是否弹出过「允许 USB 调试吗？」并点了「允许」
if /i "!STATE!"=="unauthorized" echo       当前状态是「未授权」——请看手机屏幕，点「允许」
if /i "!STATE!"=="offline" echo       当前状态是「离线」——拔掉数据线重新插一次
echo       4. 手机上把 USB 用途改成「传输文件 / MTP」（vivo/iQOO 常有此要求）
goto END

:DEVOK
echo [2/5] 设备已连接

rem ---------- 应用是否已安装 ----------
set "INST="
for /f "delims=" %%p in ('call "%ADB_EXE%" shell pm list packages %PKG% 2^>nul') do set "INST=%%p"
if "!INST!"=="" goto NOAPP
echo [3/5] 应用已安装：!INST!

rem ---------- 第 4 步：校验可授予性并授予权限 ----------
echo [4/5] 授予 WRITE_SECURE_SETTINGS
set "VER="
for /f "tokens=2 delims==" %%v in ('call "%ADB_EXE%" shell dumpsys package %PKG% ^| findstr /i "versionName="') do set "VER=%%v"
if defined VER echo       手机上的版本：!VER!
if not defined VER echo       手机上的版本：读取失败，继续尝试

rem 关键判断：只有 APK 自己在清单里声明过这个权限，系统才允许 pm grant。
rem 声明过的话，dumpsys 的 requested permissions 里一定能搜到这个字符串。
set "PROBE="
for /f "delims=" %%g in ('call "%ADB_EXE%" shell dumpsys package %PKG% ^| findstr /i "WRITE_SECURE_SETTINGS"') do set "PROBE=%%g"
if not defined PROBE goto NOTDECLARED

call "%ADB_EXE%" shell pm grant %PKG% %PERM%
if errorlevel 1 echo       [i] adb 返回了非 0，请留意上面是否有报错信息
if not errorlevel 1 echo       （adb 成功时通常不输出内容，这是正常的）

rem 判定要特别小心：同一条权限在 dumpsys 里可能有多行 ——
rem   机主空间：android.permission.WRITE_SECURE_SETTINGS: granted=true
rem   XSpace  ：android.permission.WRITE_SECURE_SETTINGS: granted=false, userId=666
rem   应用分身：android.permission.WRITE_SECURE_SETTINGS: granted=false, userId=999
rem 后两行是「别的用户空间里没装本应用」造成的，属正常现象。
rem 旧版脚本用 for /f 只保留最后一行，正好落在应用分身那行 false 上，
rem 于是把「其实已经成功」误报成「授权未成功」。现在只认 granted=true 的那行。
echo       权限明细：
call "%ADB_EXE%" shell dumpsys package %PKG% ^| findstr /i "WRITE_SECURE_SETTINGS:" | findstr /v /i "requested"
set "GRANTED_LINE="
for /f "delims=" %%g in ('call "%ADB_EXE%" shell dumpsys package %PKG% ^| findstr /i /c:"WRITE_SECURE_SETTINGS: granted=true"') do set "GRANTED_LINE=%%g"
if defined GRANTED_LINE set "GRANTED=yes"
if "!GRANTED!"=="yes" echo       [ok] 权限已授予（机主空间）
if "!GRANTED!"=="no" echo       [注意] 没读到 granted=true 的行，但仍继续（应用界面会显示真实状态）

rem ---------- 第 5 步：写回无障碍名单 ----------
echo [5/5] 把本服务写入无障碍启用名单（保留其他服务）
set "CUR="
for /f "delims=" %%c in ('call "%ADB_EXE%" shell settings get secure enabled_accessibility_services 2^>nul') do set "CUR=%%c"
if defined CUR echo       当前名单：!CUR!
if not defined CUR echo       当前名单：读不到（可能是原本为空）
rem 关键：for /f 会保留行尾空白，而组件名（包名/类名）绝不可能含空格。
rem 不归一化的话，拼接后会把原有服务写成「xxx :yyy」这种非法名，反而破坏别人的无障碍条目。
set "CUR=!CUR: =!"
set "NEW=!CUR!"
if /i "!CUR!"=="null" set "NEW="
echo !NEW!|findstr /i /c:"!SVC!" >nul
if not errorlevel 1 goto SKIPAPPEND
if "!NEW!"=="" set "NEW=!SVC!"
if not "!NEW!"=="" if not "!NEW!"=="!SVC!" set "NEW=!NEW!:!SVC!"
call "%ADB_EXE%" shell settings put secure enabled_accessibility_services "!NEW!"
echo       写入值：!NEW!
:SKIPAPPEND
call "%ADB_EXE%" shell settings put secure accessibility_enabled 1

rem ---------- 端到端校验：回读名单，确认本服务真的在里面 ----------
set "AFTER="
for /f "delims=" %%a in ('call "%ADB_EXE%" shell settings get secure enabled_accessibility_services 2^>nul') do set "AFTER=%%a"
set "AFTER=!AFTER: =!"
echo !AFTER!|findstr /i /c:"!SVC!" >nul
if not errorlevel 1 set "LISTED=yes"
echo.
echo ============== 回读校验（以手机上的实际值为准）==============
echo 无障碍启用名单：
call "%ADB_EXE%" shell settings get secure enabled_accessibility_services
echo 无障碍总开关（应为 1）：
call "%ADB_EXE%" shell settings get secure accessibility_enabled
if "!LISTED!"=="yes" echo [ok] 名单中已包含本服务
if "!LISTED!"=="no" echo [注意] 名单中仍然没有本服务——写入被系统拒绝

echo.
echo ============================================================
if "!GRANTED!"=="yes" if "!LISTED!"=="yes" echo   结论：成功！以后无障碍被系统关掉会自动恢复。
if "!GRANTED!"=="no" echo   结论：授权未成功。请把本窗口全部输出发给我。
if "!GRANTED!"=="yes" if "!LISTED!"=="no" echo   结论：权限拿到了，但写名单被拒。请把输出发给我。
echo ============================================================
echo.
echo 接下来：打开手机上的「自动上滑助手」，红色卡片里应显示
echo   「已获得自动恢复授权，无障碍被关闭时会自动写回。」
goto END

:NOTDECLARED
echo.
echo ============================================================
echo   [X] 手机上这个版本的 APK 里没有声明 WRITE_SECURE_SETTINGS，
echo       系统因此不允许授予（这是 Android 的规则：权限必须先声明才谈得上授予）。
echo.
echo   解决办法：安装 1.0.1 之后重跑本脚本（1.0.1 才加的这项声明）。
echo   APK 位置：
if exist "!APK!" echo       !APK!
if not exist "!APK!" echo       （找不到 !APK!，请检查 dist 目录）
echo.
set "ANS="
set /p "ANS=   现在自动安装 1.0.1？（y=安装 / 直接回车=退出）: "
if /i "!ANS!"=="y" goto DOINSTALL
echo.
echo   已取消。你自己装好 1.0.1 后再重跑本脚本即可。
goto END

:DOINSTALL
if not exist "!APK!" goto NOAPKFILE
echo.
echo   正在安装（手机上若提示「是否安装」请点允许）...
call "%ADB_EXE%" install -r "!APK!"
echo.
echo   安装完成后，请重新运行本脚本以完成授权。
goto END

:NOAPKFILE
echo   [X] 找不到 APK 文件，请检查路径是否正确。
goto END

:NOADB
echo [X] 找不到 adb.exe。
echo     办法一：把 adb 所在目录加进 PATH 后重开本窗口
echo     办法二：在运行本脚本前先执行  set ADB=你的adb完整路径
goto END

:NOAPP
echo [X] 手机上还没安装「自动上滑助手」。
echo     先安装：把工程 dist 目录里的 AutoSwipeBall-1.0.1.apk 拷到手机安装，
echo     或在本窗口执行（把 APK 文件拖进本窗口即可自动补全路径）：
echo         "!ADB_EXE!" install -r 
goto END

:END
echo.
echo 按任意键关闭本窗口...
pause >nul
endlocal
