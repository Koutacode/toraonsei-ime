@echo off
setlocal

cd /d "%~dp0\.."
python scripts\update_kana_kanji_dict.py %*
if errorlevel 1 (
  echo [ERROR] 辞書更新に失敗しました
  exit /b 1
)

echo [OK] app\src\main\assets\kana_kanji_base.tsv を更新しました
exit /b 0

