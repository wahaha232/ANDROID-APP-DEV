@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
set "EXCEL_FILE=%~1"

echo ============================================================================
echo   O-193D 環境管理物質部件展開表 — 自動核對與修正工具（含欄位 K~M 比對）
echo   預設輸出：output\（修正後檔案）^| reports\（核對報告）^| backup\（修正前備份）
echo ============================================================================
echo.

if "!EXCEL_FILE!"=="" (
    echo 請將 .xls / .xlsx / .xlsm 檔案拖曳到此視窗，或直接輸入完整路徑後按 Enter：
    set /p "EXCEL_FILE=> "
)

if "!EXCEL_FILE!"=="" (
    echo [錯誤] 未指定 Excel 檔案路徑。
    pause
    exit /b 1
)

rem 去除路徑頭尾雙引號
set "EXCEL_FILE=!EXCEL_FILE:"=!"

if not exist "!EXCEL_FILE!" (
    echo [錯誤] 找不到指定的檔案：!EXCEL_FILE!
    pause
    exit /b 1
)

rem 優先使用 python，找不到時改用 Windows 的 py -3
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

echo [執行中] 正在提取內嵌 PDF 並核對：!EXCEL_FILE!
echo.

%PY_CMD% "!SCRIPT_DIR!o193d_checker.py" "!EXCEL_FILE!" --save-report --interactive-fix
set "RC=!ERRORLEVEL!"

echo.
if "!RC!"=="0" (
    echo [結果] 核對／修正完成，無異常項目。
    pause
    exit /b 0
)
if "!RC!"=="1" (
    echo [結果] 執行過程發生錯誤，請檢查上方訊息。
    pause
    exit /b 1
)
if "!RC!"=="3" (
    echo [結果] 發現需人工確認之異常或警告，無可自動修正之欄位。
    pause
    exit /b 0
)
if "!RC!"=="2" (
    echo [結果] 尚有可自動修正項目未處理，請查看核對報告。
    pause
    exit /b 0
)

echo [結果] 發生未預期錯誤（RC=!RC!）。
pause
exit /b !RC!
