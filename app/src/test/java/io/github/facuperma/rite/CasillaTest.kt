package io.github.facuperma.rite

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.facuperma.rite.ui.alternarCasilla
import org.junit.Assert.assertEquals
import org.junit.Test

class CasillaTest {
    private val sin = "☐ "
    private val con = "☑ "

    @Test
    fun agrega_la_casilla_en_la_linea_del_cursor() {
        val r = alternarCasilla(TextFieldValue("uno\ndos", TextRange(5)))
        assertEquals("uno\n${sin}dos", r.text)
        assertEquals(7, r.selection.start)
    }

    @Test
    fun la_saca_si_ya_estaba_y_funciona_en_texto_vacio() {
        assertEquals("uno", alternarCasilla(TextFieldValue("${sin}uno", TextRange(3))).text)
        assertEquals("uno", alternarCasilla(TextFieldValue("${con}uno", TextRange(3))).text)
        assertEquals(sin, alternarCasilla(TextFieldValue("", TextRange(0))).text)
    }

    @Test
    fun cursor_al_principio_de_la_primera_linea() {
        assertEquals("${sin}a\nb", alternarCasilla(TextFieldValue("a\nb", TextRange(0))).text)
    }
}
