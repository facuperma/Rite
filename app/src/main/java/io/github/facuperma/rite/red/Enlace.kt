package io.github.facuperma.rite.red

/** Datos de vinculación que imprime el puente: rite://IP:PUERTO?c=CODIGO&h=HUELLA */
data class Enlace(val host: String, val puerto: Int, val codigo: String, val huella: String) {
    companion object {
        private val patron = Regex("^rite://([^:/?#\\s]+):(\\d{1,5})\\?(.+)$")

        /** Devuelve null si el texto no es un enlace válido. */
        fun leer(texto: String): Enlace? {
            val m = patron.matchEntire(texto.trim()) ?: return null
            val puerto = m.groupValues[2].toInt()
            if (puerto !in 1..65535) return null
            val params = m.groupValues[3].split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
            }.toMap()
            val codigo = params["c"]?.takeIf { it.matches(Regex("\\d{6}")) } ?: return null
            val huella = params["h"]?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
            return Enlace(m.groupValues[1], puerto, codigo, huella)
        }
    }
}

/** Separa "Título\n\ncuerpo" (como lo devuelve Mateo): la primera línea es el título. */
fun separarTitulo(texto: String): Pair<String, String> {
    val limpio = texto.trim()
    val corte = limpio.indexOf('\n')
    val primera = if (corte < 0) limpio else limpio.substring(0, corte)
    val resto = if (corte < 0) "" else limpio.substring(corte + 1)
    return primera.trimStart('#', ' ', '*').trimEnd('*', ' ').trim() to resto.trim()
}
