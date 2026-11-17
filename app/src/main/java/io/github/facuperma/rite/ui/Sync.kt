package io.github.facuperma.rite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** «hace 3 min» a partir de dos instantes en milisegundos. */
fun haceCuanto(desde: Long, ahora: Long): String {
    val s = ((ahora - desde) / 1000).coerceAtLeast(0)
    return when {
        s < 45 -> "recién"
        s < 3600 -> "hace ${(s / 60).coerceAtLeast(1)} min"
        s < 86400 -> "hace ${s / 3600} h"
        else -> "hace ${s / 86400} d"
    }
}

/** Texto corto y claro para el indicador de sincronización. */
fun describirSync(e: EstadoSync, ahora: Long = System.currentTimeMillis()): String = when (e) {
    EstadoSync.SinVincular -> "Sin PC vinculada · las notas quedan solo en este celular"
    EstadoSync.Sincronizando -> "Sincronizando…"
    is EstadoSync.AlDia -> buildString {
        append("Sincronizado ${haceCuanto(e.ultimo, ahora)}")
        if (e.resultado.conflictos > 0) append(" · ${e.resultado.conflictos} en conflicto (quedan las dos versiones)")
    }
    is EstadoSync.SinConexion -> "Sin conexión con la PC · tus notas se guardan y suben al volver"
    EstadoSync.ServidorViejo -> "La PC vinculada es el puente viejo: no sincroniza notas"
    is EstadoSync.Fallo -> "No se pudo sincronizar: ${e.mensaje}"
}

/** Línea fina al pie de la lista. Tocarla fuerza una sincronización. */
@Composable
fun BarraSync(vm: RiteViewModel) {
    val e by vm.estadoSync.collectAsState()
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().clickable { vm.sincronizar() }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            describirSync(e),
            style = MaterialTheme.typography.labelSmall,
            color = if (e is EstadoSync.Fallo) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
