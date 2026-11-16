# Rite

App de notas para Android (Kotlin + Compose + Room) que habla con Mateo (LLM local, Ollama) y, mas adelante, con Claude Code a traves del proyecto **Productor** (`Documents/productor`). Plan completo en `PLAN_NOTAS_MATEO.md`.

## Estado
- **Etapa 0**: notas, carpetas, etiquetas, colores, fijadas, papelera de 30 dias, busqueda, listas con casillas.
- **Etapa 1**: puente en la PC (`servidor/`), vinculacion con codigo de un solo uso, HTTPS con huella fijada y boton **Mateo** en cada nota (la respuesta queda como version; la nota no se pisa). Registro de proyectos de Claude Code (Productor por defecto).

## Puente (PC)
```
cd servidor
python -m venv .venv && .venv\Scripts\pip install cryptography
.venv\Scripts\python -m rite_puente servir            # imprime el enlace rite://... para vincular
.venv\Scripts\python -m rite_puente vincular          # codigo nuevo (10 min, un solo uso)
.venv\Scripts\python -m rite_puente dispositivos listar|revocar ID
.venv\Scripts\python -m rite_puente proyectos listar|agregar NOMBRE RUTA|quitar NOMBRE
.venv\Scripts\python -m unittest discover -s tests
```
Datos en `%LOCALAPPDATA%\Rite` (certificado, base de dispositivos, proyectos.json). Solo acepta clientes de red local, loopback y Tailscale. Necesita Ollama con `qwen3:8b`. Para el emulador: `servir --escuchar 127.0.0.1 --direccion 10.0.2.2`.

### Arranque automatico del puente (Windows)
```
powershell -File servidor\servicio\instalar_autoinicio.ps1   # crea un acceso directo en Inicio: el puente arranca solo, sin ventana
powershell -File servidor\servicio\quitar_autoinicio.ps1     # lo quita
```
Registro en `%LOCALAPPDATA%\Rite\puente.log` (sin codigos de vinculacion; el arranque automatico usa `--sin-codigo`). Para vincular un celular nuevo: `python -m rite_puente vincular --direccion <IP>`. Hay que tener Tailscale y Ollama arrancados para usar Rite fuera de casa y con Mateo.

## App
`gradlew.bat assembleDebug` y `gradlew.bat testDebugUnitTest` (en esta PC con `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/gtvtmp`). En la app: menu → Ajustes y PC vinculada → pegar el enlace.
