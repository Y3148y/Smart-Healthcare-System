param([string]$BaseUrl = 'http://127.0.0.1:8092', [string]$OutputPath = '')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$cases = Get-Content -LiteralPath (Join-Path $root 'backend/src/test/resources/rag-relevance-cases.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$login = Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body '{"username":"admin","password":"admin"}'
$headers = @{Authorization="Bearer $($login.token)"}
$rows = @()
foreach ($case in $cases) {
    $url = "$BaseUrl/api/admin/knowledge/search/details?q=" + [uri]::EscapeDataString($case.query)
    $report = Invoke-RestMethod $url -Headers $headers -TimeoutSec 120
    $titles = @($report.retrieval.evidence | ForEach-Object { $_.title } | Select-Object -Unique)
    $recall = 0.0; $rr = 0.0
    if ($case.relevant.Count -gt 0) {
        $recall = @($case.relevant | Where-Object { $titles -contains $_ }).Count / $case.relevant.Count
        for ($i = 0; $i -lt $titles.Count; $i++) { if ($case.relevant -contains $titles[$i]) { $rr = 1.0 / ($i + 1); break } }
    }
    $rows += [pscustomobject]@{id=$case.id;query=$case.query;recallAt3=$recall;reciprocalRank=$rr;
        forbiddenHit=(@($titles | Where-Object { $case.forbidden -contains $_ }).Count -gt 0);
        unanswerableFalsePositive=($case.relevant.Count -eq 0 -and $titles.Count -gt 0);report=$report}
}
$result = [pscustomobject]@{time=[DateTime]::UtcNow.ToString('o');baseUrl=$BaseUrl;
    runtime=(Invoke-RestMethod "$BaseUrl/api/admin/knowledge/runtime" -Headers $headers);rows=$rows}
if ($OutputPath) {
    $result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
}
$rows | Select-Object id,recallAt3,reciprocalRank,forbiddenHit,@{n='mode';e={$_.report.mode}},@{n='milliseconds';e={$_.report.elapsedMs}} | Format-Table
if (@($rows | Where-Object { $_.report.mode -notin @('HYBRID_QDRANT_RERANKED','HYBRID_QDRANT_NO_CANDIDATES') }).Count -gt 0) {
    throw 'Live verification incomplete: at least one query did not execute hybrid retrieval and reranking. Inspect the report.'
}
