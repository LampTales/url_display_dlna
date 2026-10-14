param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [Parameter(Mandatory=$true)][System.Net.IPAddress]$PhoneAddress,
    [ValidateRange(30,600)][int]$Seconds = 180
)

$ErrorActionPreference = 'Stop'
$captureDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $captureDirectory -Force | Out-Null
Start-Transcript -Path (Join-Path $captureDirectory 'pktmon.log')
$started = $false
$addedFilters = $false
try {
    # Never stop or overwrite a capture belonging to another application.
    & logman.exe query PktMon -ets 2>&1 | Out-File (Join-Path $captureDirectory 'previous-session.txt')
    if ($LASTEXITCODE -eq 0) { throw 'An existing PktMon session is active.' }
    $filters = & pktmon.exe filter list 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read PktMon filters; run as administrator.' }
    $filters | Out-File (Join-Path $captureDirectory 'previous-filters.txt')
    # A configured filter list contains numbered rows. Preserve it for its owner.
    if (($filters -join "`n") -match '(?m)^\s*\d+\s+') {
        throw 'Existing PktMon filters found; review them before capturing.'
    }
    & pktmon.exe filter add C2KPhone -i $PhoneAddress.ToString()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot add phone filter.' }
    $addedFilters = $true
    & pktmon.exe filter add C2KSSDP -t UDP -p 1900
    if ($LASTEXITCODE -ne 0) { throw 'Cannot add SSDP filter.' }
    & pktmon.exe start --capture --comp nics --pkt-size 0 --file-size 512 --file-name (Join-Path $captureDirectory 'packets.etl')
    if ($LASTEXITCODE -ne 0) { throw 'PktMon start failed.' }
    $started = $true
    [DateTime]::UtcNow.ToString('o') | Set-Content (Join-Path $captureDirectory 'ready.txt')
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline -and -not (Test-Path (Join-Path $captureDirectory 'stop.flag'))) {
        Start-Sleep -Seconds 1
    }
} catch {
    $_ | Out-String | Set-Content (Join-Path $captureDirectory 'error.txt')
} finally {
    if ($started) {
        & pktmon.exe counters | Out-File (Join-Path $captureDirectory 'counters.txt')
        & pktmon.exe stop
        & pktmon.exe etl2pcap (Join-Path $captureDirectory 'packets.etl') --out (Join-Path $captureDirectory 'packets.pcapng')
        if ($LASTEXITCODE -ne 0) { 'PCAP conversion failed; preserve packets.etl.' | Set-Content (Join-Path $captureDirectory 'error.txt') }
    }
    if ($addedFilters) { & pktmon.exe filter remove }
    [DateTime]::UtcNow.ToString('o') | Set-Content (Join-Path $captureDirectory 'finished.txt')
    Stop-Transcript
}
