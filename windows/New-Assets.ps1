$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$assets = Join-Path $PSScriptRoot 'Hommaterial.Windows\Assets'
New-Item -ItemType Directory -Force -Path $assets | Out-Null
$source = [System.Drawing.Image]::FromFile((Join-Path $PSScriptRoot '..\pwa\icons\icon-512.png'))
try {
    foreach ($entry in @(@('Logo.png', 256), @('Square44x44Logo.png', 44), @('Square150x150Logo.png', 150), @('StoreLogo.png', 50))) {
        $bitmap = New-Object System.Drawing.Bitmap ([int]$entry[1]), ([int]$entry[1])
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $graphics.DrawImage($source, 0, 0, [int]$entry[1], [int]$entry[1])
            $bitmap.Save((Join-Path $assets $entry[0]), [System.Drawing.Imaging.ImageFormat]::Png)
        } finally { $graphics.Dispose(); $bitmap.Dispose() }
    }
} finally { $source.Dispose() }
$png = [System.IO.File]::ReadAllBytes((Join-Path $assets 'Logo.png'))
$stream = [System.IO.File]::Create((Join-Path $assets 'App.ico'))
$writer = New-Object System.IO.BinaryWriter $stream
try {
    $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]1)
    $writer.Write([byte]0); $writer.Write([byte]0); $writer.Write([byte]0); $writer.Write([byte]0)
    $writer.Write([uint16]1); $writer.Write([uint16]32)
    $writer.Write([uint32]$png.Length); $writer.Write([uint32]22); $writer.Write($png)
} finally { $writer.Dispose() }
