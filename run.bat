@echo off
rem DBMS launcher (cmd). On Windows prefer run.ps1 (Unicode-safe).
rem usage: run.bat <create|open|client|test> [args]
setlocal
set "ROOT=%~dp0"
set "JAR=%ROOT%target\dbms-1.0.0.jar"

if not exist "%JAR%" (
  echo ==^> building jar ...
  pushd "%ROOT%"
  call mvn -q -DskipTests package
  if errorlevel 1 ( popd & echo mvn package failed & exit /b 1 )
  popd
)

set "CMD=%~1"
if "%CMD%"=="" goto usage

if "%CMD%"=="create" (
  java -cp "%JAR%" com.course.dbms.server.ServerLauncher create %2 9999
  goto end
)
if "%CMD%"=="open" (
  java -cp "%JAR%" com.course.dbms.server.ServerLauncher open %2 9999
  goto end
)
if "%CMD%"=="client" (
  java -cp "%JAR%" com.course.dbms.client.ClientLauncher %2 %3 %4 %5 %6 %7 %8 %9
  goto end
)
if "%CMD%"=="test" (
  pushd "%ROOT%"
  call mvn -q test
  popd
  goto end
)

:usage
echo usage: run.bat ^<create^|open^|client^|test^> [args]
:end
endlocal
