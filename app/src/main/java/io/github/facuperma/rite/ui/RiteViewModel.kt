package io.github.facuperma.rite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.facuperma.rite.datos.BaseRite
import io.github.facuperma.rite.datos.Carpeta
import io.github.facuperma.rite.datos.Etiqueta
import io.github.facuperma.rite.datos.Nota
import io.github.facuperma.rite.datos.Repositorio
import io.github.facuperma.rite.datos.Version
import io.github.facuperma.rite.red.Almacen
import io.github.facuperma.rite.red.Cliente
import io.github.facuperma.rite.red.Enlace
import io.github.facuperma.rite.red.ResultadoSync
import io.github.facuperma.rite.red.Sincronizador
import io.github.facuperma.rite.red.ErrorPuente
import io.github.facuperma.rite.red.EstadoPuente
import io.github.facuperma.rite.red.META_PROMPT
import io.github.facuperma.rite.red.RespuestaOrganizar
import io.github.facuperma.rite.red.ResultadoPrompt
import io.github.facuperma.rite.red.Vinculo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Estado de la sincronizacion con la PC (B.O.B.), para el indicador de la lista y los ajustes. */
sealed interface EstadoSync {
    data object SinVincular : EstadoSync
    data object Sincronizando : EstadoSync
    data class AlDia(val ultimo: Long, val resultado: ResultadoSync) : EstadoSync
    /** La PC no responde: la app sigue andando con lo que tiene y reintenta sola. */
    data class SinConexion(val motivo: String) : EstadoSync
    /** El servidor es el puente Python viejo (solo Mateo, sin notas). */
    data object ServidorViejo : EstadoSync
    data class Fallo(val mensaje: String) : EstadoSync
}

/** Un mensaje de la charla con Mateo (solo en memoria: no se guarda nada en el celular). */
data class MensajeCharla(val deMateo: Boolean, val texto: String, val modelo: String = "")

/** Que lista se esta mirando. */
data class Filtro(
    val carpetaId: Long? = null,
    val etiquetaId: Long? = null,
    val papelera: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class RiteViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repositorio(BaseRite.obtener(app))

    val filtro = MutableStateFlow(Filtro())
    val consulta = MutableStateFlow("")
    val editando = MutableStateFlow<Long?>(null)

    val notas: StateFlow<List<Nota>> = combine(filtro, consulta) { f, q -> f to q }
        .flatMapLatest { (f, q) ->
            if (f.papelera) repo.papelera() else repo.notas(f.carpetaId, f.etiquetaId, q)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val carpetas: StateFlow<List<Carpeta>> =
        repo.carpetas().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val etiquetas: StateFlow<List<Etiqueta>> =
        repo.etiquetas().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Vinculación con la PC (puente) ----
    private val almacen = Almacen(app)
    val vinculo = MutableStateFlow(almacen.leer())
    val ajustesAbiertos = MutableStateFlow(false)
    val charlaAbierta = MutableStateFlow(false)

    // ---- Sincronizacion ----
    val estadoSync = MutableStateFlow<EstadoSync>(if (almacen.leer()?.token != null) EstadoSync.SinConexion("Todavía no se sincronizó") else EstadoSync.SinVincular)
    private val candado = Mutex()

    /** Sincroniza con la PC si hay una vinculada. Si ya hay una en curso, no hace nada. Nunca lanza. */
    fun sincronizar() {
        viewModelScope.launch { sincronizarYEsperar() }
    }

    suspend fun sincronizarYEsperar() {
        val v = vinculo.value?.takeIf { it.token != null }
        if (v == null) {
            estadoSync.value = EstadoSync.SinVincular
            return
        }
        if (!candado.tryLock()) return
        try {
            estadoSync.value = EstadoSync.Sincronizando
            val r = Sincronizador(BaseRite.obtener(getApplication()), Cliente(v), android.os.Build.MODEL ?: "Celular").sincronizar()
            estadoSync.value = if (!r.soportado) EstadoSync.ServidorViejo else EstadoSync.AlDia(System.currentTimeMillis(), r)
        } catch (e: ErrorPuente) {
            estadoSync.value = when {
                e.codigo == 401 -> EstadoSync.Fallo("La PC no reconoce este celular: vinculalo de nuevo en Ajustes.")
                e.codigo != 0 -> EstadoSync.Fallo(e.message ?: "Error ${e.codigo}")
                else -> EstadoSync.SinConexion(e.message ?: "Sin conexión")
            }
        } catch (e: Exception) {
            estadoSync.value = EstadoSync.Fallo(e.message ?: "Error al sincronizar")
        } finally {
            candado.unlock()
        }
    }

    // ---- Charla con Mateo (B.O.B.) ----
    val charla = MutableStateFlow<List<MensajeCharla>>(emptyList())
    val charlando = MutableStateFlow(false)

    /** Manda un mensaje a Mateo. La respuesta se muestra pero NO se guarda sola: el usuario decide si la copia o la guarda como nota. */
    suspend fun charlar(texto: String): String? {
        val v = vinculo.value?.takeIf { it.token != null } ?: return "Primero vinculá la PC en Ajustes"
        val limpio = texto.trim()
        if (limpio.isEmpty()) return null
        charla.value = charla.value + MensajeCharla(false, limpio)
        charlando.value = true
        return try {
            val r = Cliente(v).charlar(limpio)
            charla.value = charla.value + MensajeCharla(true, r.texto, r.modelo)
            null
        } catch (e: ErrorPuente) {
            e.message
        } finally {
            charlando.value = false
        }
    }

    suspend fun repasarConArturo(): String? {
        val v = vinculo.value?.takeIf { it.token != null } ?: return "Primero vinculá la PC en Ajustes"
        charlando.value = true
        return try {
            val r = Cliente(v).repasarConArturo()
            charla.value = charla.value + MensajeCharla(true, r.texto, r.modelo)
            null
        } catch (e: ErrorPuente) {
            e.message
        } finally {
            charlando.value = false
        }
    }

    fun limpiarCharla() {
        charla.value = emptyList()
    }

    /** Crea una nota con el texto (acción explícita del usuario: «Guardar como nota»). */
    fun notaDesdeTexto(texto: String) {
        viewModelScope.launch {
            val (t, c) = io.github.facuperma.rite.red.separarTitulo(texto)
            repo.crearNota(carpetaId = filtro.value.carpetaId, titulo = t, cuerpo = c)
        }
    }

    /** Abre Claude Code en la PC con el prompt escrito (no lo envia). Devuelve el aviso o el error. */
    suspend fun enviarAClaudeCode(prompt: String): String {
        val v = vinculo.value?.takeIf { it.token != null } ?: return "Primero vinculá la PC en Ajustes"
        return try {
            Cliente(v).enviarAClaudeCode(prompt)
        } catch (e: ErrorPuente) {
            e.message ?: "No se pudo"
        }
    }

    /** Vincula con el enlace que imprime el puente. Devuelve el mensaje de error, o null si salió bien. */
    suspend fun vincular(textoEnlace: String): String? {
        val e = Enlace.leer(textoEnlace) ?: return "El enlace no es válido. Tiene que empezar con rite:// y salir del puente."
        return try {
            val sin = Vinculo(e.host, e.puerto, e.huella, null)
            val token = Cliente(sin).vincular(e.codigo, android.os.Build.MODEL ?: "Celular")
            val v = sin.copy(token = token)
            almacen.guardar(v)
            vinculo.value = v
            sincronizar()
            null
        } catch (ex: ErrorPuente) {
            ex.message
        }
    }

    fun desvincular() {
        almacen.borrar()
        vinculo.value = null
        estadoSync.value = EstadoSync.SinVincular
    }

    suspend fun estadoPuente(): Result<EstadoPuente> = runCatching {
        Cliente(vinculo.value?.takeIf { it.token != null } ?: throw ErrorPuente("Sin PC vinculada")).estado()
    }.recoverCatching { throw ErrorPuente(it.message ?: "Error") }

    private fun textoDe(titulo: String, cuerpo: String) = (if (titulo.isBlank()) cuerpo else "$titulo\n\n$cuerpo").trim()

    private fun vinculoActivo(): Vinculo =
        vinculo.value?.takeIf { it.token != null } ?: throw ErrorPuente("Primero vinculá la PC en Ajustes")

    /** Pide UNA versión organizada (el servidor itera internamente). No guarda nada: eso lo decide el usuario. */
    suspend fun organizarConMateo(titulo: String, cuerpo: String, pista: String = ""): Result<RespuestaOrganizar> = runCatching {
        val v = vinculoActivo()
        val texto = textoDe(titulo, cuerpo)
        if (texto.isEmpty()) throw ErrorPuente("La nota está vacía")
        Cliente(v).organizar(texto, pista)
    }

    /** Pide UN prompt compacto para Claude Code. No se guarda ni se envía a Claude. */
    suspend fun armarPromptClaude(titulo: String, cuerpo: String, pista: String = ""): Result<ResultadoPrompt> = runCatching {
        val v = vinculoActivo()
        val texto = textoDe(titulo, cuerpo)
        if (texto.isEmpty()) throw ErrorPuente("La nota está vacía")
        Cliente(v).armarPrompt(texto, pista, titulo.trim())
    }

    /** Guarda la respuesta de Mateo como versión, solo cuando el usuario lo pide (sin duplicados). */
    suspend fun guardarMateo(id: Long, texto: String) {
        if (texto.isNotBlank()) repo.registrarVersion(id, Version.MATEO, texto.trim())
    }

    suspend fun guardarPrompt(id: Long, texto: String) {
        if (texto.isNotBlank()) repo.registrarVersion(id, Version.MATEO, texto.trim(), META_PROMPT)
    }

    fun versiones(id: Long): Flow<List<Version>> = repo.versiones(id)

    /** Antes de reemplazar el contenido por una versión, el texto actual queda guardado. */
    suspend fun guardarSnapshot(id: Long, titulo: String, cuerpo: String) {
        val texto = (if (titulo.isBlank()) cuerpo else "$titulo\n\n$cuerpo").trim()
        if (texto.isNotEmpty()) repo.registrarVersion(id, Version.USUARIO, texto)
    }

    init {
        viewModelScope.launch { repo.limpiarPapeleraVieja() }
        // Sincroniza al abrir y despues cada 5 minutos (cada minuto si la PC no estaba): sin conexion la app sigue andando igual.
        viewModelScope.launch {
            delay(1_000)
            while (true) {
                sincronizarYEsperar()
                delay(if (estadoSync.value is EstadoSync.SinConexion) 60_000 else 300_000)
            }
        }
    }

    fun mirar(f: Filtro) {
        filtro.value = f
        consulta.value = ""
    }

    fun nuevaNota() {
        viewModelScope.launch { editando.value = repo.crearNota(carpetaId = filtro.value.carpetaId) }
    }

    fun abrir(id: Long) {
        editando.value = id
    }

    /** Cierra el editor; si la nota quedo vacia (sin texto ni etiquetas) no se conserva. */
    fun cerrarEditor() {
        val id = editando.value ?: return
        editando.value = null
        viewModelScope.launch {
            val n = repo.nota(id) ?: return@launch
            val sinEtiquetas = repo.etiquetasDe(id).first().isEmpty()
            if (n.titulo.isBlank() && n.cuerpo.isBlank() && sinEtiquetas) repo.eliminarDefinitivo(id)
            sincronizarYEsperar() // al cerrar una nota, lo escrito sube enseguida
        }
    }

    fun etiquetasDe(id: Long): Flow<List<Etiqueta>> = repo.etiquetasDe(id)

    suspend fun cargar(id: Long): Nota? = repo.nota(id)

    fun guardar(n: Nota) = viewModelScope.launch { repo.guardar(n) }
    fun fijar(id: Long, v: Boolean) = viewModelScope.launch { repo.fijar(id, v) }
    fun pintar(id: Long, c: Int) = viewModelScope.launch { repo.pintar(id, c) }
    fun mover(id: Long, carpeta: Long?) = viewModelScope.launch { repo.mover(id, carpeta) }
    // Papelera y borrados suben enseguida (no esperan al proximo ciclo).
    fun aPapelera(id: Long) = viewModelScope.launch { repo.aPapelera(id); sincronizarYEsperar() }
    fun restaurar(id: Long) = viewModelScope.launch { repo.restaurar(id); sincronizarYEsperar() }
    fun eliminar(id: Long) = viewModelScope.launch { repo.eliminarDefinitivo(id); sincronizarYEsperar() }
    fun vaciarPapelera() = viewModelScope.launch { repo.vaciarPapelera(); sincronizarYEsperar() }
    fun etiquetar(id: Long, nombre: String) = viewModelScope.launch { repo.etiquetar(id, nombre) }
    fun desetiquetar(id: Long, etiqueta: Long) = viewModelScope.launch { repo.desetiquetar(id, etiqueta) }

    fun crearCarpeta(nombre: String) = viewModelScope.launch { runCatching { repo.crearCarpeta(nombre) } }
    fun renombrarCarpeta(id: Long, nombre: String) = viewModelScope.launch { runCatching { repo.renombrarCarpeta(id, nombre) } }
    fun borrarCarpeta(id: Long) = viewModelScope.launch {
        repo.borrarCarpeta(id)
        if (filtro.value.carpetaId == id) filtro.value = Filtro()
    }
}
