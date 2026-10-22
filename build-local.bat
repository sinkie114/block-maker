@echo off
setlocal
rem Reuse an existing NBT Maker dependency cache: same Minecraft, Loom and Fabric versions.
if exist "E:\codex\nbt-gradle\caches" (
    set "GRADLE_USER_HOME=E:\codex\nbt-gradle"
) else (
    set "GRADLE_USER_HOME=%~dp0.block-dev\gradle-user-home"
)
pushd "%~dp0"
if errorlevel 1 exit /b 1
if "%~1"=="" (
    call "%~dp0gradlew.bat" --no-daemon build
) else (
    call "%~dp0gradlew.bat" --no-daemon %*
)
set "BLOCK_BUILD_EXIT=%ERRORLEVEL%"
popd
exit /b %BLOCK_BUILD_EXIT%
