package io.github.facuperma.rite

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.github.facuperma.rite.datos.BaseRite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigracionTest {
    @get:Rule
    val ayudante = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BaseRite::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun la_migracion_1_a_2_conserva_los_datos_y_da_ids_estables_unicos() {
        ayudante.createDatabase("migracion", 1).apply {
            execSQL("INSERT INTO carpeta(id, nombre, color, orden) VALUES (1, 'Trabajo', 2, 0)")
            execSQL("INSERT INTO etiqueta(id, nombre) VALUES (1, 'idea')")
            for (i in 1..3) {
                execSQL("INSERT INTO nota(id, carpetaId, titulo, cuerpo, fijada, color, creada, editada, borradaEn) VALUES ($i, 1, 'n$i', 'cuerpo $i', 0, 0, 100, 200, NULL)")
            }
            execSQL("INSERT INTO nota_etiqueta(notaId, etiquetaId) VALUES (1, 1)")
            execSQL("INSERT INTO version(id, notaId, origen, texto, creada, metaJson) VALUES (1, 1, 'mateo', 'v', 150, '{}')")
            execSQL("INSERT INTO version(id, notaId, origen, texto, creada, metaJson) VALUES (2, 1, 'usuario', 'u', 151, '{}')")
            close()
        }
        val db = ayudante.runMigrationsAndValidate("migracion", 2, true, BaseRite.MIGRACION_1_2)

        db.query("SELECT uuid, sucio, actualizado, baseRev FROM nota ORDER BY id").use { c ->
            val uuids = mutableSetOf<String>()
            while (c.moveToNext()) {
                val u = c.getString(0)
                assertTrue("uuid con forma de UUID v4: $u", Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(u))
                assertEquals(1, c.getInt(1))
                assertEquals(200L, c.getLong(2))
                assertTrue(c.isNull(3))
                uuids += u
            }
            assertEquals(3, uuids.size)
        }
        for (t in listOf("carpeta", "etiqueta", "version")) {
            db.query("SELECT COUNT(DISTINCT uuid), COUNT(*) FROM $t").use { c ->
                c.moveToFirst()
                assertEquals(t, c.getInt(1), c.getInt(0))
                assertTrue(c.getInt(0) > 0)
            }
        }
        db.query("SELECT cuerpo FROM nota WHERE id = 2").use { c -> c.moveToFirst(); assertEquals("cuerpo 2", c.getString(0)) }
        db.query("SELECT quitada, sucio FROM nota_etiqueta WHERE notaId = 1").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
            assertEquals(1, c.getInt(1))
        }
    }
}
