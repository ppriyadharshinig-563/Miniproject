# ========================================================
# Smart College Bus System - Backend Server Launcher
# ========================================================

Write-Host "=======================================================" -ForegroundColor Cyan
Write-Host "   Starting Smart College Bus Backend Server           " -ForegroundColor Cyan
Write-Host "=======================================================" -ForegroundColor Cyan

$port = 8080

# 1. Automatically detect and free port 8080 if another process is holding it
$connections = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
if ($connections) {
    Write-Host "[INFO] Port $port is currently in use. Freeing port $port..." -ForegroundColor Yellow
    foreach ($conn in $connections) {
        $procId = $conn.OwningProcess
        if ($procId -gt 0) {
            try {
                Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
                Write-Host "  -> Terminated previous process (PID: $procId)" -ForegroundColor Green
            } catch {
                Write-Host "  -> Could not terminate process PID: $procId" -ForegroundColor DarkGray
            }
        }
    }
    Start-Sleep -Milliseconds 800
}

# 2. Compile BackendServer.java
Write-Host "`n[1/2] Compiling BackendServer.java..." -ForegroundColor Yellow
$classpath = ".;lib/mysql-connector-j-26.7.0/mysql-connector-j-26.7.0.jar"
javac -cp $classpath BackendServer.java

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Compilation failed!" -ForegroundColor Red
    exit 1
}
Write-Host "  -> Compilation successful!" -ForegroundColor Green

# 3. Start BackendServer
Write-Host "`n[2/2] Launching BackendServer on port $port..." -ForegroundColor Yellow
Write-Host "  -> Transit Portal: http://localhost:$port/" -ForegroundColor Cyan
Write-Host "  -> Press Ctrl+C in this terminal anytime to stop the server.`n" -ForegroundColor DarkGray

java -cp $classpath BackendServer $port
