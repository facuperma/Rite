package io.github.facuperma.rite.datos

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/** Unico punto de acceso a los datos de Rite. El reloj se inyecta para poder probarlo. */
class Repositorio(
    private val base: BaseRite,
    private val reloj: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val DIAS_PAPELERA = 30
        private const val DIA_MS = 24L * 60 * 60 * 1000
    }

    // ---- Notas ----
    fun notas(carpeta: Long?, etiqueta: Long?, texto: String): Flow<List<Nota>> =
        base.notas().observar(carpeta, etiqueta, texto.trim())

    fun papelera(): Flow<List<Nota>> = base.notas().observarPapelera()

    suspend fun nota(id: Long): Nota? = base.notas().obtener(id)

    suspend fun crearNota(carpetaId: Long? = null, titulo: String = "", cuerpo: String = ""): Long {
        val ahora = reloj()
        return base.notas().insertar(
            Nota(carpetaId = carpetaId, titulo = titulo, cuerpo = cuerpo, creada = ahora, editada = ahora, actualizado = ahora),
        )
    }

    /**
     * Guarda el contenido (titulo y cuerpo) y sella la fecha de edicion. El resto de los campos se toma de lo que hay en la base,
     * no del objeto que llega (puede estar viejo si hubo un sync en el medio). Si no cambio nada, no toca nada.
     */
    suspend fun guardar(nota: Nota) {
        val actual = base.notas().obtener(nota.id) ?: return
        if (actual.titulo == nota.titulo && actual.cuerpo == nota.cuerpo) return
        val ahora = reloj()
        base.notas().actualizar(actual.copy(titulo = nota.titulo, cuerpo = nota.cuerpo, editada = ahora, actualizado = ahora, sucio = true))
    }

    suspend fun fijar(id: Long, fijada: Boolean) = cambiar(id) { it.copy(fijada = fijada) }
    suspend fun pintar(id: Long, color: Int) = cambiar(id) { it.copy(color = color) }
    suspend fun mover(id: Long, carpetaId: Long?) = cambiar(id) { it.copy(carpetaId = carpetaId) }
    suspend fun aPapelera(id: Long) = cambiar(id) { it.copy(borradaEn = reloj(), fijada = false) }
    suspend fun restaurar(id: Long) = cambiar(id) { it.copy(borradaEn = null) }

    private suspend fun cambiar(id: Long, f: (Nota) -> Nota) {
        val n = base.notas().obtener(id) ?: return
        base.notas().actualizar(f(n).copy(actualizado = reloj(), sucio = true))
    }

    private suspend fun lapidaDe(n: Nota) =
        base.lapidas().insertar(
            Lapida(n.uuid, "nota", n.titulo, n.cuerpo, n.baseRev, n.creada, n.editada, reloj()),
        )

    suspend fun eliminarDefinitivo(id: Long) {
        base.withTransaction {
            base.notas().obtener(id)?.let { lapidaDe(it) }
            base.notas().borrarDefinitivo(id)
            limpiarHuerfanas()
        }
    }

    suspend fun vaciarPapelera(): Int = base.withTransaction {
        base.notas().enPapelera().forEach { lapidaDe(it) }
        base.notas().vaciarPapelera().also { limpiarHuerfanas() }
    }

    /** Borra de verdad lo que lleva mas de 30 dias en la papelera. Devuelve cuantas notas se fueron. */
    suspend fun limpiarPapeleraVieja(): Int = base.withTransaction {
        val limite = reloj() - DIAS_PAPELERA * DIA_MS
        base.notas().papeleraAnteriorA(limite).forEach { lapidaDe(it) }
        base.notas().vaciarPapeleraAnteriorA(limite).also { limpiarHuerfanas() }
    }

    // ---- Carpetas ----
    fun carpetas(): Flow<List<Carpeta>> = base.carpetas().observar()

    suspend fun crearCarpeta(nombre: String, color: Int = 0): Long {
        val limpio = nombre.trim()
        require(limpio.isNotEmpty()) { "La carpeta necesita un nombre" }
        return base.carpetas().insertar(
            Carpeta(nombre = limpio, color = color, orden = base.carpetas().siguienteOrden(), actualizado = reloj()),
        )
    }

    suspend fun renombrarCarpeta(id: Long, nombre: String) {
        val limpio = nombre.trim()
        require(limpio.isNotEmpty()) { "La carpeta necesita un nombre" }
        val c = base.carpetas().obtener(id) ?: return
        base.carpetas().actualizar(c.copy(nombre = limpio, actualizado = reloj(), sucio = true))
    }

    /** Las notas de la carpeta no se borran: pasan a "sin carpeta". */
    suspend fun borrarCarpeta(id: Long) {
        base.withTransaction {
            base.carpetas().obtener(id)?.let { base.lapidas().insertar(Lapida(it.uuid, "carpeta", it.nombre, actualizado = reloj())) }
            base.carpetas().borrar(id)
        }
    }

    // ---- Etiquetas ----
    fun etiquetas(): Flow<List<Etiqueta>> = base.etiquetas().observar()
    fun etiquetasDe(notaId: Long): Flow<List<Etiqueta>> = base.etiquetas().observarDe(notaId)

    suspend fun etiquetar(notaId: Long, nombre: String) {
        val limpio = nombre.trim().trimStart('#').trim()
        if (limpio.isEmpty()) return
        base.withTransaction {
            val ahora = reloj()
            val id = base.etiquetas().buscar(limpio)?.id ?: base.etiquetas().insertar(Etiqueta(nombre = limpio, actualizado = ahora))
            base.etiquetas().asignar(NotaEtiqueta(notaId, id, actualizado = ahora, quitada = false, sucio = true))
        }
    }

    suspend fun desetiquetar(notaId: Long, etiquetaId: Long) {
        base.withTransaction {
            val r = base.etiquetas().relacion(notaId, etiquetaId)
            if (r != null) base.etiquetas().asignar(r.copy(quitada = true, actualizado = reloj(), sucio = true))
            limpiarHuerfanas()
        }
    }

    /** Borra las etiquetas sin ninguna nota (y avisa al servidor con una lapida). Va dentro de una transaccion. */
    private suspend fun limpiarHuerfanas() {
        for (e in base.etiquetas().huerfanas()) {
            base.lapidas().insertar(Lapida(e.uuid, "etiqueta", e.nombre, actualizado = reloj()))
            base.etiquetas().borrar(e.id)
        }
    }

    // ---- Versiones ----
    fun versiones(notaId: Long): Flow<List<Version>> = base.versiones().observarDe(notaId)

    suspend fun guardarVersion(notaId: Long, origen: String, texto: String, metaJson: String = "{}"): Long =
        base.versiones().insertar(Version(notaId = notaId, origen = origen, texto = texto, creada = reloj(), metaJson = metaJson))

    /** Como guardarVersion, pero no repite una versión del mismo origen y texto que ya exista (devuelve null). */
    suspend fun registrarVersion(notaId: Long, origen: String, texto: String, metaJson: String = "{}"): Long? {
        if (base.versiones().contar(notaId, origen, texto) > 0) return null
        return guardarVersion(notaId, origen, texto, metaJson)
    }

    // ---- Ajustes ----
    suspend fun ajuste(clave: String, porDefecto: String): String = base.ajustes().leer(clave) ?: porDefecto
    suspend fun guardarAjuste(clave: String, valor: String) = base.ajustes().guardar(Ajuste(clave, valor))
}
