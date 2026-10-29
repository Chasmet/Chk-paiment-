# INSTALLER CHK Paiements sans toucher CHK Universal Control 1.0.4 /mcp.
# Télécharger le code Paiements séparément dans AppData et démarrer le service 8791/HTTPS 8443.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = Join-Path $env:LOCALAPPDATA 'CHK-Paiements\core'
$cache = Join-Path $env:TEMP ('CHK-Paiements-' + [guid]::NewGuid().ToString('N'))
# Pinned source version, reviewed before generating the APK.
$sourceSha = 'db84c2eee5e657a78559e13dd180b0a499cdb618'
$archiveUrl = "https://github.com/Chasmet/CHK-serveur-Universal-Control/archive/$sourceSha.zip"
$archive = "$cache.zip"
try {
  New-Item -Path $cache -ItemType Directory -Force | Out-Null
  Invoke-WebRequest -Uri $archiveUrl -OutFile $archive -UseBasicParsing -TimeoutSec 90
  Expand-Archive -LiteralPath $archive -DestinationPath $cache -Force
  $source = Get-ChildItem -LiteralPath $cache -Directory | Select-Object -First 1
  if (-not $source) { throw 'Archive du module de paiement vide.' }
  $payments = Join-Path $source.FullName 'payments'
  if (-not (Test-Path (Join-Path $payments 'sidecar.mjs'))) { throw 'Fichiers du serveur Paiements introuvables.' }
  New-Item -Path $root -ItemType Directory -Force | Out-Null
  Copy-Item -Path $payments -Destination $root -Recurse -Force
  $runner = Join-Path $root 'payments\windows\start-chk-paiements.ps1'
  if (-not (Test-Path $runner)) { throw 'Script de lancement absent.' }
  Write-Host 'Moteur CHK Paiements téléchargé. MCP universel non modifié.' -ForegroundColor Green
  & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $runner
  if ($LASTEXITCODE -ne 0) { throw 'Démarrage CHK Paiements incomplet.' }
  Write-Host 'Terminé : saisir uniquement le code d appairage dans CHK Paiements.' -ForegroundColor Cyan
} finally {
  Remove-Item -LiteralPath $cache -Recurse -Force -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
}
