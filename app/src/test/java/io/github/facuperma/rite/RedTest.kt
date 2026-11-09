package io.github.facuperma.rite

import io.github.facuperma.rite.red.ConfianzaPorHuella
import io.github.facuperma.rite.red.Enlace
import io.github.facuperma.rite.red.huellaSha256
import io.github.facuperma.rite.red.separarTitulo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

class RedTest {
    private val huella = "ab".repeat(32)

    @Test
    fun enlace_valido() {
        val e = Enlace.leer("  rite://192.168.1.35:8787?c=123456&h=${huella.uppercase()}  ")!!
        assertEquals("192.168.1.35", e.host)
        assertEquals(8787, e.puerto)
        assertEquals("123456", e.codigo)
        assertEquals(huella, e.huella)
    }

    @Test
    fun enlaces_invalidos() {
        listOf(
            "", "http://192.168.1.35:8787?c=123456&h=$huella",
            "rite://192.168.1.35?c=123456&h=$huella",
            "rite://192.168.1.35:99999?c=123456&h=$huella",
            "rite://192.168.1.35:8787?c=12345&h=$huella",
            "rite://192.168.1.35:8787?c=123456&h=zz",
            "rite://192.168.1.35:8787?h=$huella",
        ).forEach { assertNull(it, Enlace.leer(it)) }
    }

    @Test
    fun separa_el_titulo_de_la_respuesta_de_mateo() {
        assertEquals("Ideas Rite" to "- uno\n- dos", separarTitulo("# Ideas Rite\n\n- uno\n- dos\n"))
        assertEquals("Solo titulo" to "", separarTitulo("**Solo titulo**"))
        assertEquals("" to "", separarTitulo("   "))
    }

    @Test
    fun huella_sha256_conocida() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            huellaSha256("abc".toByteArray()),
        )
    }

    @Test
    fun la_confianza_rechaza_una_cadena_vacia() {
        try {
            ConfianzaPorHuella(huella).checkServerTrusted(emptyArray<X509Certificate>(), "ECDHE")
            fail("tenia que rechazar la cadena vacia")
        } catch (e: CertificateException) {
            assertNotNull(e.message)
        }
    }
}
