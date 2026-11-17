package io.github.facuperma.rite

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.facuperma.rite.datos.BaseRite
import io.github.facuperma.rite.datos.Repositorio
import io.github.facuperma.rite.datos.Version
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositorioTest {
    private lateinit var base: BaseRite
    private lateinit var repo: Repositorio
    private var ahora = 1_000_000L

    @Before
    fun preparar() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        base = Room.inMemoryDatabaseBuilder(contexto, BaseRite::class.java).allowMainThreadQueries().build()
        repo = Repositorio(base) { ahora }
    }

    @After
    fun cerrar() = base.close()

    private fun lista(carpeta: Long? = null, etiqueta: Long? = null, texto: String = "") =
        runBlocking { repo.notas(carpeta, etiqueta, texto).first() }

    @Test
    fun crear_y_guardar_sella_la_fecha_solo_si_cambia_el_contenido() = runBlocking {
        val id = repo.crearNota(titulo = "Idea")
        val creada = repo.nota(id)!!
        assertEquals(1_000_000L, creada.creada)

        ahora += 5_000
        repo.guardar(creada.copy(cuerpo = "texto nuevo"))
        assertEquals(1_005_000L, repo.nota(id)!!.editada)

        ahora += 5_000
        repo.pintar(id, 3)
        val tras = repo.nota(id)!!
        assertEquals("fecha no cambia si solo cambia el color", 1_005_000L, tras.editada)
        assertEquals(3, tras.color)
        assertEquals(1_000_000L, tras.creada)
    }

    @Test
    fun orden_fijadas_primero_y_luego_las_mas_recientes() = runBlocking {
        val a = repo.crearNota(titulo = "A")
        ahora += 10
        val b = repo.crearNota(titulo = "B")
        ahora += 10
        val c = repo.crearNota(titulo = "C")
        repo.fijar(a, true)
        assertEquals(listOf(a, c, b), lista().map { it.id })
    }

    @Test
    fun busqueda_en_titulo_y_cuerpo_sin_distinguir_mayusculas() = runBlocking {
        repo.crearNota(titulo = "Compras", cuerpo = "leche y pan")
        repo.crearNota(titulo = "Trabajo", cuerpo = "Reunion con Mateo")
        assertEquals(listOf("Trabajo"), lista(texto = "mateo").map { it.titulo })
        assertEquals(listOf("Compras"), lista(texto = "PAN").map { it.titulo })
        assertEquals(2, lista(texto = "  ").size)
    }

    @Test
    fun carpetas_filtran_y_borrar_una_carpeta_no_borra_sus_notas() = runBlocking {
        val c = repo.crearCarpeta("Ideas")
        val n1 = repo.crearNota(carpetaId = c, titulo = "uno")
        repo.crearNota(titulo = "dos")
        assertEquals(listOf(n1), lista(carpeta = c).map { it.id })

        repo.borrarCarpeta(c)
        assertNull(repo.nota(n1)!!.carpetaId)
        assertEquals(2, lista().size)
        assertTrue(repo.carpetas().first().isEmpty())
    }

    @Test
    fun carpeta_sin_nombre_se_rechaza() {
        val error = runCatching { runBlocking { repo.crearCarpeta("   ") } }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun etiquetas_se_reutilizan_filtran_y_las_huerfanas_se_limpian() = runBlocking {
        val a = repo.crearNota(titulo = "a")
        val b = repo.crearNota(titulo = "b")
        repo.etiquetar(a, "#Trabajo")
        repo.etiquetar(b, "trabajo")
        repo.etiquetar(b, "casa")
        repo.etiquetar(b, "  ")

        val todas = repo.etiquetas().first()
        assertEquals(listOf("casa", "Trabajo"), todas.map { it.nombre })
        val trabajo = todas.first { it.nombre == "Trabajo" }
        assertEquals(setOf(a, b), lista(etiqueta = trabajo.id).map { it.id }.toSet())

        repo.desetiquetar(b, todas.first { it.nombre == "casa" }.id)
        assertEquals(listOf("Trabajo"), repo.etiquetas().first().map { it.nombre })
    }

    @Test
    fun papelera_restaurar_y_borrado_definitivo() = runBlocking {
        val id = repo.crearNota(titulo = "x")
        repo.fijar(id, true)
        repo.aPapelera(id)
        assertTrue(lista().isEmpty())
        assertEquals(1, repo.papelera().first().size)
        assertFalse("en la papelera deja de estar fijada", repo.nota(id)!!.fijada)

        repo.restaurar(id)
        assertEquals(1, lista().size)

        repo.aPapelera(id)
        repo.eliminarDefinitivo(id)
        assertNull(repo.nota(id))
    }

    @Test
    fun papelera_vieja_se_vacia_a_los_30_dias_y_no_antes() = runBlocking {
        val vieja = repo.crearNota(titulo = "vieja")
        val reciente = repo.crearNota(titulo = "reciente")
        repo.aPapelera(vieja)
        ahora += 20L * 24 * 60 * 60 * 1000
        repo.aPapelera(reciente)
        ahora += 11L * 24 * 60 * 60 * 1000 // la vieja lleva 31 dias, la reciente 11

        assertEquals(1, repo.limpiarPapeleraVieja())
        assertNull(repo.nota(vieja))
        assertNotNull(repo.nota(reciente))
        assertEquals(1, repo.vaciarPapelera())
    }

    @Test
    fun versiones_se_guardan_por_origen_y_se_borran_con_la_nota() = runBlocking {
        val id = repo.crearNota(titulo = "n")
        repo.guardarVersion(id, Version.USUARIO, "original")
        ahora += 1
        repo.guardarVersion(id, Version.MATEO, "organizada por Mateo")
        val v = repo.versiones(id).first()
        assertEquals(listOf(Version.MATEO, Version.USUARIO), v.map { it.origen })

        repo.eliminarDefinitivo(id)
        assertTrue(repo.versiones(id).first().isEmpty())
    }

    @Test
    fun ajustes_con_valor_por_defecto() = runBlocking {
        assertEquals("10", repo.ajuste("resumen_cada", "10"))
        repo.guardarAjuste("resumen_cada", "15")
        assertEquals("15", repo.ajuste("resumen_cada", "10"))
    }

    @Test
    fun registrar_version_no_repite_la_ultima_igual() = runBlocking {
        val id = repo.crearNota(titulo = "n")
        assertNotNull(repo.registrarVersion(id, Version.USUARIO, "texto"))
        assertNull(repo.registrarVersion(id, Version.USUARIO, "texto"))
        ahora += 1
        assertNotNull(repo.registrarVersion(id, Version.MATEO, "texto"))
        ahora += 1
        assertNull(repo.registrarVersion(id, Version.USUARIO, "texto")) // ya existe una igual: sin duplicados
        assertNotNull(repo.registrarVersion(id, Version.USUARIO, "otro"))
        assertEquals(3, repo.versiones(id).first().size)
    }
}
