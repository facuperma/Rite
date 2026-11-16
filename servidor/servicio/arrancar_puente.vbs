' Arranca el puente de Rite sin ventana. Lo usa el acceso directo de Inicio (instalar_autoinicio.ps1).
' El registro queda en %LOCALAPPDATA%\Rite\puente.log (no incluye codigos de vinculacion).
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
carpeta = fso.GetParentFolderName(WScript.ScriptFullName)
servidor = fso.GetParentFolderName(carpeta)
py = servidor & "\.venv\Scripts\python.exe"
datos = sh.ExpandEnvironmentStrings("%LOCALAPPDATA%") & "\Rite"
If Not fso.FolderExists(datos) Then fso.CreateFolder(datos)
archivoLog = datos & "\puente.log"
q = Chr(34)
sh.CurrentDirectory = servidor
cmd = "cmd /c " & q & q & py & q & " -u -m rite_puente servir --sin-codigo >> " & q & archivoLog & q & " 2>&1" & q
sh.Run cmd, 0, False
