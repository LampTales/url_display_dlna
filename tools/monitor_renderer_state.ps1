param(
    [string]$StateUrl = "http://127.0.0.1:8765/state",
    [string]$OutputPath = "$PSScriptRoot/renderer-state-history.ndjson",
    [int]$IntervalSeconds = 1
)

while ($true) {
    $timestamp = [DateTime]::UtcNow.ToString("o")
    try {
        $state = Invoke-RestMethod -UseBasicParsing -Uri $StateUrl -TimeoutSec 3
        [pscustomobject]@{ timestamp = $timestamp; ok = $true; state = $state } |
            ConvertTo-Json -Compress -Depth 12 |
            Add-Content -Encoding utf8 -Path $OutputPath
    } catch {
        [pscustomobject]@{ timestamp = $timestamp; ok = $false; error = $_.Exception.Message } |
            ConvertTo-Json -Compress |
            Add-Content -Encoding utf8 -Path $OutputPath
    }
    Start-Sleep -Seconds $IntervalSeconds
}
