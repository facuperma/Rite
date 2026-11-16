package io.github.facuperma.rite.red

import org.json.JSONObject

/** Respuesta de POST /mateo/prompt. */
data class ResultadoPrompt(
    val prompt: String,
    val modelo: String,
    val caracteresAntes: Int,
    val caracteresDespues: Int,
    val advertencias: List<String>,
)

fun parsearPrompt(o: JSONObject): ResultadoPrompt {
    val adv = o.optJSONArray("advertencias")
    val lista = if (adv == null) emptyList() else (0 until adv.length()).map { adv.getString(it) }
    return ResultadoPrompt(
        prompt = o.getString("prompt"),
        modelo = o.optString("modelo"),
        caracteresAntes = o.optInt("caracteres_antes"),
        caracteresDespues = o.optInt("caracteres_despues"),
        advertencias = lista,
    )
}

/** Estimación aproximada: ~4 caracteres por token. */
fun estimarTokens(caracteres: Int): Int = if (caracteres <= 0) 0 else (caracteres + 3) / 4

fun textoEstimacion(antes: Int, despues: Int): String = "~${estimarTokens(antes)} → ~${estimarTokens(despues)} tokens (aprox.)"

const val META_PROMPT = "{\"tipo\":\"prompt\"}"

fun esVersionPrompt(metaJson: String): Boolean = runCatching { JSONObject(metaJson).optString("tipo") == "prompt" }.getOrDefault(false)
