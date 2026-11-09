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
            Nota(carpetaId = carpetaId, titulo = titulo, cuerpo = cuerpo, creada = ahora, editada = ahora),
        )
    }

    /** Guarda cambios de contenido y sella la fecha de edicion. Si no cambio nada, no toca la fecha. */
    suspend fun guardar(nota: Nota) {
        val actual = base.notas().obtener(nota.id) ?: return
        val cambio = actual.titulo != nota.titulo || actual.cuerpo != nota.cuerpo
        base.notas().actualizar(
            nota.copy(
                creada = actual.creada,
                borradaEn = actual.borradaEn,
                editada = if (cambio) reloj() else actual.editada,
            ),
        )
    }

    suspend fun fijar(id: Long, fijada: Boolean) = cambiar(id) { it.copy(fijada = fijada) }
    suspend fun pintar(id: Long, color: Int) = cambiar(id) { it.copy(color = color) }
    suspend fun mover(id: Long, carpetaId: Long?) = cambiar(id) { it.copy(carpetaId = carpetaId) }
    suspend fun aPapelera(id: Long) = cambiar(id) { it.copy(borradaEn = reloj(), fijada = false) }
    suspend fun restaurar(id: Long) = cambiar(id) { it.copy(borradaEn = null) }

    private suspend fun cambiar(id: Long, f: (Nota) -> Nota) {
        val n = base.notas().obtener(id) ?: return
        base.notas().actualizar(f(n))
    }

    suspend fun eliminarDefinitivo(id: Long) {
        base.withTransaction {
            base.notas().borrarDefinitivo(id)
            base.etiquetas().limpiarHuerfanas()
        }
    }

    suspend fun vaciarPapelera(): Int = base.withTransaction {
        base.notas().vaciarPapelera().also { base.etiquetas().limpiarHuerfanas() }
    }

    /** Borra de verdad lo que lleva mas de 30 dias en la papelera. Devuelve cuantas notas se fueron. */
    suspend fun limpiarPapeleraVieja(): Int = base.withTransaction {
        base.notas().vaciarPapeleraAnteriorA(reloj() - DIAS_PAPELERA * DIA_MS)
            .also { base.etiquetas().limpiarHuerfanas() }
    }

    // ---- Carpetas ----
    fun carpetas(): Flow<List<Carpeta>> = base.carpetas().observar()

    suspend fun crearCarpeta(nombre: String, color: Int = 0): Long {
        val limpio = nombre.trim()
        require(limpio.isNotEmpty()) { "La carpeta necesita un nombre" }
        return base.carpetas().insertar(Carpeta(nombre = limpio, color = color, orden = base.carpetas().siguienteOrden()))
    }

    suspend fun renombrarCarpeta(id: Long, nombre: String) {
        val limpio = nombre.trim()
        require(limpio.isNotEmpty()) { "La carpeta necesita un nombre" }
        val c = base.carpetas().obtener(id) ?: return
        base.carpetas().actualizar(c.copy(nombre = limpio))
    }

    /** Las notas de la carpeta no se borran: pasan a "sin carpeta". */
    suspend fun borrarCarpeta(id: Long) = base.carpetas().borrar(id)

    // ---- Etiquetas ----
    fun etiquetas(): Flow<List<Etiqueta>> = base.etiquetas().observar()
    fun etiquetasDe(notaId: Long): Flow<List<Etiqueta>> = base.etiquetas().observarDe(notaId)

    suspend fun etiquetar(notaId: Long, nombre: String) {
        val limpio = nombre.trim().trimStart('#').trim()
        if (limpio.isEmpty()) return
        base.withTransaction {
            val id = base.etiquetas().buscar(limpio)?.id ?: base.etiquetas().insertar(Etiqueta(nombre = limpio))
            base.etiquetas().asignar(NotaEtiqueta(notaId, id))
        }
    }

    suspend fun desetiquetar(notaId: Long, etiquetaId: Long) {
        base.withTransaction {
            base.etiquetas().quitar(notaId, etiquetaId)
            base.etiquetas().limpiarHuerfanas()
        }
    }

    // ---- Versiones ----
    fun versiones(notaId: Long): Flow<List<Version>> = base.versiones().observarDe(notaId)

    suspend fun guardarVersion(notaId: Long, origen: String, texto: String, metaJson: String = "{}"): Long =
        base.versiones().insertar(Version(notaId = notaId, origen = origen, texto = texto, creada = reloj(), metaJson = metaJson))

    // ---- Ajustes ----
    suspend fun ajuste(clave: String, porDefecto: String): String = base.ajustes().leer(clave) ?: porDefecto
    suspend fun guardarAjuste(clave: String, valor: String) = base.ajustes().guardar(Ajuste(clave, valor))
}
