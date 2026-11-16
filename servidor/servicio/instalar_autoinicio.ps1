# Hace que el puente de Rite arranque solo al iniciar sesion en Windows, sin ventana.
# Crea un acceso directo en la carpeta Inicio del usuario (no toca el registro ni pide permisos de administrador).
# Para quitarlo: quitar_autoinicio.ps1
$vbs = Join-Path $PSScriptRoot "arrancar_puente.vbs"
$lnk = Join-Path ([Environment]::GetFolderPath("Startup")) "Rite puente.lnk"
$ws = New-Object -ComObject WScript.Shell
$a = $ws.CreateShortcut($lnk)
$a.TargetPath = "$env:SystemRoot\System32\wscript.exe"
$a.Arguments = "`"$vbs`""
$a.WorkingDirectory = $PSScriptRoot
$a.Description = "Puente de Rite (Mateo y Claude)"
$a.Save()
"Autoinicio creado: $lnk"
