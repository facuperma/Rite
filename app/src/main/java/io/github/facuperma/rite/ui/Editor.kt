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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.facuperma.rite.datos.Nota
import kotlinx.coroutines.delay

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
    var etiquetaNueva by remember { mutableStateOf("") }

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
    val fondo = colorNota(actual?.color ?: 0)

    Scaffold(
        containerColor = fondo,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = { salir() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
                actions = {
                    if (actual != null) {
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
            Column(Modifier.imePadding().background(fondo).padding(horizontal = 12.dp, vertical = 8.dp)) {
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
                    PaletaNotas.forEachIndexed { i, c ->
                        Box(
                            Modifier.padding(end = 10.dp).size(28.dp)
                                .background(c, CircleShape)
                                .border(if ((actual?.color ?: 0) == i) 3.dp else 1.dp, if ((actual?.color ?: 0) == i) Color(0xFF444444) else Color(0x44000000), CircleShape)
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
}

@Composable
private fun LocalContentColorOr(): Color = androidx.compose.material3.LocalContentColor.current
