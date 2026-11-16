package io.github.facuperma.rite.red

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class ErrorPuente(mensaje: String, val codigo: Int = 0) : Exception(mensaje)

data class Vinculo(val host: String, val puerto: Int, val huella: String, val token: String?)
data class EstadoPuente(val mateo: Boolean, val modelo: String)

fun huellaSha256(der: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

/** Confía solo en el certificado cuya huella SHA-256 se guardó al vincular (no en las autoridades del sistema). */
class ConfianzaPorHuella(private val esperada: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("no aplica")

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val visto = chain.firstOrNull()?.let { huellaSha256(it.encoded) }
        if (visto == null || !visto.equals(esperada, ignoreCase = true)) {
            throw CertificateException("La huella del certificado no coincide con la PC vinculada")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

class Cliente(private val v: Vinculo) {
    private val http: OkHttpClient = run {
        val confianza = ConfianzaPorHuella(v.huella)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(confianza), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, confianza)
            // El nombre no se verifica: la identidad de la PC la garantiza la huella fijada.
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS) // Mateo puede tardar en cargar el modelo
            .build()
    }

    private val base get() = "https://${v.host}:${v.puerto}"
    private val json = "application/json; charset=utf-8".toMediaType()

    private suspend fun llamar(ruta: String, cuerpo: JSONObject?, token: String?): JSONObject = withContext(Dispatchers.IO) {
        val pedido = Request.Builder().url(base + ruta).apply {
            if (token != null) header("Authorization", "Bearer $token")
            if (cuerpo != null) post(cuerpo.toString().toRequestBody(json)) else get()
        }.build()
        try {
            http.newCall(pedido).execute().use { r ->
                val texto = r.body?.string().orEmpty()
                val obj = runCatching { JSONObject(texto) }.getOrDefault(JSONObject())
                if (!r.isSuccessful) throw ErrorPuente(obj.optString("error", "Error ${r.code}"), r.code)
                obj
            }
        } catch (e: ErrorPuente) {
            throw e
        } catch (e: javax.net.ssl.SSLException) {
            throw ErrorPuente("No pude verificar la PC: el certificado no es el de la vinculación")
        } catch (e: IOException) {
            throw ErrorPuente("No llego a la PC (${v.host}:${v.puerto}). ¿Está prendida y el puente abierto?")
        }
    }

    suspend fun vincular(codigo: String, nombre: String): String =
        llamar("/vincular", JSONObject().put("codigo", codigo).put("nombre", nombre), null).getString("token")

    suspend fun estado(): EstadoPuente {
        val o = llamar("/estado", null, v.token)
        return EstadoPuente(o.optBoolean("mateo"), o.optString("modelo"))
    }

    suspend fun organizar(texto: String, pista: String = ""): RespuestaOrganizar =
        parsearOrganizar(llamar("/mateo/organizar", JSONObject().put("texto", texto).put("pista", pista), v.token))

    suspend fun armarPrompt(texto: String, pista: String = ""): ResultadoPrompt =
        parsearPrompt(llamar("/mateo/prompt", JSONObject().put("texto", texto).put("pista", pista), v.token))
}

/** Guarda a qué PC está vinculada la app. El token queda en el almacenamiento privado de la app (sin backup). */
class Almacen(contexto: Context) {
    private val prefs = contexto.getSharedPreferences("rite_vinculo", Context.MODE_PRIVATE)

    fun leer(): Vinculo? {
        val host = prefs.getString("host", null) ?: return null
        val huella = prefs.getString("huella", null) ?: return null
        return Vinculo(host, prefs.getInt("puerto", 0), huella, prefs.getString("token", null))
    }

    fun guardar(v: Vinculo) {
        prefs.edit().putString("host", v.host).putInt("puerto", v.puerto).putString("huella", v.huella)
            .putString("token", v.token).apply()
    }

    fun borrar() = prefs.edit().clear().apply()
}
