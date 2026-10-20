[CmdletBinding()]
param([string]$Directory = (Join-Path $env:USERPROFILE '.android\hommaterial-signing'))
$ErrorActionPreference = 'Stop'
$Directory = [IO.Path]::GetFullPath($Directory)
$keystore = Join-Path $Directory 'hommaterial.keystore'
$properties = Join-Path $Directory 'signing.properties'
if ((Test-Path -LiteralPath $keystore) -or (Test-Path -LiteralPath $properties)) {
    throw 'Esiste gia una chiave dedicata. Nessun file modificato: non rigenerare la chiave usata per le release.'
}
$keytool = (Get-Command keytool -ErrorAction Stop).Source
New-Item -ItemType Directory -Path $Directory -Force | Out-Null
# Restrict the directory before any private material is written.
$acl = New-Object Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true, $false)
$owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl.SetOwner($owner)
$rule = New-Object Security.AccessControl.FileSystemAccessRule($owner, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
$acl.AddAccessRule($rule)
Set-Acl -LiteralPath $Directory -AclObject $acl
$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
$password = ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
$previousPassword = $env:HOMMATERIAL_KEYTOOL_PASSWORD
try {
    $env:HOMMATERIAL_KEYTOOL_PASSWORD = $password
    & $keytool -genkeypair -alias hommaterial -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Hommaterial' -storetype PKCS12 -keystore $keystore -storepass:env HOMMATERIAL_KEYTOOL_PASSWORD -keypass:env HOMMATERIAL_KEYTOOL_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Generazione della chiave non riuscita.' }
    $values = "storeFile=$($keystore.Replace('\', '/'))`nstorePassword=$password`nkeyAlias=hommaterial`nkeyPassword=$password`n"
    [IO.File]::WriteAllText($properties, $values, (New-Object Text.UTF8Encoding $false))
    $certificate = Join-Path $Directory 'certificate.der'
    & $keytool -exportcert -alias hommaterial -keystore $keystore -storepass:env HOMMATERIAL_KEYTOOL_PASSWORD -file $certificate
    if ($LASTEXITCODE -ne 0) { throw 'Verifica del certificato non riuscita.' }
    $fingerprint = (Get-FileHash -LiteralPath $certificate -Algorithm SHA256).Hash.ToLowerInvariant()
    Remove-Item -LiteralPath $certificate
    # Creating a CSR also proves the private key can be opened with these credentials.
    $csr = Join-Path $Directory 'verify.csr'
    & $keytool -certreq -alias hommaterial -keystore $keystore -storepass:env HOMMATERIAL_KEYTOOL_PASSWORD -keypass:env HOMMATERIAL_KEYTOOL_PASSWORD -file $csr
    if ($LASTEXITCODE -ne 0) { throw 'Verifica della chiave privata non riuscita.' }
    Remove-Item -LiteralPath $csr
    Write-Host "Chiave creata e verificata: $keystore"
    Write-Host "Configurazione privata: $properties"
    Write-Host "SHA-256 del certificato pubblico: $fingerprint"
    Write-Host 'Conserva un backup privato di entrambi i file. Non caricarli su GitHub.'
} finally {
    $env:HOMMATERIAL_KEYTOOL_PASSWORD = $previousPassword
    $password = $null
    $values = $null
}
