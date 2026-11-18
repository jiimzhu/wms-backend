param(
  [string]$BaseUrl = "http://127.0.0.1:8082",
  [string]$Warehouse = "DC01",
  [string]$Location = "DC01-P-01",
  [string]$Sku = "SKU-001",
  [string]$Batch = "B-R2-K1",
  [int]$Concurrency = 20,
  [string]$Tag = "K1"
)

Add-Type -AssemblyName System.Net.Http

$results = New-Object System.Collections.Generic.List[object]
$tasks = New-Object System.Collections.Generic.List[object]

$sw = [System.Diagnostics.Stopwatch]::StartNew()

for ($i = 1; $i -le $Concurrency; $i++) {
  $idx = $i
  $requestId = "$Tag-REQ-{0:D3}" -f $idx
  $body = @{
    warehouseCode = $Warehouse
    locationCode  = $Location
    skuCode       = $Sku
    batchNo       = $Batch
    changeQty     = 1
    businessType  = "PURCHASE_IN"
    businessNo    = "$Tag-BIZ-{0:D3}" -f $idx
    remark        = "$Tag concurrent $idx"
    requestId     = $requestId
  } | ConvertTo-Json -Compress
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)

  $req = [Func[object]]{
    # Run on dedicated thread; build a per-call HttpClient to avoid any shared-state issue.
    $c = New-Object System.Net.Http.HttpClient
    $c.Timeout = [TimeSpan]::FromSeconds(15)
    try {
      $content = New-Object System.Net.Http.ByteArrayContent($bytes)
      $content.Headers.ContentType = New-Object System.Net.Http.Headers.MediaTypeHeaderValue("application/json")
      $resp = $c.PostAsync("$BaseUrl/internal/inventory/change-stock", $content).Result
      $bodyText = $resp.Content.ReadAsStringAsync().Result
      [PSCustomObject]@{ requestId = $requestId; httpCode = [int]$resp.StatusCode; body = $bodyText }
    } catch {
      [PSCustomObject]@{ requestId = $requestId; httpCode = 0; body = $_.Exception.Message }
    } finally {
      $c.Dispose()
    }
  }
  $task = [System.Threading.Tasks.Task]::Run($req)
  $tasks.Add($task)
}

[System.Threading.Tasks.Task]::WaitAll($tasks.ToArray())
$sw.Stop()

foreach ($t in $tasks) { $results.Add($t.Result) }

$results | Sort-Object requestId | Export-Csv "D:\wms\wms-backend\.r2-evidence\05-$Tag-results.csv" -NoTypeInformation -Encoding UTF8

$http200 = ($results | Where-Object httpCode -eq 200).Count
$http429 = ($results | Where-Object httpCode -eq 429).Count
$other = $results.Count - $http200 - $http429

$summary = [PSCustomObject]@{
  tag          = $Tag
  concurrency  = $Concurrency
  wallMs       = $sw.ElapsedMilliseconds
  http200      = $http200
  http429      = $http429
  httpOther    = $other
  startedAt    = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss.fff")
  finishedAt   = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss.fff")
}
$summary | ConvertTo-Json | Set-Content "D:\wms\wms-backend\.r2-evidence\05-$Tag-summary.json" -Encoding UTF8

$results | ForEach-Object { "$($_.requestId)`t$($_.httpCode)`t$($_.body)" } | Set-Content "D:\wms\wms-backend\.r2-evidence\05-$Tag-raw.txt" -Encoding UTF8

Write-Host ("$Tag wallMs={0} 200={1} 429={2} other={3}" -f $sw.ElapsedMilliseconds, $http200, $http429, $other)
