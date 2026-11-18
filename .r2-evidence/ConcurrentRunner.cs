using System;
using System.Threading.Tasks;
using System.Net.Http;
using System.Text;

public static class ConcurrentRunner {
    public static void Main(string[] args) {
        // args: url tag warehouse location sku batch outRaw outSummary
        string url = args[0];
        string tag = args[1];
        string wh = args[2], loc = args[3], sku = args[4], batch = args[5];
        string outRaw = args[6], outSummary = args[7];
        int n = 20;

        var sw = System.Diagnostics.Stopwatch.StartNew();
        var tasks = new Task<Result>[n];
        for (int i = 0; i < n; i++) {
            int idx = i;
            tasks[i] = Task.Run(async () => {
                var c = new HttpClient();
                c.Timeout = TimeSpan.FromSeconds(15);
                string requestId = string.Format("{0}-REQ-{1:D3}", tag, idx + 1);
                string businessNo = string.Format("{0}-BIZ-{1:D3}", tag, idx + 1);
                var body = "{\"warehouseCode\":\"" + wh + "\",\"locationCode\":\"" + loc
                    + "\",\"skuCode\":\"" + sku + "\",\"batchNo\":\"" + batch
                    + "\",\"changeQty\":1,\"businessType\":\"PURCHASE_IN\",\"businessNo\":\""
                    + businessNo + "\",\"remark\":\"" + tag + " concurrent " + (idx + 1)
                    + "\",\"requestId\":\"" + requestId + "\"}";
                var content = new StringContent(body, Encoding.UTF8, "application/json");
                try {
                    var resp = await c.PostAsync(url, content);
                    var b = await resp.Content.ReadAsStringAsync();
                    return new Result { RequestId = requestId, HttpCode = (int)resp.StatusCode, Body = b };
                } catch (Exception e) {
                    return new Result { RequestId = requestId, HttpCode = 0, Body = e.Message };
                } finally { c.Dispose(); }
            });
        }
        Task.WaitAll(tasks);
        sw.Stop();

        int c200 = 0, c429 = 0, other = 0;
        var sb = new StringBuilder();
        foreach (var t in tasks) {
            var r = t.Result;
            if (r.HttpCode == 200) c200++; else if (r.HttpCode == 429) c429++; else other++;
            sb.AppendLine(r.RequestId + "\t" + r.HttpCode + "\t" + r.Body);
        }
        System.IO.File.WriteAllText(outRaw, sb.ToString(), new UTF8Encoding(false));

        string summary = "{\"tag\":\"" + tag + "\",\"concurrency\":" + n
            + ",\"wallMs\":" + sw.ElapsedMilliseconds
            + ",\"http200\":" + c200 + ",\"http429\":" + c429 + ",\"httpOther\":" + other
            + ",\"startedAt\":\"" + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff")
            + "\",\"finishedAt\":\"" + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff") + "\"}";
        System.IO.File.WriteAllText(outSummary, summary, new UTF8Encoding(false));
        Console.WriteLine(tag + " wallMs=" + sw.ElapsedMilliseconds + " 200=" + c200 + " 429=" + c429 + " other=" + other);
    }

    public class Result { public string RequestId; public int HttpCode; public string Body; }
}
