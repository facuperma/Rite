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
import io.github.facuperma.rite.red.ErrorPuente
import io.github.facuperma.rite.red.EstadoPuente
import io.github.facuperma.rite.red.META_PROMPT
import io.github.facuperma.rite.red.ResultadoPrompt
import io.github.facuperma.rite.red.Vinculo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    /** Vincula con el enlace que imprime el puente. Devuelve el mensaje de error, o null si salió bien. */
    suspend fun vincular(textoEnlace: String): String? {
        val e = Enlace.leer(textoEnlace) ?: return "El enlace no es válido. Tiene que empezar con rite:// y salir del puente."
        return try {
            val sin = Vinculo(e.host, e.puerto, e.huella, null)
            val token = Cliente(sin).vincular(e.codigo, android.os.Build.MODEL ?: "Celular")
            val v = sin.copy(token = token)
            almacen.guardar(v)
            vinculo.value = v
            null
        } catch (ex: ErrorPuente) {
            ex.message
        }
    }

    fun desvincular() {
        almacen.borrar()
        vinculo.value = null
    }

    suspend fun estadoPuente(): Result<EstadoPuente> = runCatching {
        Cliente(vinculo.value?.takeIf { it.token != null } ?: throw ErrorPuente("Sin PC vinculada")).estado()
    }.recoverCatching { throw ErrorPuente(it.message ?: "Error") }

    /**
     * Manda la nota a Mateo. Guarda el texto actual como versión «usuario» y la respuesta como versión «mateo»;
     * la nota no se modifica.
     */
    suspend fun organizarConMateo(id: Long, titulo: String, cuerpo: String): Result<String> = runCatching {
        val v = vinculo.value?.takeIf { it.token != null } ?: throw ErrorPuente("Primero vinculá la PC en Ajustes")
        val texto = (if (titulo.isBlank()) cuerpo else "$titulo\n\n$cuerpo").trim()
        if (texto.isEmpty()) throw ErrorPuente("La nota está vacía")
        repo.registrarVersion(id, Version.USUARIO, texto)
        val salida = Cliente(v).organizar(texto)
        repo.registrarVersion(id, Version.MATEO, salida)
        salida
    }

    /** Pide a Mateo un prompt compacto para Claude Code. No se envía a Claude; el original se guarda como versión. */
    suspend fun armarPromptClaude(id: Long, titulo: String, cuerpo: String): Result<ResultadoPrompt> = runCatching {
        val v = vinculo.value?.takeIf { it.token != null } ?: throw ErrorPuente("Primero vinculá la PC en Ajustes")
        val texto = (if (titulo.isBlank()) cuerpo else "$titulo\n\n$cuerpo").trim()
        if (texto.isEmpty()) throw ErrorPuente("La nota está vacía")
        repo.registrarVersion(id, Version.USUARIO, texto)
        Cliente(v).armarPrompt(texto)
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
        }
    }

    fun etiquetasDe(id: Long): Flow<List<Etiqueta>> = repo.etiquetasDe(id)

    suspend fun cargar(id: Long): Nota? = repo.nota(id)

    fun guardar(n: Nota) = viewModelScope.launch { repo.guardar(n) }
    fun fijar(id: Long, v: Boolean) = viewModelScope.launch { repo.fijar(id, v) }
    fun pintar(id: Long, c: Int) = viewModelScope.launch { repo.pintar(id, c) }
    fun mover(id: Long, carpeta: Long?) = viewModelScope.launch { repo.mover(id, carpeta) }
    fun aPapelera(id: Long) = viewModelScope.launch { repo.aPapelera(id) }
    fun restaurar(id: Long) = viewModelScope.launch { repo.restaurar(id) }
    fun eliminar(id: Long) = viewModelScope.launch { repo.eliminarDefinitivo(id) }
    fun vaciarPapelera() = viewModelScope.launch { repo.vaciarPapelera() }
    fun etiquetar(id: Long, nombre: String) = viewModelScope.launch { repo.etiquetar(id, nombre) }
    fun desetiquetar(id: Long, etiqueta: Long) = viewModelScope.launch { repo.desetiquetar(id, etiqueta) }

    fun crearCarpeta(nombre: String) = viewModelScope.launch { runCatching { repo.crearCarpeta(nombre) } }
    fun renombrarCarpeta(id: Long, nombre: String) = viewModelScope.launch { runCatching { repo.renombrarCarpeta(id, nombre) } }
    fun borrarCarpeta(id: Long) = viewModelScope.launch {
        repo.borrarCarpeta(id)
        if (filtro.value.carpetaId == id) filtro.value = Filtro()
    }
}
