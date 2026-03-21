@echo off
setlocal
cd /d %~dp0

echo Removing stale client-only files from src\main\java if they exist...
if exist src\main\java\org\example2\solips\EnchantScreenObserver.java del /f /q src\main\java\org\example2\solips\EnchantScreenObserver.java
if exist src\main\java\org\example2\solips\ManualResetKeyHandler.java del /f /q src\main\java\org\example2\solips\ManualResetKeyHandler.java
if exist src\main\java\org\example2\solips\MultiItemPreviewOverlay.java del /f /q src\main\java\org\example2\solips\MultiItemPreviewOverlay.java
if exist src\main\java\org\example2\solips\ClientFeatureToggle.java del /f /q src\main\java\org\example2\solips\ClientFeatureToggle.java
if exist src\main\java\org\example2\solips\SolipsClient.java del /f /q src\main\java\org\example2\solips\SolipsClient.java
if exist src\main\java\org\example2\solips\EnchantSeedCracker.java del /f /q src\main\java\org\example2\solips\EnchantSeedCracker.java

echo Done.
echo If you extracted this zip over an old folder, these stale files were the reason compileJava kept failing.
endlocal
