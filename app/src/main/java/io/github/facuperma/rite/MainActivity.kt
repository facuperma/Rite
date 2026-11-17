package io.github.facuperma.rite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.SystemBarStyle
import io.github.facuperma.rite.ui.EsquemaClaro
import io.github.facuperma.rite.ui.EsquemaOscuro
import io.github.facuperma.rite.ui.LocalOscuro
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.facuperma.rite.ui.PantallaAjustes
import io.github.facuperma.rite.ui.PantallaCharla
import io.github.facuperma.rite.ui.PantallaEditor
import io.github.facuperma.rite.ui.PantallaLista
import io.github.facuperma.rite.ui.RiteViewModel

class MainActivity : ComponentActivity() {
    private val modelo by viewModels<RiteViewModel>()

    /** Al volver a primer plano se sincroniza (si la PC no esta, la app sigue andando igual). */
    override fun onStart() {
        super.onStart()
        modelo.sincronizar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: RiteViewModel = viewModel()
            val modo by vm.modoTema.collectAsState()
            val oscuro = modo.esOscuro(isSystemInDarkTheme())
            // Barras de estado y navegacion: iconos claros sobre fondo oscuro, y al reves.
            DisposableEffect(oscuro) {
                enableEdgeToEdge(
                    statusBarStyle = if (oscuro) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = if (oscuro) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                )
                // el fondo de la ventana acompaña al tema (si no, se ve un destello claro al abrir el teclado o al girar)
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable((if (oscuro) EsquemaOscuro else EsquemaClaro).background.toArgb()))
                onDispose { }
            }
            MaterialTheme(colorScheme = if (oscuro) EsquemaOscuro else EsquemaClaro) {
              CompositionLocalProvider(LocalOscuro provides oscuro) {
                Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                val editando by vm.editando.collectAsState()
                val ajustes by vm.ajustesAbiertos.collectAsState()
                val charla by vm.charlaAbierta.collectAsState()
                val id = editando
                when {
                    id != null -> PantallaEditor(vm, id)
                    ajustes -> PantallaAjustes(vm)
                    charla -> PantallaCharla(vm)
                    else -> PantallaLista(vm)
                }
                }
              }
            }
        }
    }
}
