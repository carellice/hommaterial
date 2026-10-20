[CmdletBinding()]
param(
    [switch]$WindowsOnly,
    [switch]$PrepareOnly,
    [switch]$Plan,
    # Bootstrap Windows on the existing Android release, without replacing its APK.
    [switch]$AttachWindowsToCurrent
)
$ErrorActionPreference = 'Stop'
$repo = 'carellice/hommaterial'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root

function Invoke-Checked([string]$Command, [string[]]$Arguments) {
    & $Command @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$Command non riuscito (codice $LASTEXITCODE)." }
}
function Read-GhJson([string[]]$Arguments) {
    $result = & gh @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'GitHub non raggiungibile o accesso non valido. Esegui gh auth login e riprova.' }
    return ($result | ConvertFrom-Json)
}
function Write-Utf8([string]$Path, [string]$Value) {
    [IO.File]::WriteAllText($Path, $Value, (New-Object Text.UTF8Encoding $false))
}

try {
    if ($AttachWindowsToCurrent) { $WindowsOnly = $true }
    foreach ($command in @('git', 'gh', 'dotnet')) {
        if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Installa $command prima di pubblicare. Vedi RELEASING.md." }
    }
    $gradlePath = Join-Path $root 'app\build.gradle.kts'
    $webPath = Join-Path $root 'pwa\js\version.js'
    $gradle = [IO.File]::ReadAllText($gradlePath)
    $current = [regex]::Match($gradle, 'versionName = "([0-9.]+)"').Groups[1].Value
    $code = [int][regex]::Match($gradle, 'versionCode = ([0-9]+)').Groups[1].Value
    if (-not $current -or $code -le 0) { throw 'Versione Android non valida.' }
    if ($Plan) {
        Write-Host "Versione attuale comune: $current (Android versionCode $code)."
        Write-Host 'Il rilascio standard incrementa l ultimo numero, compila Android + Windows x64 e pubblica una release GitHub.'
        Write-Host '-WindowsOnly: compila solo Windows, aggiornando comunque la versione comune nei sorgenti.'
        Write-Host '-PrepareOnly: compila e prepara i file senza commit, push o pubblicazione.'
        Write-Host '-AttachWindowsToCurrent: aggiunge Windows alla release Android attuale senza toccare l APK.'
        return
    }
    Invoke-Checked 'gh' @('auth', 'status')
    $repository = Read-GhJson @('repo', 'view', '--json', 'nameWithOwner')
    if ($repository.nameWithOwner -ne $repo) { throw "La cartella deve appartenere a $repo." }
    $status = & git status --porcelain
    if ($LASTEXITCODE -ne 0 -or $status) { throw 'Salva prima tutte le modifiche in un commit. La release deve corrispondere esattamente ai sorgenti.' }
    $branch = & git branch --show-current
    if ($branch -ne 'main') { throw 'Esegui il rilascio dal branch main.' }
    Invoke-Checked 'git' @('fetch', 'origin', 'main')
    $behind = & git rev-list --count 'HEAD..origin/main'
    if ($LASTEXITCODE -ne 0 -or [int]$behind -gt 0) { throw 'Il branch locale e  indietro rispetto a origin/main. Aggiornalo prima.' }
    $releases = @(Read-GhJson @('api', "repos/$repo/releases?per_page=100"))
    if ($AttachWindowsToCurrent) {
        $release = @($releases | Where-Object { $_.tag_name -eq "v$current" -and -not $_.draft -and -not $_.prerelease })
        if ($release.Count -ne 1) { throw "Release stabile v$current non trovata." }
        if (@($release[0].assets | Where-Object { $_.name -like '*windows-x64.zip' }).Count) { throw 'Windows e  gia  presente in questa release. Crea una nuova versione.' }
        $next = $current
    } else {
        $parts = @($current.Split('.') | ForEach-Object { [int]$_ })
        do {
            $parts[$parts.Count - 1]++
            $next = $parts -join '.'
        } while (@($releases | Where-Object { $_.tag_name -eq "v$next" }).Count -gt 0)
    }
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    if (-not $WindowsOnly) {
        if (-not (Test-Path -LiteralPath $sdk)) { throw 'SDK Android mancante. Installalo con Android Studio oppure usa -WindowsOnly.' }
        $key = $env:HOMMATERIAL_ANDROID_KEYSTORE
        if (-not $key) { $key = Join-Path $env:USERPROFILE '.android\debug.keystore' }
        if (-not (Test-Path -LiteralPath $key)) { throw 'Manca la chiave Android originale. Imposta HOMMATERIAL_ANDROID_KEYSTORE sul file del Mac.' }
        $env:HOMMATERIAL_ANDROID_KEYSTORE = $key
        $env:ANDROID_HOME = $sdk
        $signer = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Filter apksigner.bat -Recurse | Sort-Object FullName -Descending | Select-Object -First 1
        if (-not $signer) { throw 'Installa Android SDK Build-Tools (apksigner).' }
        if (-not $env:JAVA_HOME) {
            $jdk = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
            if (Test-Path -LiteralPath $jdk) { $env:JAVA_HOME = $jdk }
        }
        if (-not (Get-Command java -ErrorAction SilentlyContinue) -and -not $env:JAVA_HOME) { throw 'Installa Java 17 o Android Studio.' }
    }
    if (Get-Process -Name Hommaterial.Windows -ErrorAction SilentlyContinue) { throw 'Chiudi Hommaterial anche dall area di notifica prima di compilare.' }
    $tag = "v$next"
    $out = Join-Path $root "windows\artifacts\release-$tag"
    if (Test-Path -LiteralPath $out) { $out = "$out-$([Guid]::NewGuid().ToString('N').Substring(0, 8))" }
    New-Item -ItemType Directory -Path $out | Out-Null
    $previousWeb = [IO.File]::ReadAllText($webPath)
    $versionCommitted = $false
    try {
        if (-not $AttachWindowsToCurrent) {
            Write-Utf8 $gradlePath (($gradle -replace 'versionName = "[0-9.]+"', "versionName = `"$next`"") -replace 'versionCode = [0-9]+', "versionCode = $($code + 1)")
            Write-Utf8 $webPath ($previousWeb -replace "APP_VERSION = '[0-9.]+'", "APP_VERSION = '$next'")
        }
        Write-Host "Preparazione Hommaterial $next..."
        $assets = New-Object 'System.Collections.Generic.List[string]'
        if (-not $WindowsOnly) {
            Invoke-Checked (Join-Path $root 'gradlew.bat') @('testReleaseUnitTest', 'assembleRelease', '--no-daemon')
            $apk = Join-Path $out "Hommaterial-$tag.apk"
            Copy-Item -LiteralPath (Join-Path $root 'app\build\outputs\apk\release\app-release.apk') -Destination $apk
            # Refuse an APK that cannot update the previously distributed Android app.
            $prior = @($releases | Where-Object { -not $_.draft -and -not $_.prerelease -and @($_.assets | Where-Object { $_.name -like '*.apk' }).Count }) | Select-Object -First 1
            if ($prior) {
                $old = Join-Path $out 'previous-apk'
                New-Item -ItemType Directory -Path $old | Out-Null
                Invoke-Checked 'gh' @('release', 'download', $prior.tag_name, '--repo', $repo, '--pattern', '*.apk', '--dir', $old)
                $oldApk = Get-ChildItem -LiteralPath $old -Filter *.apk | Select-Object -First 1
                $oldInfo = & $signer.FullName verify --print-certs $oldApk.FullName
                if ($LASTEXITCODE -ne 0) { throw 'Verifica firma APK precedente fallita.' }
                $newInfo = & $signer.FullName verify --print-certs $apk
                if ($LASTEXITCODE -ne 0) { throw 'Verifica firma nuovo APK fallita.' }
                $oldHash = [regex]::Match(($oldInfo -join "`n"), 'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)').Groups[1].Value
                $newHash = [regex]::Match(($newInfo -join "`n"), 'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)').Groups[1].Value
                if (-not $oldHash -or $oldHash -ne $newHash) { throw 'Firma Android diversa dalla release precedente. Usa la chiave originale del Mac: nessun APK verra  pubblicato.' }
            }
            $assets.Add($apk)
        }
        $app = Join-Path $out 'app'
        & (Join-Path $root 'windows\build.ps1') -OutputDirectory $app
        $zip = Join-Path $out "Hommaterial-$tag-windows-x64.zip"
        Copy-Item -LiteralPath (Join-Path $root 'LICENSE') -Destination $app
        Copy-Item -LiteralPath (Join-Path $root 'THIRD_PARTY_NOTICES.md') -Destination $app
        Copy-Item -LiteralPath (Join-Path $root 'windows\PRIVACY.md') -Destination $app
        Write-Utf8 (Join-Path $app 'LEGGIMI.txt') "Hommaterial $next - Windows 11 x64`r`n`r`nEstrai TUTTI i file in una cartella personale scrivibile e avvia Hommaterial.Windows.exe.`r`nAggiornamenti: Impostazioni > Controlla aggiornamenti.`r`nDurante il riavvio timer e automazioni sono sospesi. Dati locali: %LOCALAPPDATA%\Hommaterial\Windows.`r`nProgetto indipendente, non affiliato ad Amazon. API Alexa non ufficiali.`r`nGuida e sorgenti: https://github.com/$repo`r`n"
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [IO.Compression.ZipFile]::CreateFromDirectory($app, $zip)
        $assets.Add($zip)
        $checksums = Join-Path $out "Hommaterial-$tag-SHA256SUMS.txt"
        Write-Utf8 $checksums (($assets | ForEach-Object { "{0}  {1}" -f (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant(), [IO.Path]::GetFileName($_) }) -join "`n")
        $assets.Add($checksums)
        if ($PrepareOnly) {
            Write-Host "File pronti in $out. Nessun commit, push o release. Versioni nei sorgenti ripristinate."
            return
        }
        if (-not $AttachWindowsToCurrent) {
            Invoke-Checked 'git' @('add', '--', 'app/build.gradle.kts', 'pwa/js/version.js')
            Invoke-Checked 'git' @('commit', '-m', "Versione $next")
            $versionCommitted = $true
        }
        Invoke-Checked 'git' @('push', 'origin', 'main')
        $commit = & git rev-parse HEAD
        if ($AttachWindowsToCurrent) {
            Invoke-Checked 'gh' (@('release', 'upload', $tag, '--repo', $repo) + $assets.ToArray())
            $notes = Join-Path $out 'notes.md'
            Write-Utf8 $notes ($release[0].body + "`n`n## Windows 11 x64`nVersione nativa WinUI, portatile, con aggiornamenti integrati da GitHub. Estrarre tutto lo ZIP in una cartella personale e avviare Hommaterial.Windows.exe. Windows aggiunto dal commit $commit; APK Android invariato.`n")
            Invoke-Checked 'gh' @('release', 'edit', $tag, '--repo', $repo, '--notes-file', $notes)
        } else {
            Invoke-Checked 'gh' (@('release', 'create', $tag, '--repo', $repo, '--target', $commit, '--title', "Hommaterial $next", '--generate-notes', '--draft') + $assets.ToArray())
            # Keep Android's /releases/latest pointing to a release with an APK.
            $latestFlag = if ($WindowsOnly) { '--latest=false' } else { '--latest' }
            Invoke-Checked 'gh' @('release', 'edit', $tag, '--repo', $repo, '--draft=false', $latestFlag)
        }
        Write-Host "Pubblicato: https://github.com/$repo/releases/tag/$tag"
        Write-Host 'Le release precedenti sono state conservate.'
    } finally {
        if (-not $versionCommitted -and -not $AttachWindowsToCurrent) {
            Write-Utf8 $gradlePath $gradle
            Write-Utf8 $webPath $previousWeb
        }
    }
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
