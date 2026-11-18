Add-Type -AssemblyName System.Net.Http
$log = "D:\wms\wms-backend\.r2-evidence\probe-out.txt"
"start" | Set-Content $log -Encoding UTF8
$req = [Func[object]]{
  "inside func" | Add-Content $log -Encoding UTF8
  $c = New-Object System.Net.Http.HttpClient
  try {
    $r = $c.GetAsync("http://127.0.0.1:8082/internal/inventory/list").Result
    "code=$($r.StatusCode)" | Add-Content $log -Encoding UTF8
    [PSCustomObject]@{ code = [int]$r.StatusCode }
  } catch {
    "EX: $($_.Exception.GetType().FullName) | $($_.Exception.Message)" | Add-Content $log -Encoding UTF8
    [PSCustomObject]@{ code = -1; msg = $_.Exception.Message }
  } finally { $c.Dispose() }
}
$t = [System.Threading.Tasks.Task]::Run($req)
try { $t.Wait(); "result: $($t.Result | ConvertTo-Json -Compress)" | Add-Content $log -Encoding UTF8 }
catch { "WAIT EX: $($_.Exception.InnerException.Message)" | Add-Content $log -Encoding UTF8 }
