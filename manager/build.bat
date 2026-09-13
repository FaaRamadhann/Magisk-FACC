@echo off
rem ============================================================
rem  build.bat — Build FACC Manager APK TANPA Android Studio/Gradle
rem  (cukup JDK + Android SDK build-tools, meniru Example-Build:
rem   https://github.com/FaaRamadhann/Example-Build/blob/main/ex-build.bat)
rem
rem  Cara pakai (dari folder ini, sejajar AndroidManifest.xml):
rem      build.bat
rem
rem  Struktur project:
rem      AndroidManifest.xml
rem      src\com\faa\facc\*.java
rem      res\drawable\icon.png
rem
rem  Hasil:
rem    build\manager.apk  (signed, hasil build mentah)
rem    manager.apk        (copy siap include ke module zip)
rem ============================================================
setlocal

rem ---------------- KONFIG (ubah sesuai environment) ----------------
set "APP_NAME=manager"
set "PACKAGE=com.faa.facc"
set "MIN_SDK=24"
set "BT=C:\AndroidSDK\build-tools\35.0.0"
set "PLAT=C:\AndroidSDK\platforms\android-34\android.jar"
set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.10"
set "KEYSTORE=debug.keystore"
set "KEY_ALIAS=faacc"
set "STOREPASS=android"
set "KEYPASS=android"
rem -------------- akhir KONFIG (jangan ubah ke bawah) ------------

set "JAVAC=%JAVA_HOME%\bin\javac.exe"
set "KEYTOOL=%JAVA_HOME%\bin\keytool.exe"
if not exist build mkdir build
rem Bersihkan obj/dex dulu (cegah class basi, mis. sisa package lama, ikut ke dex)
if exist build\obj rmdir /s /q build\obj
if exist build\dex rmdir /s /q build\dex
mkdir build\obj
mkdir build\dex

echo [1/6] kumpulkan source...
del /q build\sources.txt 2>nul
for /R src %%f in (*.java) do echo %%f>> build\sources.txt
if errorlevel 1 exit /b 1

echo [2/6] javac...
"%JAVAC%" --release 8 -classpath "%PLAT%" -d build\obj @build\sources.txt
if errorlevel 1 exit /b 1

echo [3/6] d8 (java -^> dex)...
set CLASSES=
for /R build\obj %%f in (*.class) do call set CLASSES=%%CLASSES%% "%%f"
call "%BT%\d8.bat" --min-api %MIN_SDK% --lib "%PLAT%" --output build\dex %CLASSES%
if errorlevel 1 exit /b 1

echo [4/6] aapt package...
if exist res ( set "RESFLAG=-S res" ) else ( set "RESFLAG=" )
"%BT%\aapt.exe" package -f -M AndroidManifest.xml %RESFLAG% -I "%PLAT%" -F build\unsigned.apk
if errorlevel 1 exit /b 1
cd build\dex
"%BT%\aapt.exe" add ..\unsigned.apk classes.dex
if errorlevel 1 exit /b 1
cd ..\..

echo [5/6] keystore (sekali saja, lalu dipakai terus)...
echo PENTING: backup file %KEYSTORE% — update APK wajib pakai key yang sama!
if not exist %KEYSTORE% "%KEYTOOL%" -genkeypair -keystore %KEYSTORE% -alias %KEY_ALIAS% -keyalg RSA -keysize 2048 -validity 10950 -storepass %STOREPASS% -keypass %KEYPASS% -dname "CN=FACC Manager"
if errorlevel 1 exit /b 1

echo [6/6] zipalign + apksigner...
"%BT%\zipalign.exe" -f 4 build\unsigned.apk build\aligned.apk
if errorlevel 1 exit /b 1
call "%BT%\apksigner.bat" sign --ks %KEYSTORE% --ks-key-alias %KEY_ALIAS% --ks-pass pass:%STOREPASS% --key-pass pass:%KEYPASS% --out build\%APP_NAME%.apk build\aligned.apk
if errorlevel 1 exit /b 1
call "%BT%\apksigner.bat" verify build\%APP_NAME%.apk
if errorlevel 1 exit /b 1

rem Copy hasil ke root manager agar ikut ke-pack module zip.
copy /y "build\%APP_NAME%.apk" "%APP_NAME%.apk" >nul
if errorlevel 1 exit /b 1

echo.
echo SELESAI: build\%APP_NAME%.apk + %APP_NAME%.apk (siap include module)
endlocal
