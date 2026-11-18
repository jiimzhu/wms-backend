param(
    [Parameter(Mandatory=$true)][string]$Scenario,
    [Parameter(Mandatory=$true)][string]$OutFile,
    [Parameter(Mandatory=$true)][object[]]$Items
)
Add-Type -AssemblyName System.Net.Http
$base = 'http://127.0.0.1:8082/internal/inventory/change-stock'

$handler = New-Object System.Net.Http.HttpClientHandler
$handler.MaxConnectionsPerServer = 50
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(30)

$globalStart = [Diagnostics.Stopwatch]::StartNew()
$pairs = @()
foreach ($it in $Items) {
    $jsonObj = [ordered]@{
        warehouseCode = 'DC01'
        locationCode  = 'DC01-A-01-01'
        skuCode       = 'SKU-LOCK-01'
        batchNo       = 'B-LOCK-01'
        changeQty     = $it.changeQty
        businessType  = $it.businessType
        businessNo    = $it.requestId
        remark        = $Scenario
        requestId     = $it.requestId
    }
    $json = ($jsonObj | ConvertTo-Json -Compress)
    $content = New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, 'application/json')
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $task = $client.PostAsync($base, $content)
    $pairs += [pscustomobject]@{ task = $task; sw = $sw; rid = $it.requestId; qty = $it.changeQty; bt = $it.businessType; json = $json }
}

$tasks = $pairs | ForEach-Object { $_.task }
[System.Threading.Tasks.Task]::WaitAll($tasks)
$globalStart.Stop()

$results = foreach ($p in $pairs) {
    $p.sw.Stop()
    $r = [pscustomobject]@{ requestId = $p.rid; changeQty = $p.qty; businessType = $p.bt; httpStatus = 0; bodyCode = ''; bodyMsg = ''; elapsedMs = $p.sw.ElapsedMilliseconds }
    try {
        $resp = $p.task.Result
        $body = $resp.Content.ReadAsStringAsync().Result
        $r.httpStatus = [int]$resp.StatusCode
        try { $j = $body | ConvertFrom-Json; $r.bodyCode = $j.code; $r.bodyMsg = $j.msg } catch { $r.bodyMsg = $body }
    } catch {
        $r.httpStatus = -1; $r.bodyCode = 'EX'; $r.bodyMsg = $_.Exception.Message
    }
    $r
}

$summary = [pscustomobject]@{
    scenario        = $Scenario
    concurrency     = $pairs.Count
    globalElapsedMs = $globalStart.ElapsedMilliseconds
    results         = $results
}
$summary | ConvertTo-Json -Depth 6 | Set-Content $OutFile -Encoding UTF8
Write-Output ("GLOBAL_ELAPSED_MS=" + $globalStart.ElapsedMilliseconds)
$results | Sort-Object requestId | Format-Table requestId, changeQty, httpStatus, bodyCode, elapsedMs | Out-String | Write-Output
