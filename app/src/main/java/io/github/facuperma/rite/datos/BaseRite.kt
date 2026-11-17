package io.github.facuperma.rite.datos

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Carpeta::class, Nota::class, Etiqueta::class, NotaEtiqueta::class, Version::class, Lapida::class, Ajuste::class],
    version = 2,
    exportSchema = true,
)
abstract class BaseRite : RoomDatabase() {
    abstract fun notas(): NotaDao
    abstract fun carpetas(): CarpetaDao
    abstract fun etiquetas(): EtiquetaDao
    abstract fun versiones(): VersionDao
    abstract fun lapidas(): LapidaDao
    abstract fun ajustes(): AjusteDao

    companion object {
        @Volatile private var instancia: BaseRite? = null

        /** Genera un UUID v4 en SQL; cada fila lo evalua por separado. */
        private const val SQL_UUID =
            "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2) || '-' || " +
                "substr('89ab', abs(random()) % 4 + 1, 1) || substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6)))"

        /** v1 -> v2: ids estables (uuid) y columnas de sincronizacion. Todo queda «sucio»: el primer sync sube todo. */
        val MIGRACION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val ahora = System.currentTimeMillis()
                for (t in listOf("carpeta", "nota", "etiqueta", "version")) {
                    db.execSQL("ALTER TABLE $t ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
                    db.execSQL("UPDATE $t SET uuid = $SQL_UUID")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_${t}_uuid ON $t (uuid)")
                    db.execSQL("ALTER TABLE $t ADD COLUMN sucio INTEGER NOT NULL DEFAULT 1")
                }
                for (t in listOf("carpeta", "nota", "etiqueta")) {
                    db.execSQL("ALTER TABLE $t ADD COLUMN actualizado INTEGER NOT NULL DEFAULT 0")
                }
                db.execSQL("UPDATE nota SET actualizado = MAX(editada, creada)")
                db.execSQL("UPDATE carpeta SET actualizado = $ahora")
                db.execSQL("UPDATE etiqueta SET actualizado = $ahora")
                db.execSQL("ALTER TABLE nota ADD COLUMN baseRev TEXT")
                db.execSQL("ALTER TABLE nota_etiqueta ADD COLUMN actualizado INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE nota_etiqueta ADD COLUMN quitada INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE nota_etiqueta ADD COLUMN sucio INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE nota_etiqueta SET actualizado = $ahora")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS lapida (uuid TEXT NOT NULL, tipo TEXT NOT NULL, titulo TEXT NOT NULL, cuerpo TEXT NOT NULL, " +
                        "baseRev TEXT, creada INTEGER NOT NULL, editada INTEGER NOT NULL, actualizado INTEGER NOT NULL, PRIMARY KEY(uuid))",
                )
            }
        }

        fun obtener(contexto: Context): BaseRite =
            instancia ?: synchronized(this) {
                instancia ?: Room.databaseBuilder(contexto.applicationContext, BaseRite::class.java, "rite.db")
                    .addMigrations(MIGRACION_1_2)
                    .build()
                    .also { instancia = it }
            }
    }
}
