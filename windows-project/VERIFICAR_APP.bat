@echo off
cd /d "%~dp0"
py -B test_v22.py
if errorlevel 1 goto :error
py -B test_wms_putaway.py
if errorlevel 1 goto :error
py -B test_strict_pda_exchange.py
if errorlevel 1 goto :error
py -B test_continuous_exchange.py
if errorlevel 1 goto :error
py -B test_temporary_exchange.py
if errorlevel 1 goto :error
py -B test_streamlit_app.py
if errorlevel 1 goto :error
echo.
echo TODAS LAS PRUEBAS TERMINARON CORRECTAMENTE.
pause
exit /b 0

:error
echo.
echo SE DETECTO UN ERROR. No use la plantilla hasta corregirlo.
pause
exit /b 1
