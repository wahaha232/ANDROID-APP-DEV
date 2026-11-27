@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
set "USE_PROMPT="
set "TARGET="

echo ============================================================================
echo   O-193D 批次核對工具（含欄位 K~M 與內嵌 PDF 報告比對）
echo   預設輸出：output\（修正後檔案）^| reports\（核對報告）^| backup\（修正前備份）
echo ============================================================================
echo.

if "%~1"=="" goto :PROMPT
goto :RUN_CHECK

:PROMPT
echo 請將多個 .xls / .xlsx / .xlsm 拖曳到此視窗，或輸入資料夾路徑後按 Enter。
echo （資料夾只會掃描第一層；需要子資料夾請改用命令列加 --recursive）
set /p "TARGET=> "
if "!TARGET!"=="" (
    echo [錯誤] 未指定檔案或資料夾。
    pause
    exit /b 1
)
set "USE_PROMPT=1"
rem 去除頭尾雙引號
set "TARGET=!TARGET:"=!"
if not exist "!TARGET!" (
    echo [錯誤] 找不到檔案或資料夾：!TARGET!
    pause
    exit /b 1
)

:RUN_CHECK
set "PY_CMD="
where python >nul 2>nul && set "PY_CMD=python"
if not defined PY_CMD (
    where py >nul 2>nul && set "PY_CMD=py -3"
)
if not defined PY_CMD (
    echo [錯誤] 找不到 Python，請先安裝 Python 3 並確認已加入 PATH。
    pause
    exit /b 1
)

echo [執行中] 正在批次核對...
echo.
if defined USE_PROMPT (
    %PY_CMD% "!SCRIPT_DIR!o193d_checker.py" "!TARGET!" --save-report --interactive-fix
) else (
    %PY_CMD% "!SCRIPT_DIR!o193d_checker.py" %* --save-report --interactive-fix
)
set "RC=!ERRORLEVEL!"
echo.

if "!RC!"=="0" (
    echo [結果] 批次核對／修正完成，所有檔案無異常。
    pause
    exit /b 0
)
if "!RC!"=="1" (
    echo [結果] 批次核對有檔案執行失敗，請檢查上方批次摘要。
    pause
    exit /b 1
)
if "!RC!"=="3" (
    echo [結果] 批次核對完成，但有需人工確認的項目。
    pause
    exit /b 0
)
if "!RC!"=="2" (
    echo [結果] 批次中尚有可自動修正項目未處理，請查看批次摘要。
    pause
    exit /b 0
)

echo [結果] 發生未預期錯誤（RC=!RC!）。
pause
exit /b !RC!
