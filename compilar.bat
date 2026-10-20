@echo off
setlocal
cd /d "%~dp0"
echo =============================================
echo   SpawnerSinJugador 1.5.0 - Compilador
echo   Paper 1.21.x  y  Paper 26.x (un solo jar)
echo =============================================
echo.
where javac >nul 2>nul || (echo ERROR: no se encontro javac. Instala un JDK 21 o superior.& pause & exit /b 1)
for /f "tokens=2" %%V in ('javac -version 2^>^&1') do set JAVA_VERSION=%%V
for /f "tokens=1 delims=." %%M in ("%JAVA_VERSION%") do set JAVA_MAJOR=%%M
if not defined JAVA_MAJOR (echo No se pudo detectar la version del JDK. Ejecuta javac -version.& pause & exit /b 1)
if %JAVA_MAJOR% LSS 21 (echo ERROR: se requiere JDK 21 o superior. Version detectada: %JAVA_VERSION%.& pause & exit /b 1)
where mvn >nul 2>nul || (echo ERROR: Maven no esta instalado o no esta en PATH.& pause & exit /b 1)
echo Compilando el plugin...
mvn -U clean package
if errorlevel 1 (echo.& echo ERROR: la compilacion fallo. Copia el error completo para revisarlo.& pause & exit /b 1)
echo.
echo Listo: target\SpawnerSinJugador-1.5.0.jar
pause
