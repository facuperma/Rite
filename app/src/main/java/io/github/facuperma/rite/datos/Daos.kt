package io.github.facuperma.rite.datos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NotaDao {
    /** Notas activas: fijadas primero, luego las editadas hace menos. Filtra por carpeta, etiqueta y texto. */
    @Query(
        """
        SELECT * FROM nota
        WHERE borradaEn IS NULL
          AND (:carpeta IS NULL OR carpetaId = :carpeta)
          AND (:etiqueta IS NULL OR id IN (SELECT notaId FROM nota_etiqueta WHERE etiquetaId = :etiqueta AND quitada = 0))
          AND (:texto = '' OR titulo LIKE '%' || :texto || '%' OR cuerpo LIKE '%' || :texto || '%')
        ORDER BY fijada DESC, editada DESC
        """,
    )
    fun observar(carpeta: Long?, etiqueta: Long?, texto: String): Flow<List<Nota>>

    @Query("SELECT * FROM nota WHERE borradaEn IS NOT NULL ORDER BY borradaEn DESC")
    fun observarPapelera(): Flow<List<Nota>>

    @Query("SELECT * FROM nota WHERE id = :id")
    suspend fun obtener(id: Long): Nota?

    @Query("SELECT * FROM nota WHERE uuid = :uuid")
    suspend fun porUuid(uuid: String): Nota?

    @Insert
    suspend fun insertar(nota: Nota): Long

    @Update
    suspend fun actualizar(nota: Nota)

    @Query("DELETE FROM nota WHERE id = :id")
    suspend fun borrarDefinitivo(id: Long)

    @Query("SELECT * FROM nota WHERE borradaEn IS NOT NULL AND borradaEn < :limite")
    suspend fun papeleraAnteriorA(limite: Long): List<Nota>

    @Query("SELECT * FROM nota WHERE borradaEn IS NOT NULL")
    suspend fun enPapelera(): List<Nota>

    @Query("DELETE FROM nota WHERE borradaEn IS NOT NULL AND borradaEn < :limite")
    suspend fun vaciarPapeleraAnteriorA(limite: Long): Int

    @Query("DELETE FROM nota WHERE borradaEn IS NOT NULL")
    suspend fun vaciarPapelera(): Int

    @Query("SELECT COUNT(*) FROM nota WHERE borradaEn IS NULL")
    suspend fun contarActivas(): Int

    @Query("SELECT * FROM nota WHERE sucio = 1")
    suspend fun sucias(): List<Nota>

    @Query("UPDATE nota SET sucio = 0, baseRev = :baseRev WHERE uuid = :uuid AND actualizado = :actualizado")
    suspend fun limpiar(uuid: String, actualizado: Long, baseRev: String?)

    @Query("UPDATE nota SET sucio = 1")
    suspend fun ensuciarTodas()
}

@Dao
interface CarpetaDao {
    @Query("SELECT * FROM carpeta ORDER BY orden, nombre")
    fun observar(): Flow<List<Carpeta>>

    @Query("SELECT * FROM carpeta WHERE id = :id")
    suspend fun obtener(id: Long): Carpeta?

    @Query("SELECT * FROM carpeta WHERE uuid = :uuid")
    suspend fun porUuid(uuid: String): Carpeta?

    @Insert
    suspend fun insertar(carpeta: Carpeta): Long

    @Update
    suspend fun actualizar(carpeta: Carpeta)

    @Query("DELETE FROM carpeta WHERE id = :id")
    suspend fun borrar(id: Long)

    @Query("SELECT COALESCE(MAX(orden), -1) + 1 FROM carpeta")
    suspend fun siguienteOrden(): Int

    @Query("SELECT * FROM carpeta WHERE sucio = 1")
    suspend fun sucias(): List<Carpeta>

    @Query("UPDATE carpeta SET sucio = 0 WHERE uuid = :uuid AND actualizado = :actualizado")
    suspend fun limpiar(uuid: String, actualizado: Long)

    @Query("UPDATE carpeta SET sucio = 1")
    suspend fun ensuciarTodas()
}

@Dao
interface EtiquetaDao {
    @Query("SELECT * FROM etiqueta ORDER BY nombre COLLATE NOCASE")
    fun observar(): Flow<List<Etiqueta>>

    @Query(
        """
        SELECT etiqueta.* FROM etiqueta
        JOIN nota_etiqueta ON nota_etiqueta.etiquetaId = etiqueta.id
        WHERE nota_etiqueta.notaId = :notaId AND nota_etiqueta.quitada = 0 ORDER BY etiqueta.nombre COLLATE NOCASE
        """,
    )
    fun observarDe(notaId: Long): Flow<List<Etiqueta>>

    @Query("SELECT * FROM etiqueta WHERE nombre = :nombre COLLATE NOCASE LIMIT 1")
    suspend fun buscar(nombre: String): Etiqueta?

    @Query("SELECT * FROM etiqueta WHERE uuid = :uuid")
    suspend fun porUuid(uuid: String): Etiqueta?

    @Query("SELECT * FROM etiqueta WHERE id = :id")
    suspend fun obtener(id: Long): Etiqueta?

    @Insert
    suspend fun insertar(etiqueta: Etiqueta): Long

    @Update
    suspend fun actualizar(etiqueta: Etiqueta)

    @Query("DELETE FROM etiqueta WHERE id = :id")
    suspend fun borrar(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun asignar(relacion: NotaEtiqueta)

    @Query("SELECT * FROM nota_etiqueta WHERE notaId = :notaId AND etiquetaId = :etiquetaId")
    suspend fun relacion(notaId: Long, etiquetaId: Long): NotaEtiqueta?

    @Query("SELECT * FROM nota_etiqueta WHERE etiquetaId = :etiquetaId")
    suspend fun relacionesDe(etiquetaId: Long): List<NotaEtiqueta>

    @Query("SELECT * FROM nota_etiqueta WHERE sucio = 1")
    suspend fun relacionesSucias(): List<NotaEtiqueta>

    @Query("UPDATE nota_etiqueta SET sucio = 0 WHERE notaId = :notaId AND etiquetaId = :etiquetaId AND actualizado = :actualizado")
    suspend fun limpiarRelacion(notaId: Long, etiquetaId: Long, actualizado: Long)

    @Query("SELECT * FROM etiqueta WHERE sucio = 1")
    suspend fun sucias(): List<Etiqueta>

    @Query("UPDATE etiqueta SET sucio = 0 WHERE uuid = :uuid AND actualizado = :actualizado")
    suspend fun limpiar(uuid: String, actualizado: Long)

    @Query("UPDATE etiqueta SET sucio = 1")
    suspend fun ensuciarTodas()

    @Query("UPDATE nota_etiqueta SET sucio = 1")
    suspend fun ensuciarRelaciones()

    /** Etiquetas que quedaron sin ninguna nota. */
    @Query("SELECT * FROM etiqueta WHERE id NOT IN (SELECT DISTINCT etiquetaId FROM nota_etiqueta WHERE quitada = 0)")
    suspend fun huerfanas(): List<Etiqueta>
}

@Dao
interface VersionDao {
    @Query("SELECT * FROM version WHERE notaId = :notaId ORDER BY creada DESC, id DESC")
    fun observarDe(notaId: Long): Flow<List<Version>>

    @Query("SELECT * FROM version WHERE notaId = :notaId ORDER BY creada DESC, id DESC LIMIT 1")
    suspend fun ultima(notaId: Long): Version?

    @Query("SELECT COUNT(*) FROM version WHERE notaId = :notaId AND origen = :origen AND texto = :texto")
    suspend fun contar(notaId: Long, origen: String, texto: String): Int

    @Query("SELECT COUNT(*) FROM version WHERE uuid = :uuid")
    suspend fun existeUuid(uuid: String): Int

    @Query("SELECT * FROM version WHERE sucio = 1")
    suspend fun sucias(): List<Version>

    @Query("UPDATE version SET sucio = 0 WHERE uuid = :uuid")
    suspend fun limpiar(uuid: String)

    @Query("UPDATE version SET sucio = 1")
    suspend fun ensuciarTodas()

    @Insert
    suspend fun insertar(version: Version): Long
}

@Dao
interface LapidaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertar(lapida: Lapida)

    @Query("SELECT * FROM lapida")
    suspend fun todas(): List<Lapida>

    @Query("DELETE FROM lapida WHERE uuid = :uuid")
    suspend fun borrar(uuid: String)
}

@Dao
interface AjusteDao {
    @Query("SELECT valor FROM ajuste WHERE clave = :clave")
    suspend fun leer(clave: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardar(ajuste: Ajuste)
}
