@echo off
echo =======================================================
echo    Starting Smart College Bus Backend Server
echo =======================================================

echo [1/2] Compiling BackendServer.java...
javac -cp ".;lib/mysql-connector-j-26.7.0/mysql-connector-j-26.7.0.jar" BackendServer.java
if %errorlevel% neq 0 (
    echo [ERROR] Compilation failed!
    pause
    exit /b %errorlevel%
)
echo [OK] Compilation successful!

echo [2/2] Launching BackendServer on port 8080...
echo Portal URL: http://localhost:8080/
echo Press Ctrl+C anytime to stop the server.
echo.

java -cp ".;lib/mysql-connector-j-26.7.0/mysql-connector-j-26.7.0.jar" BackendServer 8080
