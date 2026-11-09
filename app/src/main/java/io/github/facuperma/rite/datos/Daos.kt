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
          AND (:etiqueta IS NULL OR id IN (SELECT notaId FROM nota_etiqueta WHERE etiquetaId = :etiqueta))
          AND (:texto = '' OR titulo LIKE '%' || :texto || '%' OR cuerpo LIKE '%' || :texto || '%')
        ORDER BY fijada DESC, editada DESC
        """,
    )
    fun observar(carpeta: Long?, etiqueta: Long?, texto: String): Flow<List<Nota>>

    @Query("SELECT * FROM nota WHERE borradaEn IS NOT NULL ORDER BY borradaEn DESC")
    fun observarPapelera(): Flow<List<Nota>>

    @Query("SELECT * FROM nota WHERE id = :id")
    suspend fun obtener(id: Long): Nota?

    @Insert
    suspend fun insertar(nota: Nota): Long

    @Update
    suspend fun actualizar(nota: Nota)

    @Query("DELETE FROM nota WHERE id = :id")
    suspend fun borrarDefinitivo(id: Long)

    @Query("DELETE FROM nota WHERE borradaEn IS NOT NULL AND borradaEn < :limite")
    suspend fun vaciarPapeleraAnteriorA(limite: Long): Int

    @Query("DELETE FROM nota WHERE borradaEn IS NOT NULL")
    suspend fun vaciarPapelera(): Int

    @Query("SELECT COUNT(*) FROM nota WHERE borradaEn IS NULL")
    suspend fun contarActivas(): Int
}

@Dao
interface CarpetaDao {
    @Query("SELECT * FROM carpeta ORDER BY orden, nombre")
    fun observar(): Flow<List<Carpeta>>

    @Query("SELECT * FROM carpeta WHERE id = :id")
    suspend fun obtener(id: Long): Carpeta?

    @Insert
    suspend fun insertar(carpeta: Carpeta): Long

    @Update
    suspend fun actualizar(carpeta: Carpeta)

    @Query("DELETE FROM carpeta WHERE id = :id")
    suspend fun borrar(id: Long)

    @Query("SELECT COALESCE(MAX(orden), -1) + 1 FROM carpeta")
    suspend fun siguienteOrden(): Int
}

@Dao
interface EtiquetaDao {
    @Query("SELECT * FROM etiqueta ORDER BY nombre COLLATE NOCASE")
    fun observar(): Flow<List<Etiqueta>>

    @Query(
        """
        SELECT etiqueta.* FROM etiqueta
        JOIN nota_etiqueta ON nota_etiqueta.etiquetaId = etiqueta.id
        WHERE nota_etiqueta.notaId = :notaId ORDER BY etiqueta.nombre COLLATE NOCASE
        """,
    )
    fun observarDe(notaId: Long): Flow<List<Etiqueta>>

    @Query("SELECT * FROM etiqueta WHERE nombre = :nombre COLLATE NOCASE LIMIT 1")
    suspend fun buscar(nombre: String): Etiqueta?

    @Insert
    suspend fun insertar(etiqueta: Etiqueta): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun asignar(relacion: NotaEtiqueta)

    @Query("DELETE FROM nota_etiqueta WHERE notaId = :notaId AND etiquetaId = :etiquetaId")
    suspend fun quitar(notaId: Long, etiquetaId: Long)

    /** Etiquetas que quedaron sin ninguna nota. */
    @Query("DELETE FROM etiqueta WHERE id NOT IN (SELECT DISTINCT etiquetaId FROM nota_etiqueta)")
    suspend fun limpiarHuerfanas()
}

@Dao
interface VersionDao {
    @Query("SELECT * FROM version WHERE notaId = :notaId ORDER BY creada DESC, id DESC")
    fun observarDe(notaId: Long): Flow<List<Version>>

    @Insert
    suspend fun insertar(version: Version): Long
}

@Dao
interface AjusteDao {
    @Query("SELECT valor FROM ajuste WHERE clave = :clave")
    suspend fun leer(clave: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardar(ajuste: Ajuste)
}
