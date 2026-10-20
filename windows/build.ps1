[CmdletBinding()]
param(
    [ValidateSet('x64', 'arm64')][string]$Architecture = 'x64',
    [ValidateSet('Debug', 'Release')][string]$Configuration = 'Release',
    [switch]$Package,
    [switch]$Run,
    [string]$IdentityName = 'Hommaterial.Windows.Dev',
    [string]$Publisher = 'CN=Hommaterial',
    [string]$PublisherDisplayName = 'Hommaterial',
    [string]$Version = '0.1.0.0'
)
$ErrorActionPreference = 'Stop'
$project = Join-Path $PSScriptRoot 'Hommaterial.Windows\Hommaterial.Windows.csproj'
$output = Join-Path $PSScriptRoot "artifacts\$Architecture\app"
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
& (Join-Path $PSScriptRoot 'New-Assets.ps1')
dotnet run --project (Join-Path $PSScriptRoot 'Hommaterial.Tests') --configuration $Configuration -p:NuGetAudit=false
if ($LASTEXITCODE -ne 0) { throw 'Verifiche della logica non riuscite.' }
dotnet publish $project --configuration $Configuration --runtime "win-$Architecture" --self-contained true -p:NuGetAudit=false -p:PlatformTarget=$Architecture --output $output
if ($LASTEXITCODE -ne 0) { throw 'Compilazione Windows non riuscita.' }
if ($Package) {
    [xml]$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Package.appxmanifest')
    $manifest.Package.Identity.Name = $IdentityName
    $manifest.Package.Identity.Publisher = $Publisher
    $manifest.Package.Identity.Version = $Version
    $manifest.Package.Identity.ProcessorArchitecture = $Architecture
    $manifest.Package.Properties.PublisherDisplayName = $PublisherDisplayName
    $manifest.Save((Join-Path $output 'AppxManifest.xml'))
    $packageRoot = if ($env:NUGET_PACKAGES) { $env:NUGET_PACKAGES } elseif ($env:DOTNET_CLI_HOME) { Join-Path $env:DOTNET_CLI_HOME '.nuget\packages' } else { Join-Path $env:USERPROFILE '.nuget\packages' }
    $sdkBin = Join-Path $packageRoot 'microsoft.windows.sdk.buildtools\10.0.26100.4654\bin\10.0.26100.0\x64'
    $makeappx = Join-Path $sdkBin 'makeappx.exe'
    if (-not (Test-Path -LiteralPath $makeappx)) { throw "MakeAppx non trovato in $makeappx. Ripristina Microsoft.Windows.SDK.BuildTools." }
    $msix = Join-Path $PSScriptRoot "artifacts\Hommaterial-$Version-$Architecture.msix"
    & $makeappx pack /d $output /p $msix /o
    if ($LASTEXITCODE -ne 0) { throw 'Creazione o validazione MSIX non riuscita.' }
    Write-Host "Pacchetto MSIX non firmato: $msix"
}
Write-Host "App pronta: $output\Hommaterial.Windows.exe"
if ($Run) { Start-Process -FilePath (Join-Path $output 'Hommaterial.Windows.exe') -WorkingDirectory $output }
