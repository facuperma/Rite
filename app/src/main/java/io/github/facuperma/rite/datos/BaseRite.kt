package io.github.facuperma.rite.datos

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Carpeta::class, Nota::class, Etiqueta::class, NotaEtiqueta::class, Version::class, Ajuste::class],
    version = 1,
    exportSchema = true,
)
abstract class BaseRite : RoomDatabase() {
    abstract fun notas(): NotaDao
    abstract fun carpetas(): CarpetaDao
    abstract fun etiquetas(): EtiquetaDao
    abstract fun versiones(): VersionDao
    abstract fun ajustes(): AjusteDao

    companion object {
        @Volatile private var instancia: BaseRite? = null

        fun obtener(contexto: Context): BaseRite =
            instancia ?: synchronized(this) {
                instancia ?: Room.databaseBuilder(contexto.applicationContext, BaseRite::class.java, "rite.db")
                    .build()
                    .also { instancia = it }
            }
    }
}
