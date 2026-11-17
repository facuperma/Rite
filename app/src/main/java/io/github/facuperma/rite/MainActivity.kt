package io.github.facuperma.rite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
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
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF3B5BDB), surface = Color(0xFFFAFAFC), background = Color(0xFFFAFAFC))) {
                val vm: RiteViewModel = viewModel()
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
