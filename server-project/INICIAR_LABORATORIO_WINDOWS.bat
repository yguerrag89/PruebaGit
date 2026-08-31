@echo off
setlocal
set "LAB_HOME=%LOCALAPPDATA%\IluboxLabV012R2"
if not exist "%LAB_HOME%\venv\Scripts\python.exe" (
  echo Preparando el laboratorio por primera vez...
  py -3 -m venv "%LAB_HOME%\venv" || goto :python_error
  "%LAB_HOME%\venv\Scripts\python.exe" -m pip install --disable-pip-version-check -r "%~dp0requirements.txt" || goto :install_error
)
set "ILUBOX_LAB_DIR=%LAB_HOME%\datos"
set "ILUBOX_LAB_PORT=8876"
set "ILUBOX_LAB_ORIGIN=http://127.0.0.1:8876"
echo El laboratorio se abrira en este equipo. Para cerrarlo, vuelva aqui y presione Ctrl+C.
echo Direccion de esta revision: http://127.0.0.1:8876
"%LAB_HOME%\venv\Scripts\python.exe" "%~dp0start_lab.py"
exit /b %errorlevel%
:python_error
echo No se encontro Python 3. Instale Python desde python.org y active Add Python to PATH.
pause
exit /b 1
:install_error
echo No se pudieron instalar los componentes. Revise Internet y vuelva a ejecutar.
pause
exit /b 1
