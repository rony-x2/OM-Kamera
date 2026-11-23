# OM Kamera – Update auf die per USB angeschlossene Kamera (PowerShell 7)
#
# Holt das neueste Release von GitHub, lädt die APK herunter und installiert sie mit
# Sony-PMCA-RE (pmca-console). Die Kamera muss per USB angeschlossen sein (MTP- oder
# Massenspeicher-Modus); OM Kamera muss dafür nicht laufen.
#
#   .\omk-update.ps1                       # neueste Version installieren
#   .\omk-update.ps1 -Pmca D:\pmca-console.exe
#   .\omk-update.ps1 -NurPruefen           # nur zeigen, was es Neues gibt
#
# Ohne -Pmca wird pmca-console.exe neben diesem Skript, im aktuellen Ordner und im PATH gesucht.
param(
    [string]$Pmca,
    [switch]$NurPruefen,
    [string]$Repo = 'rony-x2/OM-Kamera'
)
$ErrorActionPreference = 'Stop'

$rel = Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest" -Headers @{ Accept = 'application/vnd.github+json' }
$apk = $rel.assets | Where-Object { $_.name -like '*.apk' } | Select-Object -First 1
if (-not $apk) { throw "Im Release $($rel.tag_name) ist keine APK." }
Write-Host "Neueste Version: $($rel.tag_name) ($($apk.name), $([math]::Round($apk.size / 1MB, 1)) MB)" -ForegroundColor Cyan
if ($NurPruefen) { Write-Host $rel.html_url; return }

if (-not $Pmca) {
    $kandidaten = @(
        (Join-Path $PSScriptRoot 'pmca-console.exe'),
        (Join-Path (Get-Location) 'pmca-console.exe')
    )
    $Pmca = $kandidaten | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $Pmca) { $Pmca = (Get-Command pmca-console -ErrorAction SilentlyContinue).Source }
}
if (-not $Pmca -or -not (Test-Path $Pmca)) {
    throw 'pmca-console.exe nicht gefunden – mit -Pmca <Pfad> angeben (https://github.com/ma1co/Sony-PMCA-RE/releases).'
}

$ziel = Join-Path ([IO.Path]::GetTempPath()) $apk.name
Invoke-WebRequest $apk.browser_download_url -OutFile $ziel
if ((Get-Item $ziel).Length -ne $apk.size) { throw "Download unvollständig: $ziel" }
Write-Host "Heruntergeladen: $ziel" -ForegroundColor Green

Write-Host 'Installiere auf die Kamera (USB) …' -ForegroundColor Cyan
# pmca-console wartet sonst auf die Konsole; Eingabe deshalb aus NUL
$ausgabe = cmd /d /s /c "`"$Pmca`" install -f `"$ziel`" < NUL 2>&1"
$ausgabe | Where-Object { $_ -notmatch '^(Down|Up)loading \d+%|^Installing \d+%' } | Write-Host
if (-not ($ausgabe -match 'Task completed successfully')) { throw 'Installation nicht bestätigt – ist die Kamera per USB angeschlossen?' }
Write-Host "Fertig. In der Kamera OM Kamera über das Anwendungsmenü neu starten." -ForegroundColor Green
