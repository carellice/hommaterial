param([Parameter(Mandatory = $true)][string]$JobFile, [switch]$NoRestart, [switch]$Quiet)
$ErrorActionPreference = 'Stop'
$job = Get-Content -LiteralPath $JobFile -Raw -Encoding UTF8 | ConvertFrom-Json
$target = [IO.Path]::GetFullPath($job.Target).TrimEnd('\')
$staged = [IO.Path]::GetFullPath($job.Staged).TrimEnd('\')
$work = [IO.Path]::GetFullPath($job.Work).TrimEnd('\')
$exe = Join-Path $target 'Hommaterial.Windows.exe'
$backup = Join-Path $work 'previous'
$changed = New-Object 'System.Collections.Generic.List[string]'
$original = New-Object 'System.Collections.Generic.HashSet[string]'
$installed = $false
try {
    if (-not $staged.StartsWith($work + '\', [StringComparison]::OrdinalIgnoreCase) -or
        $target.StartsWith($work + '\', [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath (Join-Path $target 'Hommaterial.Windows.dll')) -or
        -not (Test-Path -LiteralPath (Join-Path $staged 'Hommaterial.Windows.dll'))) { throw 'Cartelle di aggiornamento non valide.' }
    # Do not replace files while another instance is using this installation.
    $deadline = (Get-Date).AddSeconds(90)
    do {
        $running = @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe })
        if ($running.Count -eq 0) { break }
        if ((Get-Date) -gt $deadline) { throw 'Chiudi tutte le istanze di Hommaterial prima di aggiornare.' }
        Start-Sleep -Milliseconds 500
    } while ($true)
    # Reject links in either tree, so writes cannot escape the app folder.
    foreach ($rootPath in @($target, $staged)) {
        $item = Get-Item -LiteralPath $rootPath
        while ($null -ne $item) {
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Sposta l app fuori da cartelle con collegamenti simbolici.' }
            $item = $item.Parent
        }
        if (Get-ChildItem -LiteralPath $rootPath -Recurse -Force | Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 }) { throw 'Collegamenti non consentiti nella cartella dell app.' }
    }
    foreach ($file in Get-ChildItem -LiteralPath $staged -File -Recurse -Force) {
        $relative = $file.FullName.Substring($staged.Length + 1)
        $destination = Join-Path $target $relative
        if (-not ([IO.Path]::GetFullPath($destination)).StartsWith($target + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Percorso non valido.' }
        $saved = Join-Path $backup $relative
        if (Test-Path -LiteralPath $destination) {
            New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($saved)) -Force | Out-Null
            Copy-Item -LiteralPath $destination -Destination $saved
            [void]$original.Add($relative)
        }
        $changed.Add($relative)
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $destination -Force
    }
    $installed = $true
    if (-not $NoRestart) { Start-Process -FilePath $exe -WorkingDirectory $target }
    'Aggiornamento installato.' | Set-Content -LiteralPath (Join-Path $work 'result.txt')
} catch {
    $failure = $_.Exception.Message
    if (-not $installed) {
        foreach ($relative in $changed) {
            $destination = Join-Path $target $relative
            try {
                if ($original.Contains($relative)) { Copy-Item -LiteralPath (Join-Path $backup $relative) -Destination $destination -Force }
                else { Remove-Item -LiteralPath $destination -Force -ErrorAction SilentlyContinue }
            } catch { $failure += "`nRipristino fallito per ${relative}: $($_.Exception.Message)" }
        }
    }
    $failure | Set-Content -LiteralPath (Join-Path $work 'error.txt')
    if (-not $Quiet) {
        Add-Type -AssemblyName System.Windows.Forms
        [System.Windows.Forms.MessageBox]::Show("Aggiornamento non completato.`n$failure`nDettagli: $work", 'Hommaterial') | Out-Null
    }
    if (-not $NoRestart -and -not $installed -and $changed.Count -gt 0) { Start-Process -FilePath $exe -WorkingDirectory $target }
    exit 1
}
