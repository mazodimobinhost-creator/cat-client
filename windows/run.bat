@echo off
title Cat Client
cd /d "%~dp0"
where pyw >nul 2>nul && (
  start "" pyw main.py
  exit /b 0
)
where pythonw >nul 2>nul && (
  start "" pythonw main.py
  exit /b 0
)
python main.py
