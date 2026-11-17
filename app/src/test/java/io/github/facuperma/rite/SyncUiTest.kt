package io.github.facuperma.rite

import io.github.facuperma.rite.red.ResultadoSync
import io.github.facuperma.rite.ui.EstadoSync
import io.github.facuperma.rite.ui.describirSync
import io.github.facuperma.rite.ui.haceCuanto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncUiTest {
    @Test
    fun hace_cuanto() {
        val ahora = 10_000_000L
        assertEquals("recién", haceCuanto(ahora - 5_000, ahora))
        assertEquals("hace 3 min", haceCuanto(ahora - 3 * 60_000, ahora))
        assertEquals("hace 2 h", haceCuanto(ahora - 2 * 3_600_000, ahora))
        assertEquals("hace 2 d", haceCuanto(ahora - 2 * 86_400_000L, ahora))
        assertEquals("recién", haceCuanto(ahora + 1000, ahora)) // reloj adelantado: no da negativo
    }

    @Test
    fun textos_del_indicador() {
        val ahora = 10_000_000L
        assertTrue(describirSync(EstadoSync.SinVincular, ahora).contains("solo en este celular"))
        assertEquals("Sincronizando…", describirSync(EstadoSync.Sincronizando, ahora))
        assertEquals("Sincronizado hace 1 min", describirSync(EstadoSync.AlDia(ahora - 60_000, ResultadoSync()), ahora))
        assertTrue(describirSync(EstadoSync.AlDia(ahora, ResultadoSync(conflictos = 2)), ahora).contains("2 en conflicto"))
        assertTrue(describirSync(EstadoSync.SinConexion("x"), ahora).contains("Sin conexión"))
        assertTrue(describirSync(EstadoSync.ServidorViejo, ahora).contains("puente viejo"))
        assertTrue(describirSync(EstadoSync.Fallo("boom"), ahora).contains("boom"))
    }
}
