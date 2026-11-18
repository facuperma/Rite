package io.github.facuperma.rite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Charla con Mateo (B.O.B.). Las respuestas viven solo en esta pantalla: no se guardan solas en ninguna nota.
 * Copiar o «Guardar como nota» son acciones explícitas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaCharla(vm: RiteViewModel) {
    val mensajes by vm.charla.collectAsState()
    val pensando by vm.charlando.collectAsState()
    val alcance = rememberCoroutineScope()
    val portapapeles = LocalClipboardManager.current
    val lista = rememberLazyListState()
    var texto by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var guardada by remember { mutableStateOf<String?>(null) }
    BackHandler { vm.charlaAbierta.value = false }
    val chats by vm.chatsDisponibles.collectAsState()
    val elegido by vm.chatElegido.collectAsState()
    LaunchedEffect(Unit) { vm.cargarChats() }

    LaunchedEffect(mensajes.size, pensando) {
        if (mensajes.isNotEmpty()) lista.animateScrollToItem(mensajes.size)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chats.firstOrNull { it.id == elegido }?.titulo ?: "Mateo") },
                navigationIcon = { IconButton(onClick = { vm.charlaAbierta.value = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
                actions = {
                    if (mensajes.isNotEmpty()) TextButton(onClick = { vm.limpiarCharla() }) { Text("Limpiar") }
                },
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding().padding(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp)) }
                Row(verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = texto,
                        onValueChange = { texto = it },
                        placeholder = { Text("Escribile a ${chats.firstOrNull { it.id == elegido }?.titulo ?: "Mateo"}") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                    )
                    Button(
                        enabled = texto.isNotBlank() && !pensando,
                        modifier = Modifier.padding(start = 8.dp),
                        onClick = {
                            val t = texto
                            texto = ""
                            error = null
                            alcance.launch { error = vm.charlar(t) }
                        },
                    ) { Text("Enviar") }
                }
            }
        },
    ) { margen ->
        Column(Modifier.fillMaxSize().padding(margen)) {
            if (chats.size > 1) {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(chats.size) { i ->
                        val c = chats[i]
                        androidx.compose.material3.FilterChip(
                            selected = c.id == elegido,
                            onClick = { vm.elegirChat(c.id) },
                            label = { Text(c.titulo) },
                        )
                    }
                }
                Text(
                    chats.firstOrNull { it.id == elegido }?.modelo.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            Text(
                "La charla queda en el chat elegido en la PC. Acá nada se guarda solo: copiá o guardá como nota lo que te sirva.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(state = lista, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(mensajes.size) { i ->
                    val m = mensajes[i]
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.deMateo) Alignment.CenterStart else Alignment.CenterEnd) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (m.deMateo) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(m.texto)
                                if (m.deMateo) {
                                    Row {
                                        TextButton(onClick = { portapapeles.setText(AnnotatedString(m.texto)) }) { Text("Copiar") }
                                        TextButton(onClick = { vm.notaDesdeTexto(m.texto); guardada = "Guardada como nota nueva" }) { Text("Guardar como nota") }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    if (pensando) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text("  Mateo está pensando… (la primera vez puede tardar un minuto)")
                        }
                    } else if (mensajes.isNotEmpty() && (chats.isEmpty() || chats.firstOrNull()?.id == elegido)) {
                        TextButton(onClick = { error = null; alcance.launch { error = vm.repasarConArturo() } }) { Text("No me convence: que lo repase Arturo") }
                    }
                    guardada?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp)) }
                }
            }
        }
    }
}
