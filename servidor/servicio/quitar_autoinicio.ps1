# Quita el arranque automatico del puente (solo el acceso directo de Inicio; no cierra el puente si esta corriendo).
$lnk = Join-Path ([Environment]::GetFolderPath("Startup")) "Rite puente.lnk"
if (Test-Path $lnk) { Remove-Item $lnk; "Autoinicio quitado." } else { "No habia autoinicio." }
