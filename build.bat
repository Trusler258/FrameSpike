@echo off
setlocal enableextensions
rem FrameSpike build script - JDK 17 only, no Gradle, no JDK 8 needed.
set "ROOT=%~dp0"
set "JDK=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot\bin"

if not exist "%JDK%\javac.exe" (
  echo [ERROR] javac.exe not found at "%JDK%"
  echo         Edit this script and set JDK to your JDK 17 bin directory.
  pause
  exit /b 1
)

cd /d "%ROOT%"
if exist out\main rmdir /s /q out\main
if exist out\test rmdir /s /q out\test
if exist out\selftest rmdir /s /q out\selftest
if exist out\cmdtest rmdir /s /q out\cmdtest
mkdir out\main
if not exist out\stub mkdir out\stub
mkdir out\test
if not exist dist mkdir dist

set "CPAPI=%ROOT%lib\stubapi"
set "CPMAIN=%ROOT%lib\stubapi;%ROOT%out\main;%ROOT%out\test"

echo ===== 1/5 compile main =====
"%JDK%\javac.exe" --release 8 -encoding UTF-8 -nowarn -cp "%CPAPI%" -d "%ROOT%out\main" src\framespike\*.java
if errorlevel 1 goto fail

echo ===== 2/5 compile stubs =====
"%JDK%\javac.exe" --release 8 -encoding UTF-8 -nowarn -d "%ROOT%out\stub" stubsrc\net\minecraft\client\Minecraft.java stubsrc\net\minecraft\client\renderer\EntityRenderer.java stubsrc\net\minecraft\command\ICommand.java stubsrc\net\minecraft\command\ICommandSender.java stubsrc\net\minecraft\util\text\ITextComponent.java stubsrc\net\minecraft\util\text\ChatComponentText.java stubsrc\net\minecraftforge\client\ClientCommandHandler.java stubsrc\org\lwjgl\opengl\GL11.java
if errorlevel 1 goto fail

echo ===== 3/5 compile tests =====
"%JDK%\javac.exe" --release 8 -encoding UTF-8 -nowarn -cp "%CPAPI%;%ROOT%out\main" -d "%ROOT%out\test" test\SelfTest.java test\CmdTest.java test\RoundTrip.java test\ReportGen.java test\JsonDump.java
if errorlevel 1 goto fail

echo ===== 4/5 SelfTest (out\stub NOT on classpath on purpose) =====
"%JDK%\java.exe" -cp "%CPMAIN%" SelfTest
if errorlevel 1 goto fail

echo ===== 4.2/5 CmdTest (needs out\stub for GL11 + fake MC classes) =====
"%JDK%\java.exe" -cp "%CPMAIN%;%ROOT%out\stub" CmdTest
if errorlevel 1 goto fail

echo ===== 4.5/5 RoundTrip on real 1.8.9 classes =====
set "VC=%APPDATA%\.minecraft\versions\1.8.9\1.8.9.jar"
if exist "%VC%" (
  "%JDK%\java.exe" -cp "%CPMAIN%" RoundTrip "%VC%" 40
  if errorlevel 1 goto fail
) else (
  echo   [SKIP] vanilla jar not found: "%VC%"
)

echo ===== 5/5 package jar =====
if exist dist\FrameSpike-Forge-1.8.9-1.12.2-0.7.0.jar del dist\FrameSpike-Forge-1.8.9-1.12.2-0.7.0.jar
"%JDK%\jar.exe" cfm dist\FrameSpike-Forge-1.8.9-1.12.2-0.7.0.jar MANIFEST.MF -C "%ROOT%out\main" . -C "%ROOT%resources" .
if errorlevel 1 goto fail

echo.
echo ================================
echo  BUILD OK  -^>  dist\FrameSpike-Forge-1.8.9-1.12.2-0.7.0.jar
echo  copy it into:
echo    %USERPROFILE%\.lunarclient\profiles\1.8\mods\forge-1.8.9\
echo ================================
pause
exit /b 0

:fail
echo.
echo [FAILED] see messages above.
pause
exit /b 1
