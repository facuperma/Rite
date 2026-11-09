package io.github.facuperma.rite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaAjustes(vm: RiteViewModel) {
    val vinculo by vm.vinculo.collectAsState()
    val alcance = rememberCoroutineScope()
    var enlace by remember { mutableStateOf("") }
    var mensaje by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    BackHandler { vm.ajustesAbiertos.value = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = {
                    IconButton(onClick = { vm.ajustesAbiertos.value = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                },
            )
        },
    ) { margen ->
        Column(Modifier.fillMaxSize().padding(margen).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("PC vinculada", style = MaterialTheme.typography.titleMedium)
            val v = vinculo
            if (v?.token != null) {
                Text("Vinculada con ${v.host}:${v.puerto}", modifier = Modifier.padding(top = 8.dp))
                Text(
                    "Huella ${v.huella.take(16)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 12.dp)) {
                    Button(enabled = !ocupado, onClick = {
                        ocupado = true
                        mensaje = "Probando…"
                        alcance.launch {
                            mensaje = vm.estadoPuente().fold(
                                onSuccess = { "Conectado. Mateo: ${if (it.mateo) "disponible (${it.modelo})" else "no responde (¿Ollama abierto?)"}" },
                                onFailure = { it.message },
                            )
                            ocupado = false
                        }
                    }) { Text("Probar conexión") }
                    OutlinedButton(onClick = { vm.desvincular(); mensaje = null }, modifier = Modifier.padding(start = 8.dp)) {
                        Text("Desvincular")
                    }
                }
            } else {
                Text(
                    "En la PC corré «python -m rite_puente vincular» (o «servir») y pegá acá el enlace que empieza con rite://. " +
                        "Vale 10 minutos y se usa una sola vez.",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = enlace,
                    onValueChange = { enlace = it },
                    label = { Text("Enlace de vinculación") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Button(
                    enabled = enlace.isNotBlank() && !ocupado,
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = {
                        ocupado = true
                        mensaje = "Vinculando…"
                        alcance.launch {
                            val error = vm.vincular(enlace)
                            mensaje = error ?: "¡Listo! La PC quedó vinculada."
                            if (error == null) enlace = ""
                            ocupado = false
                        }
                    },
                ) { Text("Vincular") }
            }
            mensaje?.let { Text(it, modifier = Modifier.padding(top = 16.dp)) }
        }
    }
}
