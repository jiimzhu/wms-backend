$base = 'D:\wms\wms-backend'
$log = "$base\.r2-evidence\R4-logs"
$svcs = @(
    @{ name = 'admin';       jar = "wms-admin-service\target\wms-admin-service-1.0.0.jar" },
    @{ name = 'inventory';   jar = "wms-inventory-service\target\wms-inventory-service-1.0.0.jar" },
    @{ name = 'outbound';    jar = "wms-outbound-service\target\wms-outbound-service-1.0.0.jar" },
    @{ name = 'integration';  jar = "wms-integration-service\target\wms-integration-service-1.0.0.jar" }
)
foreach ($s in $svcs) {
    $jar = Join-Path $base $s.jar
    Start-Process -FilePath 'java' -ArgumentList '-jar', "`"$jar`"" -WorkingDirectory $base `
        -RedirectStandardOutput "$log\$($s.name).out.log" -RedirectStandardError "$log\$($s.name).err.log" -WindowStyle Hidden
    Write-Host "started $($s.name)"
    Start-Sleep -Seconds 4
}
