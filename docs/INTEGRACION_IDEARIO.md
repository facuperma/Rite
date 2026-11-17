# Integración de Rite en Ideario 2.0 — contrato

Decisión (Facundo, 17/11/2026): Ideario 2.0 (Electron + React + TypeScript, `Documents/ideas-llm/v2/`) es el centro. Las notas pasan a ser una sección de Ideario; Mateo es un chat fijado (qwen3:8b); la app Android sigue llamándose **Rite** y es el cliente móvil que sincroniza con Ideario por Tailscale. La PC es la fuente de verdad; el celular guarda copia offline. El puente Python (`servidor/rite_puente`) se retira al final.

Este documento es **solo especificación**. Referencia normativa del comportamiento actual: el código de este repo en la rama `feat/prompt-claude` (servidor Python + app Kotlin). Donde dice «exacto», el valor sale del código.

---

## 1. API actual del puente (a conservar o migrar)

HTTPS con certificado propio autofirmado. Todas las respuestas son JSON UTF-8 (`Content-Type: application/json; charset=utf-8`, `Content-Length`, HTTP/1.1). Cuerpo máximo de pedido: 256 KiB (`413 {"error":"Cuerpo demasiado grande"}`). Puerto por defecto: **8787**.

### 1.1 Filtro de origen
Antes de cualquier ruta: solo se atienden IPs privadas (RFC 1918, link-local, etc. — `ipaddress.is_private`), loopback y Tailscale (`100.64.0.0/10`). IPv6 mapeada a IPv4 se normaliza. Otro origen → `403 {"error":"Origen no permitido"}`.

### 1.2 Autenticación
Cabecera `Authorization: Bearer <token>`. Token inválido o ausente → `401 {"error":"No autorizado: vincula el celular de nuevo"}`. Cada pedido válido actualiza `ultimo_uso` del dispositivo.

### 1.3 Rutas

| Método y ruta | Auth | Cuerpo | Respuesta 200 |
|---|---|---|---|
| `POST /vincular` | no | `{"codigo":"123456","nombre":"S23"}` | `{"token":"<urlsafe 43 chars>"}`; código malo/vencido/agotado → `403 {"error":"Codigo incorrecto o vencido"}` |
| `GET /estado` | sí | — | `{"version":"…","mateo":true,"modelo":"qwen3:8b","proyectos":[{"nombre","ruta","predeterminado"}]}` |
| `POST /mateo/organizar` | sí | `{"texto":"…","pista":"" (opc.)}` | `{"texto","modelo","resumen_iteracion"}` |
| `POST /mateo/prompt` | sí | `{"texto":"…","pista":"" (opc.),"titulo":"" (opc.)}` | ver 1.4 |
| cualquier otra | — | — | `404 {"error":"No existe"}` |

`texto` debe ser string (`400 {"error":"Falta el campo texto"}`); `pista` y `titulo`, si vienen, deben ser string (`400 {"error":"El campo pista debe ser texto"}` / `…titulo…`). JSON inválido → `400 {"error":"JSON invalido"}`. Texto vacío o de más de **8000 caracteres** (`LIMITE_TEXTO`) → `502 {"error":"La nota esta vacia"}` / `"La nota es demasiado larga (N caracteres; maximo 8000)"`. Ollama caído → `502 {"error":"No pude hablar con Ollama. Esta abierto en la PC?"}`; timeout (240 s) → `502 "Mateo tardo demasiado en responder"`; respuesta vacía → `502 "Mateo devolvio una respuesta vacia"`.

`/estado` hoy lista los «proyectos» de Claude Code (Productor por defecto); es de la etapa 3 (enviar notas a Claude) y puede quedar para después.

### 1.4 `POST /mateo/prompt` — respuesta
```json
{
  "prompt": "Objetivo: …\nRequisitos:\n- …",
  "modelo": "qwen3:8b",
  "caracteres_antes": 1223,
  "caracteres_despues": 679,
  "advertencias": [],
  "intentos": 3,
  "resumen_iteracion": "Mateo probó 3 versiones y te dejó la 2ª: 56 % del original, literales 100 %, sin invenciones. Dejé afuera 1 frase (podés volver a agregarlas).",
  "dejado_afuera": ["comprar cable usb c"],
  "preguntas_del_original": ["No se si conviene X o Y."],
  "preguntas_sugeridas": ["¿Cómo …?"]
}
```
- `prompt` nunca es más largo que el original (garantía dura: si no, se devuelve el original **compactado** y `advertencias` trae un mensaje neutro).
- `intentos` = candidatos que se generaron (1..3). `dejado_afuera`: hasta 3 frases del original que el prompt no cubre (la app permite volver a agregarlas). `preguntas_sugeridas`: hasta 3, fuera del prompt y del conteo de largo. `preguntas_del_original`: frases de duda del texto, extraídas por código, tal cual.
- `/mateo/organizar` devuelve **una** versión (el mejor candidato interno) y `resumen_iteracion`; nada se guarda del lado servidor.

### 1.5 Formato de errores
Siempre `{"error":"<mensaje en español>"}` con el código HTTP: 400 (entrada), 401, 403, 404, 413, 502 (Mateo/Ollama). La app muestra `error` tal cual (`ErrorPuente(mensaje, codigo)`); errores de red/TLS los arma el cliente: «No llego a la PC (host:puerto). ¿Está prendida y el puente abierto?» y «No pude verificar la PC: el certificado no es el de la vinculación».

### 1.6 Vinculación y seguridad
- **Enlace**: `rite://IP:PUERTO?c=CODIGO&h=HUELLA` (la app lo parsea con `^rite://([^:/?#\s]+):(\d{1,5})\?(.+)$`; `c` = 6 dígitos; `h` = 64 hex en minúscula; puerto 1..65535). Se pega en la app (no hay escaneo de QR hoy).
- **Código**: 6 dígitos (`secrets.randbelow(10**6)`), vale **10 minutos**, **un solo uso**, **un solo código vigente** (pedir otro invalida el anterior), máximo **5 intentos** fallidos (al agotarse o vencer se borra). Se guarda solo su SHA-256. Comparación en tiempo constante.
- **Token por dispositivo**: `secrets.token_urlsafe(32)`; en la base solo se guarda SHA-256 (`dispositivo(id, nombre ≤60, token_hash UNIQUE, creado, ultimo_uso)`). Revocar = borrar la fila.
- **Huella fijada**: la app guarda SHA-256 del certificado DER del servidor (en el enlace) y **solo confía en ese certificado** (no en las autoridades del sistema); el nombre de host no se verifica. TLS ≥ 1.2. El cliente usa connectTimeout **8 s** y readTimeout **300 s** (Mateo puede tardar ~1 min la primera vez).
- La app guarda `host`, `puerto`, `huella`, `token` en almacenamiento privado (sin backup).
- Comandos de administración actuales: `servir`, `vincular`, `dispositivos listar|revocar`, `proyectos …`.

---

## 2. Modelo de datos (Room → esquema para Ideario)

Room actual (versión 2; sin exportar esquema): ids `Long` autogenerados, tiempos en milisegundos epoch.

- `carpeta(id, nombre, color INT, orden INT)`
- `nota(id, carpetaId→carpeta SET NULL, titulo, cuerpo, fijada BOOL, color INT (0 = sin color; índice de paleta), creada, editada, borradaEn NULL|ms)` — `borradaEn` ≠ NULL = en la papelera; se borra de verdad a los **30 días** (`DIAS_PAPELERA`).
- `etiqueta(id, nombre UNIQUE)`
- `nota_etiqueta(notaId, etiquetaId)` PK compuesta, ambas CASCADE.
- `version(id, notaId→nota CASCADE, origen 'usuario'|'mateo'|'claude', texto, creada, metaJson '{}')` — `metaJson` `{"tipo":"prompt"}` marca una versión «Prompt». **Regla**: no se guarda una versión idéntica (mismo `notaId`+`origen`+`texto`) a una existente.
- `ajuste(clave PK, valor)`.

Reglas de la app a conservar: nada de Mateo se guarda solo; una versión se guarda solo con «Guardar versión» / «Usar en la nota»; antes de reemplazar el contenido se guarda un snapshot `usuario` (sin duplicados); la lista de casillas usa `☐ ` / `☑ `.

### Esquema propuesto (SQLite de Ideario, `node:sqlite`)
Ids estables (UUID v4 texto), `actualizado` por fila, borrado lógico y un contador global `seq` para sincronizar:

```sql
CREATE TABLE IF NOT EXISTS notas_carpeta (
  id TEXT PRIMARY KEY, nombre TEXT NOT NULL, color INTEGER NOT NULL DEFAULT 0, orden INTEGER NOT NULL DEFAULT 0,
  actualizado INTEGER NOT NULL, borrado INTEGER NOT NULL DEFAULT 0, seq INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS notas_nota (
  id TEXT PRIMARY KEY, carpeta_id TEXT REFERENCES notas_carpeta(id) ON DELETE SET NULL,
  titulo TEXT NOT NULL DEFAULT '', cuerpo TEXT NOT NULL DEFAULT '', fijada INTEGER NOT NULL DEFAULT 0,
  color INTEGER NOT NULL DEFAULT 0, creada INTEGER NOT NULL, editada INTEGER NOT NULL,
  borrada_en INTEGER,              -- papelera (30 dias)
  base_rev TEXT,                   -- ver 3.4 (hash del contenido sincronizado)
  actualizado INTEGER NOT NULL, eliminada INTEGER NOT NULL DEFAULT 0, seq INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS notas_etiqueta (
  id TEXT PRIMARY KEY, nombre TEXT NOT NULL UNIQUE COLLATE NOCASE,
  actualizado INTEGER NOT NULL, eliminada INTEGER NOT NULL DEFAULT 0, seq INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS notas_nota_etiqueta (
  nota_id TEXT NOT NULL REFERENCES notas_nota(id) ON DELETE CASCADE,
  etiqueta_id TEXT NOT NULL REFERENCES notas_etiqueta(id) ON DELETE CASCADE,
  actualizado INTEGER NOT NULL, quitada INTEGER NOT NULL DEFAULT 0, seq INTEGER NOT NULL,
  PRIMARY KEY (nota_id, etiqueta_id));
CREATE TABLE IF NOT EXISTS notas_version (
  id TEXT PRIMARY KEY, nota_id TEXT NOT NULL REFERENCES notas_nota(id) ON DELETE CASCADE,
  origen TEXT NOT NULL CHECK (origen IN ('usuario','mateo','claude','conflicto')),
  texto TEXT NOT NULL, creada INTEGER NOT NULL, meta_json TEXT NOT NULL DEFAULT '{}',
  seq INTEGER NOT NULL,
  UNIQUE (nota_id, origen, texto));            -- sin duplicados
CREATE TABLE IF NOT EXISTS notas_dispositivo (
  id INTEGER PRIMARY KEY AUTOINCREMENT, nombre TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
  creado REAL NOT NULL, ultimo_uso REAL NOT NULL);
CREATE TABLE IF NOT EXISTS notas_codigo (id INTEGER PRIMARY KEY CHECK (id = 1), hash TEXT NOT NULL, expira REAL NOT NULL, intentos INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS notas_meta (clave TEXT PRIMARY KEY, valor TEXT NOT NULL);   -- 'seq' global, 'id_servidor'
```
`ajuste` es local de cada cliente (no se sincroniza). `version.origen='conflicto'` solo para ver 3.4 (en la app se rotula «Otra versión (conflicto)»). Migración desde la app: ids Long → UUID nuevos al primer sync (tabla de mapeo local en el celular).

---

## 3. Protocolo de sincronización

Principios: la PC (Ideario) es la fuente de verdad; el celular trabaja offline y sincroniza cuando ve la PC; **nunca se pierde texto**; sincronización por filas con cursor monotónico; mismo canal de seguridad que el puente (HTTPS, huella fijada, token por dispositivo, solo IPs privadas/Tailscale).

### 3.1 Cursor
La PC mantiene un contador entero `seq` (en `notas_meta`). Toda escritura de fila (propia o recibida) le asigna `seq = ++seq`. El cursor del cliente = último `seq` recibido (empieza en 0). `id_servidor` (UUID de la base de Ideario) viaja en cada respuesta: si cambia, el cliente descarta su cursor y hace sync completo.

### 3.2 Endpoints
`GET /sync?desde=<cursor>&limite=500` (auth) →
```json
{ "servidor": "<uuid>", "cursor": 1234, "hay_mas": false,
  "carpetas": [...], "notas": [...], "etiquetas": [...], "notas_etiquetas": [...], "versiones": [...] }
```
Cada fila trae sus campos (camelCase en JSON) más `actualizado`, `seq` y la marca de borrado (`eliminada`/`quitada`). Se pagina por `seq` ascendente; el cliente repite con `desde=cursor` hasta `hay_mas=false`.

`POST /sync` (auth) cuerpo:
```json
{ "servidor": "<uuid|null>", "dispositivo": "S23", "desde": 1200,
  "carpetas": [...], "notas": [{"id":"uuid","baseRev":"<hash>", ...campos, "actualizado": 169...}],
  "etiquetas": [...], "notas_etiquetas": [...], "versiones": [...] }
```
Respuesta: `{ "cursor", "aceptadas": {"notas":["uuid"...]}, "conflictos": [{"nota":"uuid","version":"uuid"}], "mapeo": {...} }`. El cliente envía **solo lo cambiado desde su último sync** (filas «sucias»). Orden recomendado del cliente: `POST /sync` (sube) y luego `GET /sync` (baja lo nuevo, incluidas las resoluciones). Los dos endpoints exigen token y respetan el filtro de IP; `413` si un POST supera 256 KiB → el cliente parte el envío en lotes (≤ 100 filas).

Endpoints existentes que se mantienen: `/vincular`, `/estado` (agregar `{"notas":true,"cursor":N,"servidor":"uuid"}`), `/mateo/organizar`, `/mateo/prompt` (iguales a la sección 1).

### 3.3 Detección de conexión (celular)
- «Sin conexión» = `/estado` falla (connectTimeout 8 s, error TLS/huella o IOException). La app lo muestra como estado, no como error; **todo sigue funcionando local**.
- Reintento: al abrir la app, al volver a primer plano, al cambiar la red y cada 5 min con la app abierta (backoff 10 s → 5 min). Un `WorkManager` con restricción de red puede disparar un sync periódico (opcional, etapa 3).
- Mateo / Prompt requieren PC: sin conexión esos botones muestran «Sin conexión con la PC»; no hay cola.

### 3.4 Conflictos (nunca se pierde texto)
Cada nota lleva `baseRev` = hash (SHA-256 truncado a 16 hex de `titulo\n\ncuerpo`) del contenido que el cliente tenía al **último sync**. Al recibir una nota del celular:
1. Si `baseRev` == hash actual del servidor → fast-forward: se aplica (no hay conflicto).
2. Si no (la PC editó la nota mientras el celular editaba la suya): **gana la más reciente por `actualizado` como contenido vigente, y la otra se guarda íntegra como `notas_version` con `origen='conflicto'`** (con `meta_json {"de":"celular|pc","dispositivo":"S23"}`). Ambas quedan visibles en «Versiones»; se avisa al celular en `conflictos`.
3. Campos de metadatos (carpeta, fijada, color, etiquetas): por fila, gana el `actualizado` mayor (sin versión de conflicto; son reversibles y baratos).
4. Una nota **borrada** en un lado y **editada** en el otro → se conserva la edición (la nota vuelve de la papelera) y se registra versión `conflicto` con el texto previo.
5. Versiones (`notas_version`) son append-only con id UUID y `UNIQUE(nota_id, origen, texto)`: se unen sin conflicto.
6. Etiquetas: se unen por `nombre` (case-insensitive); si dos ids tienen el mismo nombre se unifican al id del servidor y el mapeo viaja en `mapeo`.

### 3.5 Borrados
- **Papelera** (`borrada_en` ≠ NULL): se sincroniza como cualquier campo; el purgado definitivo (a los 30 días) lo hace **solo la PC** y emite lápida.
- **Lápidas**: `eliminada=1` / `quitada=1` con `seq` nuevo; se conservan **90 días** y recién entonces se compactan. Un celular que vuelve tras más de 90 días con cursor viejo recibe `servidor` distinto o `410` → sync completo (`desde=0`) y sus filas sucias se reconcilian por 3.4.

### 3.6 Reglas generales
Reloj: `actualizado` en ms del dispositivo que escribió; el servidor lo recorta a `min(actualizado, ahora_servidor)` para tolerar relojes adelantados. El servidor nunca confía en `seq` del cliente. Todo `POST /sync` es idempotente (mismas ids → mismas filas). Tamaño y límites como el puente actual.

---

## 4. Módulos a portar a TypeScript (motor de prompts)

Fuente: `servidor/rite_puente/{fidelidad,puntuacion,armador,mateo}.py`. Son puros (sin HTTP): reciben `llamar(mensajes, opciones) -> texto`. Criterio de aceptación = **equivalencia con el banco** (sección 4.6), no byte a byte, porque el modelo tiene temperatura.

### 4.1 `fidelidad` (todo determinista; portar con tests unitarios idénticos)
- `literales(texto)`: lo que debe conservarse tal cual, sin repetidos y en orden: (a) lo que va entre backticks; (b) rutas y nombres de archivo (`[/\\]` o extensión `kt|py|md|json|kts|xml|ps1|ts|tsx|js|sql|gradle|toml|yml|yaml|txt|html|css`); (c) identificadores snake_case, camelCase, PascalCase con mayúsculas internas y `CONSTANTES_MAYUSCULAS`; (d) números y versiones (`\d+([.,]\d+)*`, no pegados a letras ni a otro número); (e) sigla + letra suelta tipo «usb c» / «USB C» (primera palabra en minúscula de 2-4 letras que no sea funcional — `de en un el la los las por con sin que se su al lo del para y` —, segunda una consonante suelta). Se recortan `.,;:` finales y `)` desbalanceados.
- `faltantes(original, prompt)`: literales ausentes en el prompt. Los números se buscan con límites (no dentro de otro número) y toleran coma/punto y unidad pegada (`400` vs `400ms`); las rutas normalizan `\`→`/`.
- `con_contexto(original, lit)`: para un número suelto devuelve ±2 palabras de su frase (para la línea «Datos:»).
- `separar_dudas(texto, 3)`: devuelve `(texto sin dudas, dudas)`; dudas = frases con `?`, «no sé si/cuál/qué/cómo/cuánto», «no estoy seguro/a», «capaz», «tal vez», «quizás», «a/por definir», «lo decidimos», «habría que ver/decidir», «not sure», «maybe», «TBD»; se devuelven tal cual (solo se quitan muletillas iniciales «ah, y»).
- `invenciones(original, prompt)`: tokens del prompt que parecen identificador/nombre propio/siglas/contienen dígito y no aparecen en el original (comparación sin acentos, minúsculas); capitalizadas a mitad de línea; ítems sueltos «- Nombre» (≤ 2 palabras, no cabecera). Cabeceras permitidas: Objetivo, Contexto, Requisitos, Restricciones, Hecho, Datos, Preguntas.
- `omitidas(original, prompt, 3, excluir)`: frases del original (separadas por línea/viñeta/`.!?;`) cuyas raíces de contenido (5 letras, sin acentos, sin las palabras vacías de `_VACIAS`, ≥ 3 letras) casi no están en el prompt (**< 34 %** presentes). `excluir` = título.
- `recortadas(original, prompt, 3, excluir)`: frases cortas (2..6 palabras de contenido) presentes entre **34 % y < 100 %** de sus raíces y con algún **bigrama contiguo** de raíces ausente del prompt (se perdió una palabra dentro de la frase).
- `restaurar(prompt, frase)`: reemplaza el trozo del prompt con mayor solapamiento (≥ 50 % de las raíces de la frase) por la frase original; si no hay, la agrega al final.
- `detectar_titulo(texto)`: primera línea ≤ 80 caracteres, sin `. ! ? ; :` final, seguida de línea en blanco o de más texto.
- `compactar(texto)`: colapsa espacios y líneas en blanco repetidas.
- `comparte_contenido(pregunta, nota)` y `contiene_cita(cita, nota)` (cita ≥ 6 caracteres contenida en la nota normalizada).

### 4.2 `puntuacion`
`Puntaje { largo, limite, ratio, faltan, inventadas, estructura, omitidas, recortadas }`.
- `pasa` = largo ≤ límite **y** sin faltantes **y** sin inventadas **y** estructura **y** sin recortadas (las omitidas NO bloquean; solo informan).
- `clave` (menor es mejor): `(hay inventadas, largo > límite, #faltan, #recortadas, #omitidas, !estructura, largo)`.
- `estructura` (solo prompts): empieza con «Objetivo:» o alguna línea con cabecera (objetivo/requisitos/contexto/restricciones/hecho cuando).
- `pistas()`: «NO uses estos terminos que no estan en la idea: …», «Conserva tal cual: …», «Conserva la frase tal cual, sin sacarle palabras: …», «Quedo demasiado largo (N caracteres): acortalo mucho mas, solo lo esencial.», «Respeta la estructura pedida (empeza por 'Objetivo:').»
- `elegir(puntajes)`: índice del menor `clave` (empate → el primero). `resumen(i, total, p)`: «Mateo probó N versión|versiones y te dejó la Kª: R % del original, literales 100 %|faltaban N literales, sin invenciones|N invenciones.»

### 4.3 `armar(original, llamar, modelo, pista, k=3, sugerir, titulo)`
Constantes: `CANDIDATOS=3`, `RATIO_OBJETIVO=0.55` (notas ≥ 300), `RATIO_CORTAS=0.80` (< 300), `UMBRAL_CORTA=300`, `MIN_PARA_PREGUNTAS=120`, aceptación `0.7·len` si `len<500`, si no `RATIO·len`.
1. `titulo = titulo || detectar_titulo`. `(sin_dudas, dudas) = separar_dudas`. `base = compactar(sin_dudas)`; `compactado = compactar(original)`. `largo_dudas = Σ(len+3) + 22 (si hay dudas)`.
2. Si hay `base`: `presupuesto = max(40, 0.35·len(base), ratio·len(original) − largo_dudas)`; `limite_cuerpo = max(presupuesto+15, aceptable − largo_dudas)`.
3. Hasta `k` candidatos: `tope = max(40, presupuesto·0.8^i)`; mensajes = `[system SISTEMA, user EJEMPLO_IDEA+"(Maximo 190 caracteres.)", assistant EJEMPLO_PROMPT, user base + "\n\n(Maximo {tope} caracteres en total. [Indicacion de Facundo: {pista≤300}.] [pistas del candidato anterior])"]`; opciones `temperature = 0.1 (i=0) | 0.0`, `num_predict = int(tope/2.2)+40`. Se limpia la salida (`_limpiar`: sin fences, sin secciones «Preguntas/Dudas/Reglas» que el modelo agregue, sin líneas repetidas ni en blanco). Se puntúa (`literales_de=base`, `titulo`); si `pasa`, se corta; si no, las `pistas()` pasan al siguiente.
4. Elige el mejor (`elegir`). Reparaciones deterministas: quitar líneas con invenciones sobrevivientes; `restaurar` cada frase `recortada`; recomputar `faltan`.
5. Ensamblado: `cuerpo` + (si faltan literales) línea `Datos: <con_contexto de cada literal, más largos primero, sin repetir contenidos>` + (si hay dudas) `Preguntas abiertas:\n- …` (las del original, tal cual).
6. **Garantía de largo/fidelidad**: si el resultado está vacío, o `len(final) ≥ len(compactado)`, o todavía faltan literales del original → `final = compactado` + advertencia neutra («Tu nota ya era corta; la dejé compactada, sin cambios.» si `len < 600`, si no «No logré achicarla sin perder datos; la dejé compactada, sin cambios.») y `resumen_iteracion = "Tu texto ya era corto: no encontré una versión más corta que conservara todo, así que lo dejé compactado."`.
7. `dejado_afuera = omitidas(original, final, 3, [titulo])` (vacío si quedó el compactado); si hay, se agrega «Dejé afuera N frase(s) (podés volver a agregarlas).» al resumen.
8. Preguntas sugeridas (solo si `len(original) ≥ 120`): una llamada aparte (`SISTEMA_PREGUNTAS`, `temperature 0.4`, `num_predict 120`) con formato por línea `¿pregunta? | "cita textual"`; `limpiar_preguntas` conserva ≤ 3 que: tengan cita presente en la nota, compartan raíces de contenido con la nota, terminen en `?`, midan 8..140, no estén repetidas ni sean de `preguntas_del_original`. Si falla la llamada, el prompt sale igual con `[]`.

`organizar(original, llamar, modelo, sistema, pista, k=3)`: igual iteración con `temperature 0.2/0.0`, límite `1.6·len + 60`, sin estructura de prompt, mismas pistas («…la nota»); devuelve `{texto, modelo, resumen_iteracion}`.

### 4.4 Prompts de sistema (copiar de `armador.py` / `mateo.py`)
`SISTEMA`, `EJEMPLO_IDEA` / `EJEMPLO_PROMPT` (few-shot sobre «contador de no leídos»; **no cambiarlo por temas de otras notas**: se filtraba al resultado), `SISTEMA_PREGUNTAS`, `SISTEMA_ORGANIZAR`. Son texto normativo: portarlos literalmente y versionarlos.

### 4.5 Modelo / Ollama
`POST {ollama}/api/chat` con `{"model","stream":false,"think":false,"options":{"temperature":0.2, …opciones},"messages":[…]}`; se quita `<think>…</think>` de la salida. Modelo por defecto `qwen3:8b`; `--modelo-prompt` permite otro para prompts. qwen3:14b se midió: no mejora (ratio medio 0,79 vs 0,75) y tarda 10–86 s vs 1–9 s; no usar por defecto.

### 4.6 Banco de equivalencia
`servidor/tests/banco_prompts.py` (script con Ollama real; `--repeticiones 3 [--modelo X] [--ver] [--solo a,b]`). Notas (18): `corta1, corta2, corta_dudas, media_info, larga_dark, sin_dudas_larga, informal_che, codigo_rutas, numeros_versiones, dudas_explicitas, lista, mezcla_en_es, repetida, larga_estado, tecnico_log, pocas_palabras, titulo_cable, cable_usb, ux`. Para el port: copiar las notas a un JSON compartido (`banco_notas.json`) y correr el mismo banco contra la implementación TS.

Métricas por corrida y **criterios de aprobación** (resultado actual del Python, qwen3:8b, 3 repeticiones):
| Métrica | Debe cumplir | Python hoy |
|---|---|---|
| ratio = len(prompt)/len(nota) | **máximo ≤ 1,00** en el 100 % | máx 1,00 |
| ratio medio notas ≥ 500 caracteres | ≤ 0,65 | 0,59 |
| ratio medio (todas) | informativo | ~0,77 |
| literales conservados | 100 % | 100 % |
| tecnologías/nombres inventados dentro del prompt | 0 | 0 |
| frases perdidas sin informar (`dejado_afuera`) y frases recortadas | 0 | 0 |
| `preguntas_sugeridas` | ≤ 3, terminan en `?`, comparten contenido con la nota, ninguna en notas < 120 caracteres, no aparecen en el prompt | ok |
| corridas con falla | 0 | 0/54+ |
Casos fijos que no pueden regresar: `cable_usb` («usb c» conservado o texto original compactado; nada se pierde en silencio), `titulo_cable` (el título no cuenta como frase omitida; «comprar cable usb c» intacta), `larga_dark` (≈ 0,55, `Datos:` sin basura). Además portar los **47 tests unitarios** de `servidor/tests/test_puente.py` (clases `FidelidadTest`, `ArmadorTest`, `IteracionTest`, `OmitidasTest`, `PuntuacionTest`, `DudasTest`) con un `Mateo` falso guionado; esos sí deben pasar tal cual.

---

## 5. Cambios en la app Android y plan por etapas

### 5.1 App (Rite, Kotlin/Compose/Room)
- **Room**: pasar a ids `String` (UUID) o agregar columna `uuid` única por tabla + columnas de sync (`actualizado`, `sucio`, `eliminada`, `baseRev`); migración 2→3 que conserva los datos y genera UUID. Tabla `sync_estado(clave,valor)` con `cursor`, `servidor`, `ultimo_sync`.
- **Cliente de sync** (`red/Sincronizador.kt`): `GET/POST /sync` según 3.2, lotes ≤ 100 filas, reintento con backoff, disparo al abrir / primer plano / cambio de red / periódico; mapeo de ids y resolución de `conflictos` (rotular versiones `conflicto` en «Versiones»).
- **Ajustes**: estado de la PC (conectado / sin conexión / última sincronización), botón «Sincronizar ahora», cursor y servidor (solo lectura), «Desvincular»; la vinculación sigue por `rite://…` (el enlace lo genera Ideario).
- **Sin cambios de contrato** en Mateo/Prompt: se siguen llamando `/mateo/organizar` y `/mateo/prompt` (ahora servidos por Ideario). La UI ya refleja `resumen_iteracion`, `dejado_afuera`, `preguntas_sugeridas`, «Rehacer» con pista y guardado solo a pedido.
- Indicador de «sin conexión» no bloqueante; Mateo/Prompt deshabilitados con aviso cuando no hay PC.

### 5.2 Etapas
**Etapa 1 — Notas + Mateo + motor de prompts dentro de Ideario** (sin celular): tablas `notas_*`; sección «Notas» (carpetas, etiquetas, fijadas, colores, papelera 30 días, versiones, casillas); chat fijado «Mateo» (qwen3:8b); port a TS de `fidelidad/puntuacion/armador` + prompts, con los 47 tests y el banco de equivalencia (4.6); tools MCP de notas de solo lectura/escritura mínima (`notas_buscar`, `nota_leer`, `nota_crear`) para que Claude las lea barato. Importación única desde `%LOCALAPPDATA%\Rite` si hiciera falta (la app guarda sus datos en el celular, no en la PC).
**Etapa 2 — Servidor para el celular dentro de Ideario**: HTTPS con certificado propio (misma huella/enlace `rite://`), vinculación (código 6 dígitos / 10 min / un uso / 5 intentos, token con hash), filtro de IP (privadas/loopback/Tailscale), rutas de la sección 1 + `/sync` (sección 3), escucha en 8787 (o puerto configurable), arranque junto con Ideario (acceso directo / inicio con Windows, a elección de Facundo). Pruebas con un cliente falso (script) antes de tocar el celular.
**Etapa 3 — La app Rite apunta a Ideario y se retira el puente Python**: cliente de sync + migración Room (5.1), prueba de punta a punta en el celular real (offline → online, conflictos, papelera), apagar el autoinicio del puente Python y archivarlo (no borrar sin pedirlo). Envío de notas a Claude Code (antes «etapa 3» del plan original) queda como etapa posterior, con confirmación explícita.

### 5.3 Riesgos / decisiones abiertas
- Ideario debe estar corriendo (o arrancar con Windows) para sincronizar; si no, el celular sigue offline sin pérdida.
- Tailscale: dirección estable `100.x`; el enlace de vinculación debe permitir elegir IP LAN o Tailscale.
- `/estado` hoy lista proyectos de Claude Code (Productor): decidir si se conserva en Ideario.
- Mantener un solo código de motor: el Python queda como referencia/banco hasta que el TS pase la equivalencia; recién después se retira.
