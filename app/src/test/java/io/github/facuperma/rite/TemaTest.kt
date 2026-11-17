package io.github.facuperma.rite

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import io.github.facuperma.rite.ui.EsquemaClaro
import io.github.facuperma.rite.ui.EsquemaOscuro
import io.github.facuperma.rite.ui.ModoTema
import io.github.facuperma.rite.ui.PaletaNotasClara
import io.github.facuperma.rite.ui.PaletaNotasOscura
import io.github.facuperma.rite.ui.PreferenciasTema
import io.github.facuperma.rite.ui.TextoNotaClaro
import io.github.facuperma.rite.ui.TextoNotaOscuro
import io.github.facuperma.rite.ui.colorNota
import io.github.facuperma.rite.ui.contraste
import io.github.facuperma.rite.ui.paletaNotas
import io.github.facuperma.rite.ui.textoNota
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemaTest {
    private val nombres = listOf("sin color", "amarillo", "rojo", "verde", "azul", "violeta")

    @Test
    fun por_defecto_sigue_al_sistema() {
        assertEquals(ModoTema.SISTEMA, ModoTema.desde(null))
        assertEquals(ModoTema.SISTEMA, ModoTema.desde("cualquier cosa"))
        assertEquals(ModoTema.SISTEMA, ModoTema.desde(""))
    }

    @Test
    fun la_eleccion_resuelve_oscuro_o_claro() {
        assertTrue(ModoTema.SISTEMA.esOscuro(true))
        assertTrue(!ModoTema.SISTEMA.esOscuro(false))
        assertTrue(!ModoTema.CLARO.esOscuro(true)) // forzar claro gana al sistema oscuro
        assertTrue(ModoTema.OSCURO.esOscuro(false)) // forzar oscuro gana al sistema claro
    }

    @Test
    fun la_eleccion_se_recuerda_entre_aperturas() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        c.getSharedPreferences("rite_apariencia", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(ModoTema.SISTEMA, PreferenciasTema(c).leer())
        PreferenciasTema(c).guardar(ModoTema.OSCURO)
        assertEquals(ModoTema.OSCURO, PreferenciasTema(c).leer()) // otra instancia = otra apertura
        PreferenciasTema(c).guardar(ModoTema.CLARO)
        assertEquals(ModoTema.CLARO, PreferenciasTema(c).leer())
    }

    @Test
    fun la_preferencia_no_vive_en_la_base_que_se_sincroniza() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        PreferenciasTema(c).guardar(ModoTema.OSCURO)
        // se guarda en un archivo de preferencias propio, no en la tabla `ajuste` ni en la vinculacion
        assertTrue(c.getSharedPreferences("rite_apariencia", Context.MODE_PRIVATE).contains("tema"))
        assertTrue(!c.getSharedPreferences("rite_vinculo", Context.MODE_PRIVATE).contains("tema"))
    }

    @Test
    fun contraste_de_referencia() {
        assertEquals(21.0, contraste(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contraste(Color.White, Color.White), 0.001)
    }

    @Test
    fun las_7_variantes_oscuras_de_notas_tienen_contraste_AA_con_el_texto() {
        assertEquals(6, PaletaNotasOscura.size) // sin color + 5 colores = las 6 entradas de la paleta de la app
        PaletaNotasOscura.forEachIndexed { i, fondo ->
            val c = contraste(TextoNotaOscuro, fondo)
            assertTrue("${nombres[i]} oscuro: contraste $c < 7", c >= 7.0) // AAA, mas que el AA pedido
        }
    }

    @Test
    fun las_variantes_claras_siguen_legibles() {
        PaletaNotasClara.forEachIndexed { i, fondo ->
            val c = contraste(TextoNotaClaro, fondo)
            assertTrue("${nombres[i]} claro: contraste $c < 7", c >= 7.0)
        }
    }

    @Test
    fun colorNota_y_textoNota_siguen_el_tema_y_no_se_salen_de_la_paleta() {
        for (oscuro in listOf(false, true)) {
            assertEquals(paletaNotas(oscuro)[2], colorNota(2, oscuro))
            assertEquals(paletaNotas(oscuro)[0], colorNota(99, oscuro)) // indice desconocido = sin color
            assertEquals(if (oscuro) TextoNotaOscuro else TextoNotaClaro, textoNota(oscuro))
        }
    }

    @Test
    fun el_esquema_oscuro_cumple_AA_en_texto_y_botones() {
        val e = EsquemaOscuro
        val pares = listOf(
            "texto sobre fondo" to (e.onBackground to e.background),
            "texto sobre superficie" to (e.onSurface to e.surface),
            "texto sobre superficie alta (dialogos)" to (e.onSurface to e.surfaceContainerHigh),
            "texto sobre menu" to (e.onSurface to e.surfaceContainer),
            "texto secundario sobre fondo" to (e.onSurfaceVariant to e.background),
            "texto secundario sobre superficie" to (e.onSurfaceVariant to e.surface),
            "texto secundario sobre superficie variante" to (e.onSurfaceVariant to e.surfaceVariant),
            "texto secundario sobre superficie alta" to (e.onSurfaceVariant to e.surfaceContainerHigh),
            "boton relleno" to (e.onPrimary to e.primary),
            "enlaces/botones de texto sobre fondo" to (e.primary to e.background),
            "enlaces/botones de texto sobre superficie alta (dialogos)" to (e.primary to e.surfaceContainerHigh),
            "enlaces/botones de texto sobre notas oscuras" to (e.primary to PaletaNotasOscura[1]),
            "burbuja del usuario en la charla" to (e.onPrimaryContainer to e.primaryContainer),
            "chip seleccionado" to (e.onSecondaryContainer to e.secondaryContainer),
            "error sobre fondo" to (e.error to e.background),
            "error sobre fondo de error" to (e.onErrorContainer to e.errorContainer),
        )
        for ((nombre, par) in pares) {
            val c = contraste(par.first, par.second)
            assertTrue("$nombre: contraste $c < 4.5", c >= 4.5)
        }
        // bordes de campos y botones: no texto, pero 3:1 contra el fondo
        assertTrue(contraste(e.outline, e.background) >= 3.0)
    }

    @Test
    fun el_esquema_oscuro_usa_la_paleta_pedida() {
        assertEquals(Color(0xFF0B0C0E), EsquemaOscuro.background)
        assertEquals(Color(0xFF16171B), EsquemaOscuro.surface)
        assertEquals(Color(0xFF1B1C20), EsquemaOscuro.surfaceVariant)
        assertEquals(Color(0xFFECEDEF), EsquemaOscuro.onSurface)
        assertEquals(Color(0xFFA7ACB4), EsquemaOscuro.onSurfaceVariant)
        assertEquals(Color(0xFF8FB0FF), EsquemaOscuro.primary)
        assertEquals(Color(0xFF2A2C33), EsquemaOscuro.outlineVariant)
        assertEquals(listOf(0xFF1B1C20, 0xFF4A4421, 0xFF4D2A2A, 0xFF27402B, 0xFF26364D, 0xFF3B2D4D).map { Color(it) }, PaletaNotasOscura)
        assertEquals(Color(0xFF3B5BDB), EsquemaClaro.primary) // el tema claro no cambio
    }
}
