package io.github.facuperma.rite.datos

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "carpeta")
data class Carpeta(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    val color: Int = 0,
    val orden: Int = 0,
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
    indices = [Index("carpetaId"), Index("borradaEn")],
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
)

@Entity(tableName = "etiqueta", indices = [Index("nombre", unique = true)])
data class Etiqueta(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
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
data class NotaEtiqueta(val notaId: Long, val etiquetaId: Long)

/** Versiones de una nota: lo que escribe el usuario, lo que devuelve Mateo o lo que devuelve Claude. */
@Entity(
    tableName = "version",
    foreignKeys = [
        ForeignKey(entity = Nota::class, parentColumns = ["id"], childColumns = ["notaId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("notaId")],
)
data class Version(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val notaId: Long,
    val origen: String,
    val texto: String,
    val creada: Long,
    val metaJson: String = "{}",
) {
    companion object {
        const val USUARIO = "usuario"
        const val MATEO = "mateo"
        const val CLAUDE = "claude"
    }
}

@Entity(tableName = "ajuste")
data class Ajuste(
    @PrimaryKey val clave: String,
    val valor: String,
)
