package io.github.facuperma.rite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.facuperma.rite.ui.PantallaEditor
import io.github.facuperma.rite.ui.PantallaLista
import io.github.facuperma.rite.ui.RiteViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF3B5BDB), surface = Color(0xFFFAFAFC), background = Color(0xFFFAFAFC))) {
                val vm: RiteViewModel = viewModel()
                val editando by vm.editando.collectAsState()
                val id = editando
                if (id == null) PantallaLista(vm) else PantallaEditor(vm, id)
            }
        }
    }
}
