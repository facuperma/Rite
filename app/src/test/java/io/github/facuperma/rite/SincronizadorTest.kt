package io.github.facuperma.rite

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.facuperma.rite.datos.BaseRite
import io.github.facuperma.rite.datos.Repositorio
import io.github.facuperma.rite.datos.Version
import io.github.facuperma.rite.red.ErrorPuente
import io.github.facuperma.rite.red.EstadoPuente
import io.github.facuperma.rite.red.ServidorSync
import io.github.facuperma.rite.red.Sincronizador
import io.github.facuperma.rite.red.revDe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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

/** Servidor falso: acepta todo lo que se sube (devuelve «aceptadas» con los ids) y baja lo que se le prepare. */
class ServidorFalso : ServidorSync {
    var soportaNotas = true
    var idServidor = "srv-1"
    var caido = false
    var paginas = ArrayDeque<JSONObject>()
    val subidos = mutableListOf<JSONObject>()
    val pedidosBajar = mutableListOf<Long>()
    var respuestaExtra: (JSONObject) -> JSONObject = { it }
    var lanzar410 = 0

    override suspend fun estado(): EstadoPuente {
        if (caido) throw ErrorPuente("No llego a la PC")
        return EstadoPuente(mateo = true, modelo = "qwen3:8b", notas = soportaNotas, servidor = idServidor)
    }

    override suspend fun bajar(desde: Long, limite: Int): JSONObject {
        if (caido) throw ErrorPuente("No llego a la PC")
        pedidosBajar += desde
        if (lanzar410 > 0) { lanzar410--; throw ErrorPuente("Sincronizacion vieja", 410) }
        return paginas.removeFirstOrNull() ?: JSONObject().put("servidor", idServidor).put("cursor", desde).put("hay_mas", false)
    }

    override suspend fun subir(cuerpo: JSONObject): JSONObject {
        if (caido) throw ErrorPuente("No llego a la PC")
        subidos += cuerpo
        val acept = JSONObject()
        for (k in listOf("carpetas", "etiquetas", "notas", "versiones")) {
            val a = cuerpo.optJSONArray(k) ?: JSONArray()
            acept.put(k, JSONArray((0 until a.length()).map { a.getJSONObject(it).getString("id") }))
        }
        val rel = cuerpo.optJSONArray("notas_etiquetas") ?: JSONArray()
        acept.put("notas_etiquetas", JSONArray((0 until rel.length()).map { rel.getJSONObject(it).let { r -> r.getString("notaId") + ":" + r.getString("etiquetaId") } }))
        return respuestaExtra(JSONObject().put("cursor", 1).put("aceptadas", acept).put("conflictos", JSONArray()).put("mapeo", JSONObject()))
    }

    fun pagina(cursor: Long, hayMas: Boolean = false, build: JSONObject.() -> Unit): JSONObject {
        val p = JSONObject().put("servidor", idServidor).put("cursor", cursor).put("hay_mas", hayMas)
        for (k in listOf("carpetas", "notas", "etiquetas", "notas_etiquetas", "versiones")) p.put(k, JSONArray())
        p.build()
        return p
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SincronizadorTest {
    private lateinit var base: BaseRite
    private lateinit var repo: Repositorio
    private lateinit var srv: ServidorFalso
    private var ahora = 2_000_000L

    @Before
    fun preparar() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        base = Room.inMemoryDatabaseBuilder(c, BaseRite::class.java).allowMainThreadQueries().build()
        repo = Repositorio(base) { ahora }
        srv = ServidorFalso()
    }

    @After
    fun cerrar() = base.close()

    private fun sinc() = Sincronizador(base, srv, "S23") { ahora }
    private fun nota(uuid: String, titulo: String, cuerpo: String, act: Long, extra: JSONObject.() -> Unit = {}) =
        JSONObject().put("id", uuid).put("carpetaId", JSONObject.NULL).put("titulo", titulo).put("cuerpo", cuerpo).put("fijada", false)
            .put("color", 0).put("creada", act).put("editada", act).put("borradaEn", JSONObject.NULL).put("baseRev", revDe(titulo, cuerpo))
            .put("actualizado", act).put("eliminada", false).put("seq", 1).also(extra)

    @Test
    fun puente_viejo_sin_notas_no_sincroniza_y_no_toca_nada() = runBlocking {
        val id = repo.crearNota(titulo = "a", cuerpo = "b")
        srv.soportaNotas = false
        val r = sinc().sincronizar()
        assertFalse(r.soportado)
        assertTrue(srv.subidos.isEmpty())
        assertTrue(base.notas().obtener(id)!!.sucio)
    }

    @Test
    fun sube_lo_sucio_con_ids_estables_y_lo_marca_limpio() = runBlocking {
        val carpeta = repo.crearCarpeta("Trabajo")
        val id = repo.crearNota(carpetaId = carpeta, titulo = "Idea", cuerpo = "texto")
        repo.etiquetar(id, "urgente")
        repo.registrarVersion(id, Version.MATEO, "mejorado")
        val r = sinc().sincronizar()

        assertTrue(r.soportado)
        val cuerpo = srv.subidos.single()
        val n = cuerpo.getJSONArray("notas").getJSONObject(0)
        val local = base.notas().obtener(id)!!
        assertEquals(local.uuid, n.getString("id"))
        assertEquals(base.carpetas().obtener(carpeta)!!.uuid, n.getString("carpetaId"))
        assertEquals("texto", n.getString("cuerpo"))
        assertEquals(1, cuerpo.getJSONArray("carpetas").length())
        assertEquals(1, cuerpo.getJSONArray("etiquetas").length())
        assertEquals(1, cuerpo.getJSONArray("notas_etiquetas").length())
        assertEquals("mejorado", cuerpo.getJSONArray("versiones").getJSONObject(0).getString("texto"))
        assertEquals("S23", cuerpo.getString("dispositivo"))

        assertFalse(base.notas().obtener(id)!!.sucio)
        assertEquals(revDe("Idea", "texto"), base.notas().obtener(id)!!.baseRev)
        assertTrue(base.notas().sucias().isEmpty() && base.carpetas().sucias().isEmpty() && base.etiquetas().sucias().isEmpty())
        assertTrue(base.etiquetas().relacionesSucias().isEmpty() && base.versiones().sucias().isEmpty())
    }

    @Test
    fun una_segunda_sincronizacion_sin_cambios_no_sube_nada() = runBlocking {
        repo.crearNota(titulo = "a", cuerpo = "b")
        sinc().sincronizar()
        srv.subidos.clear()
        sinc().sincronizar()
        assertTrue(srv.subidos.isEmpty())
    }

    @Test
    fun sube_en_lotes_de_100() = runBlocking {
        repeat(250) { repo.crearNota(titulo = "n$it", cuerpo = "c") }
        sinc().sincronizar()
        assertEquals(listOf(100, 100, 50), srv.subidos.map { it.getJSONArray("notas").length() })
    }

    @Test
    fun baja_notas_carpetas_etiquetas_relaciones_y_versiones() = runBlocking {
        srv.paginas += srv.pagina(7) {
            getJSONArray("carpetas").put(JSONObject().put("id", "c1").put("nombre", "PC").put("color", 2).put("orden", 0).put("actualizado", 10).put("borrado", false))
            getJSONArray("etiquetas").put(JSONObject().put("id", "e1").put("nombre", "idea").put("actualizado", 10).put("eliminada", false))
            getJSONArray("notas").put(nota("n1", "Desde la PC", "hola", 10) { put("carpetaId", "c1"); put("fijada", true) })
            getJSONArray("notas_etiquetas").put(JSONObject().put("notaId", "n1").put("etiquetaId", "e1").put("actualizado", 10).put("quitada", false))
            getJSONArray("versiones").put(JSONObject().put("id", "v1").put("notaId", "n1").put("origen", "mateo").put("texto", "mejor").put("creada", 11).put("metaJson", "{}"))
        }
        val r = sinc().sincronizar()
        assertTrue(r.bajadas >= 5)
        val n = base.notas().porUuid("n1")!!
        assertEquals("Desde la PC", n.titulo)
        assertTrue(n.fijada)
        assertFalse(n.sucio)
        assertEquals(base.carpetas().porUuid("c1")!!.id, n.carpetaId)
        assertEquals(listOf("idea"), repo.etiquetasDe(n.id).first().map { it.nombre })
        assertEquals(listOf("mejor"), repo.versiones(n.id).first().map { it.texto })
        assertEquals(revDe("Desde la PC", "hola"), n.baseRev)
        assertTrue(base.notas().sucias().isEmpty())
        assertEquals("7", base.ajustes().leer(Sincronizador.CLAVE_CURSOR))
        assertEquals("srv-1", base.ajustes().leer(Sincronizador.CLAVE_SERVIDOR))
    }

    @Test
    fun el_cursor_guardado_se_usa_en_la_siguiente_sincronizacion() = runBlocking {
        srv.paginas += srv.pagina(42) { }
        sinc().sincronizar()
        sinc().sincronizar()
        assertEquals(listOf(0L, 42L), srv.pedidosBajar)
    }

    @Test
    fun pagina_varias_veces_mientras_hay_mas() = runBlocking {
        srv.paginas += srv.pagina(5, hayMas = true) { getJSONArray("notas").put(nota("a", "A", "1", 1)) }
        srv.paginas += srv.pagina(9, hayMas = false) { getJSONArray("notas").put(nota("b", "B", "2", 2)) }
        sinc().sincronizar()
        assertEquals(listOf(0L, 5L), srv.pedidosBajar)
        assertNotNull(base.notas().porUuid("a"))
        assertNotNull(base.notas().porUuid("b"))
        assertEquals("9", base.ajustes().leer(Sincronizador.CLAVE_CURSOR))
    }

    @Test
    fun cursor_viejo_410_resincroniza_desde_cero_y_conserva_lo_pendiente() = runBlocking {
        base.ajustes().guardar(io.github.facuperma.rite.datos.Ajuste(Sincronizador.CLAVE_CURSOR, "999"))
        base.ajustes().guardar(io.github.facuperma.rite.datos.Ajuste(Sincronizador.CLAVE_SERVIDOR, "srv-1"))
        val id = repo.crearNota(titulo = "mia", cuerpo = "sin subir")
        srv.lanzar410 = 1
        srv.paginas += srv.pagina(3) { getJSONArray("notas").put(nota("x", "X", "del servidor", 5)) }
        val r = sinc().sincronizar()
        assertTrue(r.resincronizo)
        assertEquals(listOf(999L, 0L), srv.pedidosBajar)
        assertEquals("sin subir", base.notas().obtener(id)!!.cuerpo)
        assertEquals("del servidor", base.notas().porUuid("x")!!.cuerpo)
    }

    @Test
    fun servidor_distinto_vuelve_a_subir_todo() = runBlocking {
        repo.crearNota(titulo = "a", cuerpo = "b")
        sinc().sincronizar()
        srv.subidos.clear()
        srv.idServidor = "srv-NUEVO" // otra base
        sinc().sincronizar()
        assertEquals(1, srv.subidos.sumOf { it.getJSONArray("notas").length() })
        assertEquals("srv-NUEVO", base.ajustes().leer(Sincronizador.CLAVE_SERVIDOR))
    }

    @Test
    fun conflicto_edicion_local_sin_subir_y_servidor_distinto_conserva_ambos_textos() = runBlocking {
        val id = repo.crearNota(titulo = "T", cuerpo = "original")
        val uuid = base.notas().obtener(id)!!.uuid
        sinc().sincronizar()
        ahora += 1000
        repo.guardar(repo.nota(id)!!.copy(cuerpo = "edicion en el celular"))
        // el servidor responde con la version de la PC (la edicion del celular no se subio porque la red se cayo a mitad)
        srv.caido = true
        runCatching { sinc().sincronizar() }
        srv.caido = false
        srv.paginas += srv.pagina(20) { getJSONArray("notas").put(nota(uuid, "T", "edicion en la PC", 3000)) }
        // simula que el POST no llegó a subir la nota: la hacemos fallar para forzar el camino de «bajar con sucia local»
        srv.respuestaExtra = { r -> r.put("aceptadas", JSONObject().put("notas", JSONArray())) }
        sinc().sincronizar()

        val n = base.notas().porUuid(uuid)!!
        assertEquals("edicion en la PC", n.cuerpo)
        val textos = repo.versiones(n.id).first()
        assertTrue(textos.any { it.origen == Version.CONFLICTO && it.texto.contains("edicion en el celular") })
    }

    @Test
    fun borrar_para_siempre_sube_una_lapida_y_se_olvida_al_aceptarla() = runBlocking {
        val id = repo.crearNota(titulo = "borrame", cuerpo = "x")
        val uuid = base.notas().obtener(id)!!.uuid
        sinc().sincronizar()
        srv.subidos.clear()
        repo.aPapelera(id)
        repo.eliminarDefinitivo(id)
        sinc().sincronizar()
        val n = srv.subidos.flatMap { s -> (0 until s.getJSONArray("notas").length()).map { s.getJSONArray("notas").getJSONObject(it) } }.single()
        assertEquals(uuid, n.getString("id"))
        assertTrue(n.getBoolean("eliminada"))
        assertEquals(revDe("borrame", "x"), n.getString("baseRev"))
        assertTrue(base.lapidas().todas().isEmpty())
    }

    @Test
    fun sin_conexion_la_app_no_pierde_nada_y_sigue_pendiente() = runBlocking {
        val id = repo.crearNota(titulo = "offline", cuerpo = "x")
        srv.caido = true
        val r = runCatching { sinc().sincronizar() }
        assertTrue(r.exceptionOrNull() is ErrorPuente)
        assertEquals("offline", base.notas().obtener(id)!!.titulo)
        assertTrue(base.notas().obtener(id)!!.sucio)
        srv.caido = false
        sinc().sincronizar()
        assertFalse(base.notas().obtener(id)!!.sucio)
    }

    @Test
    fun el_servidor_borra_una_nota_y_se_va_de_la_papelera_y_del_celular() = runBlocking {
        val id = repo.crearNota(titulo = "a", cuerpo = "b")
        sinc().sincronizar()
        val uuid = base.notas().obtener(id)!!.uuid
        srv.paginas += srv.pagina(8) { getJSONArray("notas").put(nota(uuid, "a", "b", 9) { put("eliminada", true) }) }
        sinc().sincronizar()
        assertNull(base.notas().obtener(id))
    }

    @Test
    fun papelera_viaja_como_campo_y_se_restaura() = runBlocking {
        srv.paginas += srv.pagina(1) { getJSONArray("notas").put(nota("p1", "vieja", "x", 5) { put("borradaEn", 4) }) }
        sinc().sincronizar()
        assertEquals(4L, base.notas().porUuid("p1")!!.borradaEn)
        srv.paginas += srv.pagina(2) { getJSONArray("notas").put(nota("p1", "vieja", "x", 6) { put("borradaEn", JSONObject.NULL) }) }
        sinc().sincronizar()
        assertNull(base.notas().porUuid("p1")!!.borradaEn)
    }

    @Test
    fun etiqueta_con_mismo_nombre_y_otro_id_se_unifica_con_la_del_servidor() = runBlocking {
        val id = repo.crearNota(titulo = "a", cuerpo = "b")
        repo.etiquetar(id, "viaje")
        val local = base.etiquetas().buscar("viaje")!!
        srv.respuestaExtra = { it.put("mapeo", JSONObject().put(local.uuid, "uuid-del-servidor")) }
        sinc().sincronizar()
        assertEquals("uuid-del-servidor", base.etiquetas().buscar("viaje")!!.uuid)
    }

    @Test
    fun quitar_una_etiqueta_se_sincroniza_como_quitada() = runBlocking {
        val id = repo.crearNota(titulo = "a", cuerpo = "b")
        val otra = repo.crearNota(titulo = "c", cuerpo = "d")
        repo.etiquetar(id, "x")
        repo.etiquetar(otra, "x") // sigue en uso: la etiqueta no se borra
        sinc().sincronizar()
        srv.subidos.clear()
        val e = base.etiquetas().buscar("x")!!
        ahora += 10
        repo.desetiquetar(id, e.id)
        sinc().sincronizar()
        val rel = srv.subidos.flatMap { s -> (0 until s.getJSONArray("notas_etiquetas").length()).map { s.getJSONArray("notas_etiquetas").getJSONObject(it) } }
        assertTrue(rel.single().getBoolean("quitada"))
        assertTrue(repo.etiquetasDe(id).first().isEmpty())
        assertEquals(1, repo.etiquetasDe(otra).first().size)
    }

    @Test
    fun la_etiqueta_sin_notas_se_borra_y_se_avisa_con_una_lapida() = runBlocking {
        val id = repo.crearNota(titulo = "a", cuerpo = "b")
        repo.etiquetar(id, "solita")
        sinc().sincronizar()
        srv.subidos.clear()
        val e = base.etiquetas().buscar("solita")!!
        repo.desetiquetar(id, e.id)
        sinc().sincronizar()
        val eti = srv.subidos.flatMap { s -> (0 until s.getJSONArray("etiquetas").length()).map { s.getJSONArray("etiquetas").getJSONObject(it) } }.single()
        assertEquals(e.uuid, eti.getString("id"))
        assertTrue(eti.getBoolean("eliminada"))
        assertNull(base.etiquetas().buscar("solita"))
    }
}
