package io.github.facuperma.rite

import io.github.facuperma.rite.red.META_PROMPT
import io.github.facuperma.rite.red.esVersionPrompt
import io.github.facuperma.rite.red.estimarTokens
import io.github.facuperma.rite.red.parsearPrompt
import io.github.facuperma.rite.red.textoEstimacion
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptClaudeTest {
    @Test
    fun estimacion_de_tokens_es_caracteres_sobre_cuatro_redondeando_arriba() {
        assertEquals(0, estimarTokens(0))
        assertEquals(1, estimarTokens(1))
        assertEquals(1, estimarTokens(4))
        assertEquals(2, estimarTokens(5))
        assertEquals(176, estimarTokens(704))
        assertEquals("~306 → ~211 tokens (aprox.)", textoEstimacion(1223, 844))
    }

    @Test
    fun parsea_la_respuesta_con_advertencias() {
        val r = parsearPrompt(
            JSONObject(
                """{"prompt":"Objetivo: x","modelo":"qwen3:8b","caracteres_antes":100,"caracteres_despues":12,
                   "advertencias":["Falta «2»","otra"]}""",
            ),
        )
        assertEquals("Objetivo: x", r.prompt)
        assertEquals("qwen3:8b", r.modelo)
        assertEquals(100, r.caracteresAntes)
        assertEquals(12, r.caracteresDespues)
        assertEquals(listOf("Falta «2»", "otra"), r.advertencias)
    }

    @Test
    fun sin_advertencias_da_lista_vacia() {
        val r = parsearPrompt(JSONObject("""{"prompt":"p","caracteres_antes":1,"caracteres_despues":1}"""))
        assertTrue(r.advertencias.isEmpty())
    }

    @Test(expected = org.json.JSONException::class)
    fun sin_prompt_falla() {
        parsearPrompt(JSONObject("{}"))
    }

    @Test
    fun reconoce_las_versiones_de_tipo_prompt() {
        assertTrue(esVersionPrompt(META_PROMPT))
        assertFalse(esVersionPrompt("{}"))
        assertFalse(esVersionPrompt("no es json"))
    }
}
