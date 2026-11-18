param(
    [Parameter(Mandatory=$true)][string]$Module,
    [Parameter(Mandatory=$true)][string]$JarName,
    [Parameter(Mandatory=$true)][int]$Port
)
$art = 'C:\Users\华为\AppData\Local\Doubao\User Data\Default\.doubao\agent_mode\workspace\.sessions\38454212807548930\agents\s_000Ig3k3nip\artifacts'
$jar = "D:\wms\wms-backend\$Module\target\$JarName"
if (-not (Test-Path $jar)) { Write-Host "JAR NOT FOUND: $jar"; exit 1 }
$out = "$art\logs\$Module.out.log"
$err = "$art\logs\$Module.err.log"
New-Item -ItemType Directory -Force -Path "$art\logs" | Out-Null
$proc = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jar, '--server.port=' + $Port) `
    -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -WindowStyle Hidden
$proc.Id | Out-File -FilePath "$art\logs\$Module.pid" -Encoding ascii
Write-Host "STARTED $Module PID=$($proc.Id) PORT=$Port"
