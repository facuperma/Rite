package io.github.facuperma.rite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.facuperma.rite.datos.Nota
import io.github.facuperma.rite.datos.Version
import io.github.facuperma.rite.red.RespuestaOrganizar
import io.github.facuperma.rite.red.ResultadoPrompt
import io.github.facuperma.rite.red.esVersionPrompt
import io.github.facuperma.rite.red.separarTitulo
import io.github.facuperma.rite.red.textoEstimacion
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SIN_MARCAR = "☐ "
private const val MARCADO = "☑ "

/** Pone o saca la casilla de lista en la linea donde esta el cursor. */
fun alternarCasilla(v: TextFieldValue): TextFieldValue {
    val texto = v.text
    val pos = v.selection.start.coerceIn(0, texto.length)
    val inicio = texto.lastIndexOf('\n', pos - 1).let { if (pos == 0) 0 else it + 1 }
    val resto = texto.substring(inicio)
    val nuevo = when {
        resto.startsWith(SIN_MARCAR) -> texto.removeRange(inicio, inicio + SIN_MARCAR.length)
        resto.startsWith(MARCADO) -> texto.removeRange(inicio, inicio + MARCADO.length)
        else -> texto.substring(0, inicio) + SIN_MARCAR + texto.substring(inicio)
    }
    val delta = nuevo.length - texto.length
    val cursor = (v.selection.start + delta).coerceIn(0, nuevo.length)
    return TextFieldValue(nuevo, TextRange(cursor))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaEditor(vm: RiteViewModel, id: Long) {
    var nota by remember(id) { mutableStateOf<Nota?>(null) }
    var titulo by remember(id) { mutableStateOf("") }
    var cuerpo by remember(id) { mutableStateOf(TextFieldValue("")) }
    var cargada by remember(id) { mutableStateOf(false) }
    val etiquetas by vm.etiquetasDe(id).collectAsState(initial = emptyList())
    val carpetas by vm.carpetas.collectAsState()
    var menuCarpeta by remember { mutableStateOf(false) }
    var menuAcciones by remember { mutableStateOf(false) }
    var etiquetaNueva by remember { mutableStateOf("") }

    var estadoMateo by remember { mutableStateOf<EstadoMateo?>(null) }
    var estadoPrompt by remember { mutableStateOf<EstadoPrompt?>(null) }
    var verVersiones by remember { mutableStateOf(false) }
    var confirmarClaude by remember { mutableStateOf<String?>(null) }
    var avisoClaude by remember { mutableStateOf<String?>(null) }
    val portapapeles = LocalClipboardManager.current
    val alcance = rememberCoroutineScope()
    val versiones by vm.versiones(id).collectAsState(initial = emptyList())

    /** Reemplaza título y cuerpo por el texto dado; lo que había queda guardado como versión. */
    fun aplicar(texto: String) {
        alcance.launch {
            vm.guardarSnapshot(id, titulo, cuerpo.text)
            val (t, c) = separarTitulo(texto)
            titulo = t
            cuerpo = TextFieldValue(c, TextRange(c.length))
        }
    }

    var pistaMateo by remember { mutableStateOf("") }
    var pistaPrompt by remember { mutableStateOf("") }

    /** Un solo resultado a la vez: «Rehacer» reemplaza el que se muestra; nada se guarda solo. */
    fun pedirMateo(pista: String) {
        estadoMateo = EstadoMateo.Cargando
        alcance.launch {
            estadoMateo = vm.organizarConMateo(titulo, cuerpo.text, pista).fold(
                onSuccess = { EstadoMateo.Listo(it) },
                onFailure = { EstadoMateo.Error(it.message ?: "Error desconocido") },
            )
        }
    }

    fun pedirPrompt(pista: String) {
        estadoPrompt = EstadoPrompt.Cargando
        alcance.launch {
            estadoPrompt = vm.armarPromptClaude(titulo, cuerpo.text, pista).fold(
                onSuccess = { EstadoPrompt.Listo(it) },
                onFailure = { EstadoPrompt.Error(it.message ?: "Error desconocido") },
            )
        }
    }

    LaunchedEffect(id) {
        vm.cargar(id)?.let {
            nota = it
            titulo = it.titulo
            cuerpo = TextFieldValue(it.cuerpo, TextRange(it.cuerpo.length))
        }
        cargada = true
    }

    // Autoguardado: 0,4 s despues de dejar de escribir.
    LaunchedEffect(titulo, cuerpo.text, cargada) {
        val base = nota ?: return@LaunchedEffect
        if (!cargada) return@LaunchedEffect
        delay(400)
        vm.guardar(base.copy(titulo = titulo, cuerpo = cuerpo.text))
    }

    fun salir() {
        val base = nota
        if (base != null) vm.guardar(base.copy(titulo = titulo, cuerpo = cuerpo.text))
        vm.cerrarEditor()
    }
    BackHandler { salir() }

    val actual = nota
    val oscuro = LocalOscuro.current
    val fondo = colorNota(actual?.color ?: 0, oscuro)

    Scaffold(
        containerColor = fondo,
        contentColor = textoNota(oscuro),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = { salir() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
                actions = {
                    if (actual != null) {
                        Box {
                            IconButton(onClick = { menuAcciones = true }) { Icon(Icons.Default.MoreVert, "Más acciones") }
                            DropdownMenu(expanded = menuAcciones, onDismissRequest = { menuAcciones = false }) {
                                DropdownMenuItem(text = { Text("Mateo") }, onClick = { menuAcciones = false; pistaMateo = ""; pedirMateo("") })
                                DropdownMenuItem(text = { Text("Prompt para Claude") }, onClick = { menuAcciones = false; pistaPrompt = ""; pedirPrompt("") })
                                if (versiones.isNotEmpty()) {
                                    DropdownMenuItem(text = { Text("Versiones (${versiones.size})") }, onClick = { menuAcciones = false; verVersiones = true })
                                }
                            }
                        }
                        IconButton(onClick = {
                            vm.fijar(id, !actual.fijada)
                            nota = actual.copy(fijada = !actual.fijada)
                        }) {
                            Icon(
                                Icons.Default.Star,
                                if (actual.fijada) "Quitar de fijadas" else "Fijar",
                                tint = if (actual.fijada) Color(0xFFE0A100) else LocalContentColorOr(),
                            )
                        }
                        IconButton(onClick = { vm.aPapelera(id); vm.editando.value = null }) { Icon(Icons.Default.Delete, "A la papelera") }
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = fondo),
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding().background(fondo).padding(horizontal = 12.dp, vertical = 8.dp)) {
                LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    items(etiquetas, key = { it.id }) { e ->
                        AssistChip(onClick = { vm.desetiquetar(id, e.id) }, label = { Text("#${e.nombre}  ✕") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = etiquetaNueva,
                        onValueChange = { etiquetaNueva = it },
                        placeholder = { Text("Etiqueta") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.width(140.dp),
                    )
                    TextButton(
                        enabled = etiquetaNueva.isNotBlank(),
                        onClick = { vm.etiquetar(id, etiquetaNueva); etiquetaNueva = "" },
                    ) { Text("Agregar") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { cuerpo = alternarCasilla(cuerpo) }) { Text("☐ Lista") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    paletaNotas(oscuro).forEachIndexed { i, c ->
                        Box(
                            Modifier.padding(end = 10.dp).size(28.dp)
                                .background(c, CircleShape)
                                .border(if ((actual?.color ?: 0) == i) 3.dp else 1.dp, if ((actual?.color ?: 0) == i) (if (oscuro) Color(0xFFECEDEF) else Color(0xFF444444)) else (if (oscuro) Color(0xFF5A5E68) else Color(0x44000000)), CircleShape)
                                .clickable {
                                    vm.pintar(id, i)
                                    nota = actual?.copy(color = i)
                                },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { menuCarpeta = true }) {
                            Text(carpetas.firstOrNull { it.id == actual?.carpetaId }?.nombre ?: "Sin carpeta")
                        }
                        DropdownMenu(expanded = menuCarpeta, onDismissRequest = { menuCarpeta = false }) {
                            DropdownMenuItem(text = { Text("Sin carpeta") }, onClick = {
                                vm.mover(id, null); nota = actual?.copy(carpetaId = null); menuCarpeta = false
                            })
                            carpetas.forEach { c ->
                                DropdownMenuItem(text = { Text(c.nombre) }, onClick = {
                                    vm.mover(id, c.id); nota = actual?.copy(carpetaId = c.id); menuCarpeta = false
                                })
                            }
                        }
                    }
                }
            }
        },
    ) { margen ->
        Column(Modifier.fillMaxSize().padding(margen).verticalScroll(rememberScrollState())) {
            val campo = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            )
            TextField(
                value = titulo,
                onValueChange = { titulo = it },
                placeholder = { Text("Titulo", style = MaterialTheme.typography.headlineSmall) },
                textStyle = MaterialTheme.typography.headlineSmall,
                colors = campo,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = cuerpo,
                onValueChange = { cuerpo = it },
                placeholder = { Text("Escribi aca") },
                colors = campo,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            )
        }
    }

    when (val e = estadoMateo) {
        null -> {}
        EstadoMateo.Cargando -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Mateo está trabajando…") },
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(28.dp)); Text("  La primera vez puede tardar un minuto.") } },
            confirmButton = {},
        )
        is EstadoMateo.Error -> AlertDialog(
            onDismissRequest = { estadoMateo = null },
            title = { Text("No se pudo") },
            text = { Text(e.msg) },
            confirmButton = { TextButton(onClick = { estadoMateo = null }) { Text("Cerrar") } },
        )
        is EstadoMateo.Listo -> AlertDialog(
            onDismissRequest = { estadoMateo = null },
            title = { Text("Versión de Mateo") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    if (e.resultado.resumenIteracion.isNotBlank()) {
                        Text(e.resultado.resumenIteracion, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Nada se guardó todavía; tu nota sigue igual.", style = MaterialTheme.typography.bodySmall)
                    Text(e.resultado.texto, modifier = Modifier.padding(top = 8.dp))
                    RehacerFila(pistaMateo, { pistaMateo = it }) { pedirMateo(pistaMateo) }
                }
            },
            confirmButton = {
                TextButton(onClick = { alcance.launch { vm.guardarMateo(id, e.resultado.texto) }; aplicar(e.resultado.texto); estadoMateo = null }) { Text("Usar en la nota") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { alcance.launch { vm.guardarMateo(id, e.resultado.texto) }; estadoMateo = null }) { Text("Guardar versión") }
                    TextButton(onClick = { estadoMateo = null }) { Text("Cerrar") }
                }
            },
        )
    }

    when (val p = estadoPrompt) {
        null -> {}
        EstadoPrompt.Cargando -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Mateo está armando el prompt…") },
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(28.dp)); Text("  La primera vez puede tardar un minuto.") } },
            confirmButton = {},
        )
        is EstadoPrompt.Error -> AlertDialog(
            onDismissRequest = { estadoPrompt = null },
            title = { Text("No se pudo") },
            text = { Text(p.msg) },
            confirmButton = { TextButton(onClick = { estadoPrompt = null }) { Text("Cerrar") } },
        )
        is EstadoPrompt.Listo -> {
            var editado by remember(p) { mutableStateOf(p.resultado.prompt) }
            var elegida by remember(p) { mutableStateOf<String?>(null) }
            var repuestas by remember(p) { mutableStateOf(emptySet<String>()) }
            AlertDialog(
                onDismissRequest = { estadoPrompt = null },
                title = { Text("Prompt para Claude Code") },
                text = {
                    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            textoEstimacion(p.resultado.caracteresAntes, editado.length),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        if (p.resultado.resumenIteracion.isNotBlank()) {
                            Text(p.resultado.resumenIteracion, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("No se envió nada a Claude ni se guardó nada. Podés editarlo antes de copiarlo.", style = MaterialTheme.typography.bodySmall)
                        p.resultado.advertencias.forEach {
                            Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        }
                        OutlinedTextField(
                            value = editado,
                            onValueChange = { editado = it },
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 140.dp, max = 300.dp),
                        )
                        if (p.resultado.dejadoAfuera.isNotEmpty()) {
                            Text(
                                "Dejé afuera (tocá para volver a agregar):",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            p.resultado.dejadoAfuera.filter { it !in repuestas }.forEach { f ->
                                Text(
                                    "＋ «$f»",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        editado = agregarLinea(editado, f)
                                        repuestas = repuestas + f
                                    }.padding(vertical = 4.dp),
                                )
                            }
                        }
                        if (p.resultado.preguntasSugeridas.isNotEmpty()) {
                            Text(
                                "Preguntas de Mateo (ideas, no entran en el prompt)",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            p.resultado.preguntasSugeridas.forEach { q ->
                                Text(
                                    (if (elegida == q) "▸ " else "• ") + q,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth().clickable { elegida = if (elegida == q) null else q }.padding(vertical = 4.dp),
                                )
                                if (elegida == q) {
                                    Row {
                                        TextButton(onClick = {
                                            cuerpo = agregarPregunta(cuerpo, q)
                                            elegida = null
                                        }) { Text("Agregar a la nota") }
                                        TextButton(onClick = { portapapeles.setText(AnnotatedString(q)); elegida = null }) { Text("Copiar") }
                                    }
                                }
                            }
                        }
                        RehacerFila(pistaPrompt, { pistaPrompt = it }) { pedirPrompt(pistaPrompt) }
                    }
                },
                confirmButton = {
                    Row {
                        TextButton(onClick = { portapapeles.setText(AnnotatedString(editado)) }) { Text("Copiar") }
                        TextButton(onClick = { confirmarClaude = editado }) { Text("A Claude Code") }
                        TextButton(onClick = { alcance.launch { vm.guardarPrompt(id, editado) }; estadoPrompt = null }) { Text("Guardar versión") }
                    }
                },
                dismissButton = { TextButton(onClick = { estadoPrompt = null }) { Text("Cerrar") } },
            )
        }
    }

    confirmarClaude?.let { prompt ->
        AlertDialog(
            onDismissRequest = { confirmarClaude = null },
            title = { Text("Abrir en Claude Code") },
            text = { Text("Se abre Claude Code en la PC con este prompt ya escrito. No se envía: lo revisás y lo mandás vos desde la PC.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmarClaude = null
                    alcance.launch { avisoClaude = vm.enviarAClaudeCode(prompt) }
                }) { Text("Abrir en la PC") }
            },
            dismissButton = { TextButton(onClick = { confirmarClaude = null }) { Text("Cancelar") } },
        )
    }
    avisoClaude?.let { aviso ->
        AlertDialog(
            onDismissRequest = { avisoClaude = null },
            title = { Text("Claude Code") },
            text = { Text(aviso) },
            confirmButton = { TextButton(onClick = { avisoClaude = null }) { Text("Listo") } },
        )
    }

    if (verVersiones) {
        AlertDialog(
            onDismissRequest = { verVersiones = false },
            title = { Text("Versiones") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(versiones, key = { it.id }) { v ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            val quien = when (v.origen) {
                                Version.MATEO -> if (esVersionPrompt(v.metaJson)) "Prompt" else "Mateo"
                                Version.CONFLICTO -> "Otra versión (conflicto)"
                                Version.CLAUDE -> "Claude"
                                else -> "Tuya"
                            }
                            Text(quien, style = MaterialTheme.typography.labelLarge)
                            Text(v.texto, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { aplicar(v.texto); verVersiones = false }) { Text("Restaurar") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { verVersiones = false }) { Text("Cerrar") } },
        )
    }
}

sealed interface EstadoPrompt {
    data object Cargando : EstadoPrompt
    data class Listo(val resultado: ResultadoPrompt) : EstadoPrompt
    data class Error(val msg: String) : EstadoPrompt
}

/** Agrega una línea al final de un texto (sin dejar espacios de más). */
fun agregarLinea(texto: String, linea: String): String {
    val base = texto.trimEnd()
    return if (base.isEmpty()) linea else base + "\n" + linea
}

/** Agrega una pregunta al final de la nota (una línea nueva). */
fun agregarPregunta(v: TextFieldValue, pregunta: String): TextFieldValue {
    val base = v.text.trimEnd()
    val nuevo = if (base.isEmpty()) pregunta else base + "\n" + pregunta
    return TextFieldValue(nuevo, TextRange(nuevo.length))
}

@Composable
private fun RehacerFila(pista: String, alCambiar: (String) -> Unit, rehacer: () -> Unit) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = pista,
            onValueChange = alCambiar,
            placeholder = { Text("Pista (opcional)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = rehacer) { Text("Rehacer") }
    }
}

sealed interface EstadoMateo {
    data object Cargando : EstadoMateo
    data class Listo(val resultado: RespuestaOrganizar) : EstadoMateo
    data class Error(val msg: String) : EstadoMateo
}

@Composable
private fun LocalContentColorOr(): Color = androidx.compose.material3.LocalContentColor.current
