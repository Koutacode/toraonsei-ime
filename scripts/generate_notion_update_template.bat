@echo off
setlocal

python "%~dp0generate_notion_update_template.py" %*
if errorlevel 1 (
  echo [error] failed to generate notion template
  exit /b 1
)
