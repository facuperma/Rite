package io.github.facuperma.rite.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.facuperma.rite.datos.Carpeta
import io.github.facuperma.rite.datos.Nota
import kotlinx.coroutines.launch

/** Paleta de notas. El indice 0 es "sin color". */
val PaletaNotas = listOf(
    Color(0xFFFFFFFF), Color(0xFFFFF3B0), Color(0xFFFFD6D6), Color(0xFFD6F5D6),
    Color(0xFFD6E8FF), Color(0xFFEBD9FF),
)

fun colorNota(i: Int): Color = PaletaNotas.getOrElse(i) { PaletaNotas[0] }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaLista(vm: RiteViewModel) {
    val notas by vm.notas.collectAsState()
    val carpetas by vm.carpetas.collectAsState()
    val etiquetas by vm.etiquetas.collectAsState()
    val filtro by vm.filtro.collectAsState()
    val consulta by vm.consulta.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val alcance = rememberCoroutineScope()
    var buscando by remember { mutableStateOf(false) }
    var dialogoCarpeta by remember { mutableStateOf<Carpeta?>(null) }
    var carpetaNueva by remember { mutableStateOf(false) }
    var borrarCarpeta by remember { mutableStateOf<Carpeta?>(null) }
    var vaciar by remember { mutableStateOf(false) }

    val titulo = when {
        filtro.papelera -> "Papelera"
        filtro.carpetaId != null -> carpetas.firstOrNull { it.id == filtro.carpetaId }?.nombre ?: "Rite"
        filtro.etiquetaId != null -> "#" + (etiquetas.firstOrNull { it.id == filtro.etiquetaId }?.nombre ?: "")
        else -> "Todas las notas"
    }

    fun ir(f: Filtro) {
        vm.mirar(f)
        buscando = false
        alcance.launch { drawer.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
                    item {
                        Text("Rite", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
                        NavigationDrawerItem(
                            label = { Text("Todas las notas") },
                            selected = filtro == Filtro(),
                            onClick = { ir(Filtro()) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        Text("Carpetas", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                    }
                    items(carpetas, key = { "c${it.id}" }) { c ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 8.dp)) {
                            NavigationDrawerItem(
                                label = { Text(c.nombre, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                selected = filtro.carpetaId == c.id,
                                onClick = { ir(Filtro(carpetaId = c.id)) },
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { dialogoCarpeta = c }) { Icon(Icons.Default.Edit, "Renombrar carpeta") }
                            IconButton(onClick = { borrarCarpeta = c }) { Icon(Icons.Default.Delete, "Borrar carpeta") }
                        }
                    }
                    item {
                        TextButton(onClick = { carpetaNueva = true }, modifier = Modifier.padding(horizontal = 20.dp)) { Text("+ Nueva carpeta") }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        if (etiquetas.isNotEmpty()) {
                            Text("Etiquetas", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                        }
                    }
                    items(etiquetas, key = { "e${it.id}" }) { e ->
                        NavigationDrawerItem(
                            label = { Text("#" + e.nombre) },
                            selected = filtro.etiquetaId == e.id,
                            onClick = { ir(Filtro(etiquetaId = e.id)) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        NavigationDrawerItem(
                            label = { Text("Papelera") },
                            selected = filtro.papelera,
                            onClick = { ir(Filtro(papelera = true)) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        NavigationDrawerItem(
                            label = { Text("Charlar con Mateo") },
                            selected = false,
                            onClick = { vm.charlaAbierta.value = true; alcance.launch { drawer.close() } },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        NavigationDrawerItem(
                            label = { Text("Ajustes y PC vinculada") },
                            selected = false,
                            onClick = { vm.ajustesAbiertos.value = true; alcance.launch { drawer.close() } },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (buscando) {
                            OutlinedTextField(
                                value = consulta,
                                onValueChange = { vm.consulta.value = it },
                                placeholder = { Text("Buscar en las notas") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(titulo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { alcance.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Menu") }
                    },
                    actions = {
                        if (filtro.papelera) {
                            if (notas.isNotEmpty()) TextButton(onClick = { vaciar = true }) { Text("Vaciar") }
                        } else if (buscando) {
                            IconButton(onClick = { buscando = false; vm.consulta.value = "" }) { Icon(Icons.Default.Close, "Cerrar busqueda") }
                        } else {
                            IconButton(onClick = { buscando = true }) { Icon(Icons.Default.Search, "Buscar") }
                        }
                    },
                )
            },
            bottomBar = { BarraSync(vm) },
            floatingActionButton = {
                if (!filtro.papelera) {
                    FloatingActionButton(onClick = { vm.nuevaNota() }) { Icon(Icons.Default.Add, "Nueva nota") }
                }
            },
        ) { margen ->
            Box(Modifier.fillMaxSize().padding(margen)) {
                if (notas.isEmpty()) {
                    Text(
                        when {
                            filtro.papelera -> "La papelera esta vacia."
                            consulta.isNotBlank() -> "No hay notas que coincidan."
                            else -> "Todavia no hay notas. Toca + para escribir la primera."
                        },
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(2),
                        contentPadding = PaddingValues(12.dp),
                        verticalItemSpacing = 10.dp,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(notas, key = { it.id }) { n ->
                            TarjetaNota(
                                n,
                                onClick = { if (!filtro.papelera) vm.abrir(n.id) },
                                enPapelera = filtro.papelera,
                                onRestaurar = { vm.restaurar(n.id) },
                                onEliminar = { vm.eliminar(n.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (carpetaNueva) {
        DialogoTexto("Nueva carpeta", "", "Crear", { carpetaNueva = false }) { vm.crearCarpeta(it) }
    }
    dialogoCarpeta?.let { c ->
        DialogoTexto("Renombrar carpeta", c.nombre, "Guardar", { dialogoCarpeta = null }) { vm.renombrarCarpeta(c.id, it) }
    }
    borrarCarpeta?.let { c ->
        AlertDialog(
            onDismissRequest = { borrarCarpeta = null },
            title = { Text("Borrar carpeta") },
            text = { Text("Se borra la carpeta \"${c.nombre}\". Sus notas no se borran: quedan sin carpeta.") },
            confirmButton = { TextButton(onClick = { vm.borrarCarpeta(c.id); borrarCarpeta = null }) { Text("Borrar") } },
            dismissButton = { TextButton(onClick = { borrarCarpeta = null }) { Text("Cancelar") } },
        )
    }
    if (vaciar) {
        AlertDialog(
            onDismissRequest = { vaciar = false },
            title = { Text("Vaciar papelera") },
            text = { Text("Se borran para siempre las ${notas.size} notas de la papelera. No se puede deshacer.") },
            confirmButton = { TextButton(onClick = { vm.vaciarPapelera(); vaciar = false }) { Text("Vaciar") } },
            dismissButton = { TextButton(onClick = { vaciar = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun TarjetaNota(
    n: Nota,
    onClick: () -> Unit,
    enPapelera: Boolean,
    onRestaurar: () -> Unit,
    onEliminar: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colorNota(n.color), contentColor = Color(0xFF1B1B1B)),
        border = BorderStroke(1.dp, Color(0x22000000)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    n.titulo.ifBlank { "Sin titulo" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (n.fijada) Icon(Icons.Default.Star, "Fijada", modifier = Modifier.padding(start = 4.dp))
            }
            if (n.cuerpo.isNotBlank()) {
                Text(
                    n.cuerpo,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (enPapelera) {
                Row(Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = onRestaurar) { Text("Restaurar") }
                    TextButton(onClick = onEliminar) { Text("Borrar") }
                }
            }
        }
    }
}

@Composable
fun DialogoTexto(titulo: String, inicial: String, confirmar: String, cerrar: () -> Unit, aceptar: (String) -> Unit) {
    var texto by remember { mutableStateOf(inicial) }
    AlertDialog(
        onDismissRequest = cerrar,
        title = { Text(titulo) },
        text = { OutlinedTextField(value = texto, onValueChange = { texto = it }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = texto.isNotBlank(), onClick = { aceptar(texto); cerrar() }) { Text(confirmar) }
        },
        dismissButton = { TextButton(onClick = cerrar) { Text("Cancelar") } },
    )
}
