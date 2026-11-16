package io.github.facuperma.rite.red

import org.json.JSONObject

/** Respuesta de POST /mateo/prompt. */
data class ResultadoPrompt(
    val prompt: String,
    val modelo: String,
    val caracteresAntes: Int,
    val caracteresDespues: Int,
    val advertencias: List<String>,
    val resumenIteracion: String = "",
    val preguntasDelOriginal: List<String> = emptyList(),
    val preguntasSugeridas: List<String> = emptyList(),
    val dejadoAfuera: List<String> = emptyList(),
)

/** Respuesta de POST /mateo/organizar: un solo texto (el mejor de los candidatos internos). */
data class RespuestaOrganizar(val texto: String, val resumenIteracion: String)

private fun lista(o: JSONObject, clave: String): List<String> {
    val a = o.optJSONArray(clave) ?: return emptyList()
    return (0 until a.length()).map { a.getString(it) }
}

fun parsearOrganizar(o: JSONObject) = RespuestaOrganizar(o.getString("texto"), o.optString("resumen_iteracion"))

fun parsearPrompt(o: JSONObject): ResultadoPrompt {
    return ResultadoPrompt(
        prompt = o.getString("prompt"),
        modelo = o.optString("modelo"),
        caracteresAntes = o.optInt("caracteres_antes"),
        caracteresDespues = o.optInt("caracteres_despues"),
        advertencias = lista(o, "advertencias"),
        resumenIteracion = o.optString("resumen_iteracion"),
        preguntasDelOriginal = lista(o, "preguntas_del_original"),
        preguntasSugeridas = lista(o, "preguntas_sugeridas"),
        dejadoAfuera = lista(o, "dejado_afuera"),
    )
}

/** Estimación aproximada: ~4 caracteres por token. */
fun estimarTokens(caracteres: Int): Int = if (caracteres <= 0) 0 else (caracteres + 3) / 4

fun textoEstimacion(antes: Int, despues: Int): String = "~${estimarTokens(antes)} → ~${estimarTokens(despues)} tokens (aprox.)"

const val META_PROMPT = "{\"tipo\":\"prompt\"}"

fun esVersionPrompt(metaJson: String): Boolean = runCatching { JSONObject(metaJson).optString("tipo") == "prompt" }.getOrDefault(false)
