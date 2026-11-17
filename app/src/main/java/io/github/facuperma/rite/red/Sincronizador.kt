package io.github.facuperma.rite.red

import androidx.room.withTransaction
import io.github.facuperma.rite.datos.Ajuste
import io.github.facuperma.rite.datos.BaseRite
import io.github.facuperma.rite.datos.Carpeta
import io.github.facuperma.rite.datos.Etiqueta
import io.github.facuperma.rite.datos.Nota
import io.github.facuperma.rite.datos.NotaEtiqueta
import io.github.facuperma.rite.datos.Version
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Hash del contenido de una nota (igual que el servidor: sha256 de «titulo\n\ncuerpo», 16 hex). */
fun revDe(titulo: String, cuerpo: String): String =
    MessageDigest.getInstance("SHA-256").digest("$titulo\n\n$cuerpo".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(16)

data class ResultadoSync(
    /** false = el servidor no sincroniza notas (puente Python viejo). */
    val soportado: Boolean = true,
    val subidas: Int = 0,
    val bajadas: Int = 0,
    val conflictos: Int = 0,
    val resincronizo: Boolean = false,
)

/**
 * Sincroniza la base local con B.O.B. (contrato: docs/INTEGRACION_IDEARIO.md, seccion 3).
 * Primero sube lo «sucio» en lotes, despues baja desde el cursor. Nunca pierde texto: ante un conflicto
 * los dos textos quedan (uno como nota, el otro como version «conflicto»).
 */
class Sincronizador(
    private val base: BaseRite,
    private val servidor: ServidorSync,
    private val dispositivo: String,
    private val reloj: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val LOTE = 100
        const val PAGINA = 500
        const val CLAVE_CURSOR = "sync_cursor"
        const val CLAVE_SERVIDOR = "sync_servidor"
        const val CLAVE_ULTIMO = "sync_ultimo"
    }

    private class Fila(val tipo: String, val json: JSONObject, val clave: String, val actualizado: Long, val extra: String? = null)

    private var servidorId = ""
    private var subidas = 0
    private var bajadas = 0
    private var conflictos = 0
    private var resincronizo = false

    suspend fun sincronizar(): ResultadoSync {
        subidas = 0; bajadas = 0; conflictos = 0; resincronizo = false
        val estado = servidor.estado()
        if (!estado.notas) return ResultadoSync(soportado = false)
        servidorId = estado.servidor
        var cursor = ajusteLong(CLAVE_CURSOR)
        val guardado = ajuste(CLAVE_SERVIDOR)
        if (guardado.isNotEmpty() && servidorId.isNotEmpty() && guardado != servidorId) {
            // Es otro servidor (otra base): hay que subirle todo de nuevo.
            ensuciarTodo()
            cursor = 0
            resincronizo = true
        }
        if (servidorId.isNotEmpty()) guardarAjuste(CLAVE_SERVIDOR, servidorId)

        for (ronda in 1..2) {
            subir(cursor)
            cursor = bajar(cursor)
            if (!hayPendientes()) break
        }
        guardarAjuste(CLAVE_ULTIMO, reloj().toString())
        return ResultadoSync(true, subidas, bajadas, conflictos, resincronizo)
    }

    // ---------------------------------------------------------------- subir

    private suspend fun recolectar(): List<Fila> {
        val filas = mutableListOf<Fila>()
        for (c in base.carpetas().sucias()) {
            filas += Fila(
                "carpetas",
                JSONObject().put("id", c.uuid).put("nombre", c.nombre).put("color", c.color).put("orden", c.orden)
                    .put("actualizado", c.actualizado).put("borrado", false),
                c.uuid, c.actualizado,
            )
        }
        for (e in base.etiquetas().sucias()) {
            filas += Fila(
                "etiquetas",
                JSONObject().put("id", e.uuid).put("nombre", e.nombre).put("actualizado", e.actualizado).put("eliminada", false),
                e.uuid, e.actualizado,
            )
        }
        for (n in base.notas().sucias()) {
            val carpeta = n.carpetaId?.let { base.carpetas().obtener(it)?.uuid }
            val rev = revDe(n.titulo, n.cuerpo)
            filas += Fila(
                "notas",
                JSONObject().put("id", n.uuid).put("carpetaId", carpeta ?: JSONObject.NULL).put("titulo", n.titulo).put("cuerpo", n.cuerpo)
                    .put("fijada", n.fijada).put("color", n.color).put("creada", n.creada).put("editada", n.editada)
                    .put("borradaEn", n.borradaEn ?: JSONObject.NULL).put("baseRev", n.baseRev ?: "")
                    .put("actualizado", n.actualizado).put("eliminada", false),
                n.uuid, n.actualizado, rev,
            )
        }
        for (r in base.etiquetas().relacionesSucias()) {
            val nota = base.notas().obtener(r.notaId) ?: continue
            val eti = base.etiquetas().obtener(r.etiquetaId) ?: continue
            filas += Fila(
                "notas_etiquetas",
                JSONObject().put("notaId", nota.uuid).put("etiquetaId", eti.uuid).put("actualizado", r.actualizado).put("quitada", r.quitada),
                "${nota.uuid}:${eti.uuid}", r.actualizado, "${r.notaId}:${r.etiquetaId}",
            )
        }
        for (v in base.versiones().sucias()) {
            val nota = base.notas().obtener(v.notaId) ?: continue
            filas += Fila(
                "versiones",
                JSONObject().put("id", v.uuid).put("notaId", nota.uuid).put("origen", v.origen).put("texto", v.texto)
                    .put("creada", v.creada).put("metaJson", v.metaJson),
                v.uuid, 0,
            )
        }
        for (l in base.lapidas().todas()) {
            val j = JSONObject().put("id", l.uuid).put("actualizado", l.actualizado)
            val tipo = when (l.tipo) {
                "nota" -> {
                    j.put("titulo", l.titulo).put("cuerpo", l.cuerpo).put("creada", l.creada).put("editada", l.editada)
                        .put("baseRev", l.baseRev ?: "").put("eliminada", true).put("fijada", false).put("color", 0)
                    "notas"
                }
                "carpeta" -> { j.put("nombre", l.titulo).put("borrado", true); "carpetas" }
                else -> { j.put("nombre", l.titulo).put("eliminada", true); "etiquetas" }
            }
            filas += Fila(tipo, j, l.uuid, l.actualizado, "lapida")
        }
        return filas
    }

    private suspend fun subir(cursor: Long) {
        val filas = recolectar()
        for (lote in filas.chunked(LOTE)) {
            val cuerpo = JSONObject().put("servidor", servidorId).put("dispositivo", dispositivo).put("desde", cursor)
            for (tipo in listOf("carpetas", "etiquetas", "notas", "notas_etiquetas", "versiones")) {
                cuerpo.put(tipo, JSONArray().also { a -> lote.filter { it.tipo == tipo }.forEach { a.put(it.json) } })
            }
            val r = servidor.subir(cuerpo)
            marcarAceptadas(r, lote)
            conflictos += r.optJSONArray("conflictos")?.length() ?: 0
            subidas += lote.size
        }
    }

    private fun conjunto(r: JSONObject, nombre: String): Set<String> {
        val a = r.optJSONObject("aceptadas")?.optJSONArray(nombre) ?: return emptySet()
        return (0 until a.length()).map { a.getString(it) }.toSet()
    }

    private suspend fun marcarAceptadas(r: JSONObject, lote: List<Fila>) {
        val aceptadas = listOf("carpetas", "etiquetas", "notas", "notas_etiquetas", "versiones").associateWith { conjunto(r, it) }
        base.withTransaction {
            for (f in lote) {
                if (f.clave !in aceptadas.getValue(f.tipo)) continue
                if (f.extra == "lapida") {
                    base.lapidas().borrar(f.clave)
                    continue
                }
                when (f.tipo) {
                    "carpetas" -> base.carpetas().limpiar(f.clave, f.actualizado)
                    "etiquetas" -> base.etiquetas().limpiar(f.clave, f.actualizado)
                    "notas" -> base.notas().limpiar(f.clave, f.actualizado, f.extra)
                    "notas_etiquetas" -> {
                        val (n, e) = f.extra!!.split(":").map { it.toLong() }
                        base.etiquetas().limpiarRelacion(n, e, f.actualizado)
                    }
                    "versiones" -> base.versiones().limpiar(f.clave)
                }
            }
            r.optJSONObject("mapeo")?.let { m ->
                for (viejo in m.keys()) unificarEtiqueta(viejo, m.getString(viejo))
            }
        }
    }

    /** El servidor ya tenia una etiqueta con ese nombre bajo otro id: se adopta el del servidor. */
    private suspend fun unificarEtiqueta(uuidViejo: String, uuidNuevo: String) {
        val viejo = base.etiquetas().porUuid(uuidViejo) ?: return
        val nuevo = base.etiquetas().porUuid(uuidNuevo)
        if (nuevo == null) {
            base.etiquetas().actualizar(viejo.copy(uuid = uuidNuevo, sucio = false))
            return
        }
        for (rel in base.etiquetas().relacionesDe(viejo.id)) {
            if (base.etiquetas().relacion(rel.notaId, nuevo.id) == null) {
                base.etiquetas().asignar(rel.copy(etiquetaId = nuevo.id, sucio = true))
            }
        }
        base.etiquetas().borrar(viejo.id)
    }

    // ---------------------------------------------------------------- bajar

    private suspend fun bajar(desdeInicial: Long): Long {
        var cursor = desdeInicial
        var reintento = false
        while (true) {
            val pagina = try {
                servidor.bajar(cursor, PAGINA)
            } catch (e: ErrorPuente) {
                if (e.codigo == 410 && !reintento) {
                    // Cursor viejo: sincronizacion completa. Lo local pendiente se conserva (sigue «sucio»).
                    reintento = true
                    resincronizo = true
                    cursor = 0
                    continue
                }
                throw e
            }
            base.withTransaction { aplicarPagina(pagina) }
            cursor = maxOf(cursor, pagina.optLong("cursor"))
            guardarAjuste(CLAVE_CURSOR, cursor.toString())
            if (!pagina.optBoolean("hay_mas")) return cursor
        }
    }

    private suspend fun aplicarPagina(p: JSONObject) {
        for (o in objetos(p, "carpetas")) aplicarCarpeta(o)
        for (o in objetos(p, "etiquetas")) aplicarEtiqueta(o)
        for (o in objetos(p, "notas")) aplicarNota(o)
        for (o in objetos(p, "notas_etiquetas")) aplicarRelacion(o)
        for (o in objetos(p, "versiones")) aplicarVersion(o)
    }

    private fun objetos(p: JSONObject, clave: String): List<JSONObject> {
        val a = p.optJSONArray(clave) ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private suspend fun aplicarCarpeta(o: JSONObject) {
        val uuid = o.getString("id")
        val local = base.carpetas().porUuid(uuid)
        val act = o.optLong("actualizado")
        if (o.optBoolean("borrado")) {
            if (local != null) base.carpetas().borrar(local.id)
            base.lapidas().borrar(uuid)
            bajadas++
            return
        }
        if (local == null) {
            base.carpetas().insertar(
                Carpeta(nombre = o.optString("nombre", "Carpeta"), color = o.optInt("color"), orden = o.optInt("orden"), uuid = uuid, actualizado = act, sucio = false),
            )
        } else if (!(local.sucio && local.actualizado > act)) {
            base.carpetas().actualizar(local.copy(nombre = o.optString("nombre", local.nombre), color = o.optInt("color"), orden = o.optInt("orden"), actualizado = act, sucio = false))
        }
        bajadas++
    }

    private suspend fun aplicarEtiqueta(o: JSONObject) {
        val uuid = o.getString("id")
        val nombre = o.optString("nombre").trim()
        val act = o.optLong("actualizado")
        val local = base.etiquetas().porUuid(uuid)
        if (o.optBoolean("eliminada")) {
            if (local != null) base.etiquetas().borrar(local.id)
            base.lapidas().borrar(uuid)
            bajadas++
            return
        }
        if (nombre.isEmpty()) return
        if (local == null) {
            val mismoNombre = base.etiquetas().buscar(nombre)
            if (mismoNombre != null) {
                base.etiquetas().actualizar(mismoNombre.copy(uuid = uuid, actualizado = act, sucio = false)) // mismo nombre: se adopta el id del servidor
            } else {
                base.etiquetas().insertar(Etiqueta(nombre = nombre, uuid = uuid, actualizado = act, sucio = false))
            }
        } else if (!(local.sucio && local.actualizado > act)) {
            runCatching { base.etiquetas().actualizar(local.copy(nombre = nombre, actualizado = act, sucio = false)) }
        }
        bajadas++
    }

    private suspend fun aplicarNota(o: JSONObject) {
        val uuid = o.getString("id")
        val local = base.notas().porUuid(uuid)
        if (o.optBoolean("eliminada")) {
            if (local != null) base.notas().borrarDefinitivo(local.id)
            base.lapidas().borrar(uuid)
            bajadas++
            return
        }
        val titulo = o.optString("titulo")
        val cuerpo = o.optString("cuerpo")
        val act = o.optLong("actualizado")
        val carpetaId = if (o.isNull("carpetaId")) null else base.carpetas().porUuid(o.optString("carpetaId"))?.id
        val borradaEn = if (o.isNull("borradaEn")) null else o.getLong("borradaEn")
        val rev = o.optString("baseRev").ifEmpty { revDe(titulo, cuerpo) }
        if (local == null) {
            base.notas().insertar(
                Nota(
                    carpetaId = carpetaId, titulo = titulo, cuerpo = cuerpo, fijada = o.optBoolean("fijada"), color = o.optInt("color"),
                    creada = o.optLong("creada"), editada = o.optLong("editada"), borradaEn = borradaEn, uuid = uuid,
                    actualizado = act, sucio = false, baseRev = rev,
                ),
            )
        } else {
            val textoDistinto = local.titulo != titulo || local.cuerpo != cuerpo
            if (local.sucio) {
                if (!textoDistinto && local.actualizado > act) return // solo metadatos locales mas nuevos: se suben despues
                if (textoDistinto) guardarConflictoLocal(local)
            }
            base.notas().actualizar(
                local.copy(
                    carpetaId = carpetaId, titulo = titulo, cuerpo = cuerpo, fijada = o.optBoolean("fijada"), color = o.optInt("color"),
                    editada = o.optLong("editada"), borradaEn = borradaEn, actualizado = act, sucio = false, baseRev = rev,
                ),
            )
        }
        bajadas++
    }

    /** El texto local no subido se guarda como version «conflicto» antes de pisarlo con el del servidor. */
    private suspend fun guardarConflictoLocal(n: Nota) {
        val texto = if (n.titulo.isBlank()) n.cuerpo else "${n.titulo}\n\n${n.cuerpo}"
        if (texto.isBlank() || base.versiones().contar(n.id, Version.CONFLICTO, texto) > 0) return
        val meta = JSONObject().put("de", "celular").put("dispositivo", dispositivo.take(60)).toString()
        base.versiones().insertar(Version(notaId = n.id, origen = Version.CONFLICTO, texto = texto, creada = reloj(), metaJson = meta, sucio = true))
        conflictos++
    }

    private suspend fun aplicarRelacion(o: JSONObject) {
        val nota = base.notas().porUuid(o.optString("notaId")) ?: return
        val eti = base.etiquetas().porUuid(o.optString("etiquetaId")) ?: return
        val act = o.optLong("actualizado")
        val local = base.etiquetas().relacion(nota.id, eti.id)
        if (local != null && local.sucio && local.actualizado > act) return
        base.etiquetas().asignar(NotaEtiqueta(nota.id, eti.id, actualizado = act, quitada = o.optBoolean("quitada"), sucio = false))
        bajadas++
    }

    private suspend fun aplicarVersion(o: JSONObject) {
        val uuid = o.getString("id")
        if (base.versiones().existeUuid(uuid) > 0) return
        val nota = base.notas().porUuid(o.optString("notaId")) ?: return
        val origen = o.optString("origen")
        val texto = o.optString("texto")
        if (base.versiones().contar(nota.id, origen, texto) > 0) return
        base.versiones().insertar(
            Version(notaId = nota.id, origen = origen, texto = texto, creada = o.optLong("creada"), metaJson = o.optString("metaJson", "{}"), uuid = uuid, sucio = false),
        )
        bajadas++
    }

    // ---------------------------------------------------------------- utilidades

    private suspend fun hayPendientes(): Boolean = recolectar().isNotEmpty()

    private suspend fun ensuciarTodo() {
        base.notas().ensuciarTodas()
        base.carpetas().ensuciarTodas()
        base.etiquetas().ensuciarTodas()
        base.etiquetas().ensuciarRelaciones()
        base.versiones().ensuciarTodas()
    }

    private suspend fun ajuste(clave: String) = base.ajustes().leer(clave) ?: ""
    private suspend fun ajusteLong(clave: String) = ajuste(clave).toLongOrNull() ?: 0L
    private suspend fun guardarAjuste(clave: String, valor: String) = base.ajustes().guardar(Ajuste(clave, valor))
}
