# Notas + Mateo + Claude: plan y modelo de datos

## Piezas
1. **App Android (Kotlin + Compose + Room/SQLite)**: notas offline, estilo Samsung Notes.
2. **Servidor puente en la PC (Python, junto a Ideario)**: HTTPS, vinculación por QR, habla con Ollama (Mateo) y con Claude Code (`claude -p --resume`).
3. **Red**: Tailscale en PC y celular para usar con datos o cualquier wifi (sin abrir puertos del router).

## Pantallas (inspiradas en Samsung Notes)
- Lista: tarjetas en cuadrícula o lista, fijadas arriba, buscador, filtro por carpeta y etiqueta.
- Carpetas (panel lateral), papelera 30 días.
- Editor: título, texto con checklist, negrita/cursiva, etiquetas, color de nota.
- Barra de acciones de la nota: **Mateo**, **Claude**, versiones.
- Chat con Mateo (con contexto de notas) y pestaña "Contexto" (resumen vigente).
- Ajustes: PC vinculada, N mensajes por resumen (10), tope de contexto.

## Flujos
- **Nota -> Mateo**: se envía el texto; vuelve una VERSION nueva (origen=mateo). La original no se pisa.
- **Nota -> Claude**: se envía la versión elegida (o un brief comprimido por Mateo). Vuelve respuesta + diff/archivos tocados como VERSION (origen=claude).
- **Resumen cada 10 mensajes**: el servidor toma los últimos mensajes + el contexto vigente, Mateo genera: resumen, hechos significativos (lista corta), contexto nuevo (tope de caracteres). Se guarda y se muestra en el celular. El próximo prompt de Mateo arranca con ese contexto (menos invención).
- **Revisión de diff por Mateo**: solo a pedido (botón).

## Modelo de datos (celular, Room; el servidor guarda lo mismo en SQLite)
- `carpeta(id, nombre, color, orden)`
- `nota(id, carpeta_id, titulo, cuerpo, fijada, color, creada, editada, borrada_en)`
- `etiqueta(id, nombre)` / `nota_etiqueta(nota_id, etiqueta_id)`
- `version(id, nota_id, origen[usuario|mateo|claude], texto, creada, meta_json)`
- `chat(id, tipo[mateo|claude], titulo, creada)`
- `mensaje(id, chat_id, rol[usuario|mateo|claude], texto, creada, resumido)`
- `resumen(id, chat_id, desde_mensaje, hasta_mensaje, resumen, hechos_json, contexto, creada)`
- `ajuste(clave, valor)`
- Servidor extra: `dispositivo(id, huella, token_hash, creado, ultimo_uso)`, `trabajo(id, tipo, estado, entrada, salida, creado)`.

## API del puente (todo con token de dispositivo)
- POST /vincular (QR de un solo uso)
- POST /mateo/organizar {texto} -> {texto}
- POST /mateo/chat {chat_id, mensaje} -> stream
- POST /mateo/resumir {chat_id}
- POST /claude/enviar {nota/texto, sesion?} -> trabajo_id; GET /trabajo/{id} (estado, respuesta, diff)
- POST /mateo/revisar-diff {trabajo_id}
- GET /sync?desde=... (notas/versiones/resumenes)

## Seguridad
- Vinculación por QR de un solo uso, token por dispositivo guardado hasheado, HTTPS con huella fijada (como TVon).
- Solo escucha en la IP de Tailscale/LAN. Claude Code corre con permisos acotados a la carpeta del proyecto y pide aprobación para ejecutar comandos.
- Nada personal en la base de conocimiento de Mateo (regla acordada).

## Etapas
0. Esqueleto: app con notas/carpetas/etiquetas offline + tests de la base.
1. Servidor puente + vinculación + "Nota -> Mateo" (versiones).
2. Chat con Mateo + resumen automático cada 10 + contexto.
3. "Nota -> Claude" con respuesta y diff; revisión de diff a pedido.
4. Pulido: Tailscale guiado, búsqueda, papelera, exportar/respaldo.

## Preguntas abiertas
- Nombre de la app: **Rite** (definido).
- Carpeta del proyecto (propuesta: Documents/rite, nueva, con git a nombre de facuperma).
- Qué proyecto/sesión de Claude Code recibe las notas (¿una fija?).
