[CmdletBinding()]
param(
    [switch]$PrepareOnly,
    [switch]$Plan
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
    foreach ($command in @('git', 'gh')) {
        if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Installa $command prima di pubblicare. Vedi RELEASING.md." }
    }
    $gradlePath = Join-Path $root 'app\build.gradle.kts'
    $webPath = Join-Path $root 'pwa\web\js\version.js'
    $gradle = [IO.File]::ReadAllText($gradlePath)
    $current = [regex]::Match($gradle, 'versionName = "([0-9.]+)"').Groups[1].Value
    $code = [int][regex]::Match($gradle, 'versionCode = ([0-9]+)').Groups[1].Value
    if (-not $current -or $code -le 0) { throw 'Versione Android non valida.' }
    if ($Plan) {
        Write-Host "Versione attuale: $current (Android versionCode $code)."
        Write-Host 'Il rilascio incrementa l ultimo numero, compila Android e pubblica una release GitHub.'
        Write-Host '-PrepareOnly: compila e prepara i file senza commit, push o pubblicazione.'
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
    $parts = @($current.Split('.') | ForEach-Object { [int]$_ })
    do {
        $parts[$parts.Count - 1]++
        $next = $parts -join '.'
    } while (@($releases | Where-Object { $_.tag_name -eq "v$next" }).Count -gt 0)
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    if (-not (Test-Path -LiteralPath $sdk)) { throw 'SDK Android mancante. Installalo con Android Studio.' }
    $signingProperties = $env:HOMMATERIAL_ANDROID_SIGNING_PROPERTIES
    if (-not $signingProperties) { $signingProperties = Join-Path $env:USERPROFILE '.android\hommaterial-signing\signing.properties' }
    if (Test-Path -LiteralPath $signingProperties) {
        $env:HOMMATERIAL_ANDROID_SIGNING_PROPERTIES = $signingProperties
    } else {
        if ($env:HOMMATERIAL_ANDROID_SIGNING_PROPERTIES) { throw 'Il file HOMMATERIAL_ANDROID_SIGNING_PROPERTIES configurato non esiste.' }
        $key = $env:HOMMATERIAL_ANDROID_KEYSTORE
        if (-not $key) { $key = Join-Path $env:USERPROFILE '.android\debug.keystore' }
        if (-not (Test-Path -LiteralPath $key)) { throw 'Chiave Android mancante. Ripristina la chiave dedicata e signing.properties dal backup.' }
        $env:HOMMATERIAL_ANDROID_KEYSTORE = $key
    }
    $env:ANDROID_HOME = $sdk
    $signer = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Filter apksigner.bat -Recurse | Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $signer) { throw 'Installa Android SDK Build-Tools (apksigner).' }
    if (-not $env:JAVA_HOME) {
        $jdk = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
        if (Test-Path -LiteralPath $jdk) { $env:JAVA_HOME = $jdk }
    }
    if (-not (Get-Command java -ErrorAction SilentlyContinue) -and -not $env:JAVA_HOME) { throw 'Installa Java 17 o Android Studio.' }
    $tag = "v$next"
    $out = Join-Path $root "app\build\release-$tag"
    if (Test-Path -LiteralPath $out) { $out = "$out-$([Guid]::NewGuid().ToString('N').Substring(0, 8))" }
    New-Item -ItemType Directory -Path $out | Out-Null
    $previousWeb = [IO.File]::ReadAllText($webPath)
    $versionCommitted = $false
    try {
        Write-Utf8 $gradlePath (($gradle -replace 'versionName = "[0-9.]+"', "versionName = `"$next`"") -replace 'versionCode = [0-9]+', "versionCode = $($code + 1)")
        Write-Utf8 $webPath ($previousWeb -replace "APP_VERSION = '[0-9.]+'", "APP_VERSION = '$next'")
        Write-Host "Preparazione Hommaterial $next..."
        $assets = New-Object 'System.Collections.Generic.List[string]'
        $androidMigration = $false
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
            if (-not $oldHash -or -not $newHash) { throw 'Impossibile leggere le impronte dei certificati Android.' }
            if ($oldHash -ne $newHash) {
                $migrationPath = Join-Path $PSScriptRoot 'android-signing-migration.json'
                $migration = if (Test-Path -LiteralPath $migrationPath) { Get-Content -LiteralPath $migrationPath -Raw | ConvertFrom-Json } else { $null }
                if ($migration -and $prior.tag_name -eq $migration.previousRelease -and $newHash -eq $migration.newCertificateSha256) {
                    $androidMigration = $true
                    Write-Warning 'Nuova firma Android autorizzata: gli utenti devono esportare il backup e disinstallare il vecchio APK prima di installare questo.'
                } else { throw 'Firma Android diversa da quella attesa. Ripristina la chiave dedicata dal backup: nessun APK verra pubblicato.' }
            }
        }
        $assets.Add($apk)
        $checksums = Join-Path $out "Hommaterial-$tag-SHA256SUMS.txt"
        Write-Utf8 $checksums (($assets | ForEach-Object { "{0}  {1}" -f (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant(), [IO.Path]::GetFileName($_) }) -join "`n")
        $assets.Add($checksums)
        if ($PrepareOnly) {
            Write-Host "File pronti in $out. Nessun commit, push o release. Versioni nei sorgenti ripristinate."
            return
        }
        Invoke-Checked 'git' @('add', '--', 'app/build.gradle.kts', 'pwa/web/js/version.js')
        Invoke-Checked 'git' @('commit', '-m', "Versione $next")
        $versionCommitted = $true
        Invoke-Checked 'git' @('push', 'origin', 'main')
        $commit = & git rev-parse HEAD
        Invoke-Checked 'gh' (@('release', 'create', $tag, '--repo', $repo, '--target', $commit, '--title', "Hommaterial $next", '--generate-notes', '--draft') + $assets.ToArray())
        if ($androidMigration) {
            $draft = Read-GhJson @('release', 'view', $tag, '--repo', $repo, '--json', 'body')
            $notes = Join-Path $out 'notes.md'
            Write-Utf8 $notes ("## Android: nuova chiave di firma`nQuesta versione non si installa come aggiornamento degli APK fino alla v1.33. Esporta prima un backup dall'app, disinstalla la vecchia versione, installa il nuovo APK, collega nuovamente Amazon e importa il backup. Gli aggiornamenti successivi useranno la stessa nuova firma.`n`n" + $draft.body)
            Invoke-Checked 'gh' @('release', 'edit', $tag, '--repo', $repo, '--notes-file', $notes)
        }
        Invoke-Checked 'gh' @('release', 'edit', $tag, '--repo', $repo, '--draft=false', '--latest')
        Write-Host "Pubblicato: https://github.com/$repo/releases/tag/$tag"
        Write-Host 'Le release precedenti sono state conservate.'
    } finally {
        if (-not $versionCommitted) {
            Write-Utf8 $gradlePath $gradle
            Write-Utf8 $webPath $previousWeb
        }
    }
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
