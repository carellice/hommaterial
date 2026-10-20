$ErrorActionPreference = 'Stop'
$windowsRoot = Split-Path -Parent $PSScriptRoot
$fixture = Join-Path $windowsRoot ('artifacts\installer-test-' + [Guid]::NewGuid().ToString('N'))
$installer = Join-Path $windowsRoot 'Hommaterial.Windows\Install-Update.ps1'
foreach ($scenario in @('success', 'rollback')) {
    $basePath = Join-Path $fixture $scenario
    $target = Join-Path $basePath 'target'
    $work = Join-Path $basePath 'work'
    $staged = Join-Path $work 'app'
    New-Item -ItemType Directory -Path $target, $staged -Force | Out-Null
    foreach ($name in @('Hommaterial.Windows.exe', 'Hommaterial.Windows.dll', 'payload.txt', 'zzz-locked.txt')) {
        [IO.File]::WriteAllText((Join-Path $target $name), 'old')
        [IO.File]::WriteAllText((Join-Path $staged $name), 'new')
    }
    [IO.File]::WriteAllText((Join-Path $target 'unrelated.txt'), 'keep')
    [IO.File]::WriteAllText((Join-Path $staged 'new-file.txt'), 'new')
    $jobPath = Join-Path $work 'job.json'
    @{ Target = $target; Staged = $staged; Work = $work; ProcessId = 0 } | ConvertTo-Json | Set-Content -LiteralPath $jobPath
    $lock = $null
    try {
        if ($scenario -eq 'rollback') { $lock = [IO.File]::Open((Join-Path $target 'zzz-locked.txt'), [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read) }
        & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $installer -JobFile $jobPath -NoRestart -Quiet
        $exitCode = $LASTEXITCODE
    } finally { if ($lock) { $lock.Dispose() } }
    if ((Get-Content -LiteralPath (Join-Path $target 'unrelated.txt') -Raw) -ne 'keep') { throw 'Unrelated file was modified.' }
    if ($scenario -eq 'success') {
        if ($exitCode -ne 0 -or (Get-Content -LiteralPath (Join-Path $target 'payload.txt') -Raw) -ne 'new' -or
            -not (Test-Path -LiteralPath (Join-Path $work 'result.txt'))) { throw 'Update did not install.' }
        Write-Host 'PASS: updater installs new files and preserves unrelated data.'
    } else {
        if ($exitCode -eq 0 -or (Get-Content -LiteralPath (Join-Path $target 'payload.txt') -Raw) -ne 'old' -or
            (Test-Path -LiteralPath (Join-Path $target 'new-file.txt')) -or
            -not (Test-Path -LiteralPath (Join-Path $work 'error.txt'))) { throw 'Rollback failed.' }
        Write-Host 'PASS: locked file cancels the update and restores the previous files.'
    }
}
