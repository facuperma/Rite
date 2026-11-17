package io.github.facuperma.rite.ui

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Eleccion de tema del usuario. Es una preferencia LOCAL del aparato: no se sincroniza con B.O.B. */
enum class ModoTema(val clave: String, val titulo: String) {
    SISTEMA("sistema", "Sistema"),
    CLARO("claro", "Claro"),
    OSCURO("oscuro", "Oscuro"),
    ;

    /** Si hay que dibujar en oscuro, dado lo que dice el sistema. */
    fun esOscuro(sistemaOscuro: Boolean): Boolean = when (this) {
        SISTEMA -> sistemaOscuro
        CLARO -> false
        OSCURO -> true
    }

    companion object {
        /** Cualquier valor desconocido o ausente vuelve al valor por defecto: seguir al sistema. */
        fun desde(clave: String?): ModoTema = entries.firstOrNull { it.clave == clave } ?: SISTEMA
    }
}

/** Guarda la eleccion entre aperturas (SharedPreferences propio, aparte de la vinculacion y de la base que se sincroniza). */
class PreferenciasTema(contexto: Context) {
    private val prefs = contexto.getSharedPreferences("rite_apariencia", Context.MODE_PRIVATE)
    fun leer(): ModoTema = ModoTema.desde(prefs.getString("tema", null))
    fun guardar(modo: ModoTema) = prefs.edit().putString("tema", modo.clave).apply()
}

/** true cuando la app se esta dibujando con el tema oscuro (lo lee la paleta de las notas). */
val LocalOscuro = compositionLocalOf { false }

// ---------------------------------------------------------------- esquemas

val EsquemaClaro: ColorScheme = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    surface = Color(0xFFFAFAFC),
    background = Color(0xFFFAFAFC),
)

/** Oscuro acorde al logo de Rite (cuadrado negro, marco blanco). */
val EsquemaOscuro: ColorScheme = darkColorScheme(
    primary = Color(0xFF8FB0FF),
    onPrimary = Color(0xFF0B0C0E),
    primaryContainer = Color(0xFF26324F),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFA7ACB4),
    onSecondary = Color(0xFF0B0C0E),
    secondaryContainer = Color(0xFF2A2C33),
    onSecondaryContainer = Color(0xFFECEDEF),
    tertiary = Color(0xFFB9C7E6),
    onTertiary = Color(0xFF0B0C0E),
    background = Color(0xFF0B0C0E),
    onBackground = Color(0xFFECEDEF),
    surface = Color(0xFF16171B),
    onSurface = Color(0xFFECEDEF),
    surfaceVariant = Color(0xFF1B1C20),
    onSurfaceVariant = Color(0xFFA7ACB4),
    surfaceTint = Color(0xFF8FB0FF),
    surfaceContainerLowest = Color(0xFF0B0C0E),
    surfaceContainerLow = Color(0xFF121317),
    surfaceContainer = Color(0xFF16171B),
    surfaceContainerHigh = Color(0xFF1B1C20),
    surfaceContainerHighest = Color(0xFF222329),
    inverseSurface = Color(0xFFECEDEF),
    inverseOnSurface = Color(0xFF16171B),
    inversePrimary = Color(0xFF3B5BDB),
    outline = Color(0xFF7A808A), // bordes de campos y botones: >= 3:1 contra el fondo
    outlineVariant = Color(0xFF2A2C33), // divisores y bordes suaves
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

// ---------------------------------------------------------------- notas

/** Sin color + 6 pasteles (modo claro). */
val PaletaNotasClara = listOf(
    Color(0xFFFFFFFF), Color(0xFFFFF3B0), Color(0xFFFFD6D6), Color(0xFFD6F5D6),
    Color(0xFFD6E8FF), Color(0xFFEBD9FF),
)

/** Variantes oscuras de los mismos 7 colores. */
val PaletaNotasOscura = listOf(
    Color(0xFF1B1C20), Color(0xFF4A4421), Color(0xFF4D2A2A), Color(0xFF27402B),
    Color(0xFF26364D), Color(0xFF3B2D4D),
)

val TextoNotaClaro = Color(0xFF1B1B1B)
val TextoNotaOscuro = Color(0xFFECEDEF)

fun paletaNotas(oscuro: Boolean): List<Color> = if (oscuro) PaletaNotasOscura else PaletaNotasClara
fun textoNota(oscuro: Boolean): Color = if (oscuro) TextoNotaOscuro else TextoNotaClaro

fun colorNota(i: Int, oscuro: Boolean): Color {
    val paleta = paletaNotas(oscuro)
    return paleta.getOrElse(i) { paleta[0] }
}

/** Borde de las tarjetas de nota. */
fun bordeNota(oscuro: Boolean): Color = if (oscuro) Color(0xFF2A2C33) else Color(0x22000000)

// ---------------------------------------------------------------- contraste (WCAG)

/** Relacion de contraste WCAG entre dos colores opacos (1 a 21). AA exige 4,5 para texto normal. */
fun contraste(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    val claro = maxOf(la, lb)
    val oscuro = minOf(la, lb)
    return (claro + 0.05) / (oscuro + 0.05)
}
