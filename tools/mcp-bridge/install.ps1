# OM Kamera – MCP-Brücke einrichten (PowerShell 7)
# Kopiert die Brücke nach %USERPROFILE%\Tools\omkamera-mcp und trägt sie in die Konfiguration
# von Claude Desktop ein (vorher wird eine Sicherung angelegt).
param(
    [string]$Token = $(Read-Host 'MCP-Token von der Kamera (Menü App > MCP-Schnittstelle)'),
    [string]$Url = 'http://192.168.2.38:8080/mcp',
    # Optional: pmca-console.exe für das Brücken-Werkzeug update_installieren
    [string]$Pmca = ''
)
$ErrorActionPreference = 'Stop'
$src = $PSScriptRoot
$dst = Join-Path $env:USERPROFILE 'Tools\omkamera-mcp'
$cfg = Join-Path $env:LOCALAPPDATA 'Claude-Data\claude_pzs8sxrjxfjjc\claude_desktop_config.json'
if (-not (Test-Path $cfg)) { throw "Konfiguration nicht gefunden: $cfg" }

New-Item -ItemType Directory -Force $dst | Out-Null
Copy-Item (Join-Path $src 'omkamera_mcp.py'), (Join-Path $src 'mcp_tools.json') $dst -Force

$backup = "$cfg.bak-$(Get-Date -Format yyyyMMdd-HHmmss)"
Copy-Item $cfg $backup
$j = Get-Content $cfg -Raw | ConvertFrom-Json
if (-not $j.mcpServers) { $j | Add-Member -NotePropertyName mcpServers -NotePropertyValue ([pscustomobject]@{}) }
$entry = [pscustomobject]@{
    command = 'py'
    args    = @('-3', (Join-Path $dst 'omkamera_mcp.py'))
    env     = [pscustomobject]@{ OMKAMERA_URL = $Url; OMKAMERA_TOKEN = $Token.Trim() }
}
if ($Pmca) { $entry.env | Add-Member -NotePropertyName OMKAMERA_PMCA -NotePropertyValue (Resolve-Path $Pmca).Path }
$j.mcpServers | Add-Member -NotePropertyName omkamera -NotePropertyValue $entry -Force
$j | ConvertTo-Json -Depth 20 | Set-Content $cfg -Encoding utf8NoBOM

# Kurzer Test gegen die Kamera
try {
    $body = '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"status","arguments":{}}}'
    $r = Invoke-RestMethod -Uri $Url -Method Post -Body $body -ContentType 'application/json' -Headers @{ Authorization = "Bearer $($Token.Trim())" } -TimeoutSec 8
    Write-Host "Kamera antwortet:" -ForegroundColor Green
    $r.result.content[0].text | Out-Host
} catch {
    Write-Host "Kamera hat (noch) nicht geantwortet: $($_.Exception.Message)" -ForegroundColor Yellow
}
Write-Host "Eingetragen (Sicherung: $backup). Claude Desktop jetzt ganz beenden (auch im Infobereich) und neu starten." -ForegroundColor Cyan
