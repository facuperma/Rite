package io.github.facuperma.rite.datos

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

fun nuevoUuid(): String = UUID.randomUUID().toString()

/*
 * Todas las filas sincronizables tienen:
 *  - uuid: id estable que viaja al servidor (los ids Long son solo locales);
 *  - actualizado: milisegundos de la ultima modificacion (resuelve conflictos de metadatos);
 *  - sucio: true = hay cambios que todavia no se subieron.
 */

@Entity(tableName = "carpeta", indices = [Index("uuid", unique = true)])
data class Carpeta(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    val color: Int = 0,
    val orden: Int = 0,
    @ColumnInfo(defaultValue = "''") val uuid: String = nuevoUuid(),
    @ColumnInfo(defaultValue = "0") val actualizado: Long = 0,
    @ColumnInfo(defaultValue = "1") val sucio: Boolean = true,
)

@Entity(
    tableName = "nota",
    foreignKeys = [
        ForeignKey(
            entity = Carpeta::class,
            parentColumns = ["id"],
            childColumns = ["carpetaId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("carpetaId"), Index("borradaEn"), Index("uuid", unique = true)],
)
data class Nota(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val carpetaId: Long? = null,
    val titulo: String = "",
    val cuerpo: String = "",
    val fijada: Boolean = false,
    /** Indice en la paleta de colores de la interfaz (0 = sin color). */
    val color: Int = 0,
    val creada: Long = 0,
    val editada: Long = 0,
    /** Null = activa; con valor = en la papelera desde ese momento. */
    val borradaEn: Long? = null,
    @ColumnInfo(defaultValue = "''") val uuid: String = nuevoUuid(),
    @ColumnInfo(defaultValue = "0") val actualizado: Long = 0,
    @ColumnInfo(defaultValue = "1") val sucio: Boolean = true,
    /** Hash del contenido que el servidor tenia en el ultimo sync (se manda al subir para detectar conflictos). */
    val baseRev: String? = null,
)

@Entity(tableName = "etiqueta", indices = [Index("nombre", unique = true), Index("uuid", unique = true)])
data class Etiqueta(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    @ColumnInfo(defaultValue = "''") val uuid: String = nuevoUuid(),
    @ColumnInfo(defaultValue = "0") val actualizado: Long = 0,
    @ColumnInfo(defaultValue = "1") val sucio: Boolean = true,
)

@Entity(
    tableName = "nota_etiqueta",
    primaryKeys = ["notaId", "etiquetaId"],
    foreignKeys = [
        ForeignKey(entity = Nota::class, parentColumns = ["id"], childColumns = ["notaId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Etiqueta::class, parentColumns = ["id"], childColumns = ["etiquetaId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("etiquetaId")],
)
data class NotaEtiqueta(
    val notaId: Long,
    val etiquetaId: Long,
    @ColumnInfo(defaultValue = "0") val actualizado: Long = 0,
    /** true = la etiqueta se le quito a la nota (queda la fila para avisarle al servidor). */
    @ColumnInfo(defaultValue = "0") val quitada: Boolean = false,
    @ColumnInfo(defaultValue = "1") val sucio: Boolean = true,
)

/** Versiones de una nota: lo que escribe el usuario, lo que devuelve Mateo o Claude, o un texto en conflicto. */
@Entity(
    tableName = "version",
    foreignKeys = [
        ForeignKey(entity = Nota::class, parentColumns = ["id"], childColumns = ["notaId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("notaId"), Index("uuid", unique = true)],
)
data class Version(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val notaId: Long,
    val origen: String,
    val texto: String,
    val creada: Long,
    val metaJson: String = "{}",
    @ColumnInfo(defaultValue = "''") val uuid: String = nuevoUuid(),
    @ColumnInfo(defaultValue = "1") val sucio: Boolean = true,
) {
    companion object {
        const val USUARIO = "usuario"
        const val MATEO = "mateo"
        const val CLAUDE = "claude"
        const val CONFLICTO = "conflicto"
    }
}

/** Borrado definitivo pendiente de avisarle al servidor (nota, carpeta o etiqueta). */
@Entity(tableName = "lapida")
data class Lapida(
    @PrimaryKey val uuid: String,
    /** "nota", "carpeta" o "etiqueta". */
    val tipo: String,
    /** Nombre (carpeta/etiqueta) o titulo (nota). */
    val titulo: String = "",
    val cuerpo: String = "",
    val baseRev: String? = null,
    val creada: Long = 0,
    val editada: Long = 0,
    val actualizado: Long,
)

@Entity(tableName = "ajuste")
data class Ajuste(
    @PrimaryKey val clave: String,
    val valor: String,
)
