import hashlib
import json
import socket
import ssl
import sys
import tempfile
import unittest
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from rite_puente import certificado
from rite_puente.config import Proyectos, carpeta_datos
from rite_puente import armador, fidelidad, puntuacion
from rite_puente.mateo import Mateo, MateoError
from rite_puente.servidor import Puente, ip_permitida
from rite_puente.vinculos import MAX_INTENTOS, VIGENCIA_CODIGO, Vinculos


class MateoFalso:
    modelo = "falso:1b"

    def __init__(self):
        self.recibido = []

    def disponible(self):
        return True

    def organizar(self, texto, pista=""):
        if texto == "falla":
            raise MateoError("Mateo no responde")
        self.recibido.append(texto)
        return {"texto": "# Titulo\n- " + texto.strip(), "modelo": self.modelo, "resumen_iteracion": "1 version"}

    salida_prompt = "Objetivo: x"

    def armar_prompt(self, texto, pista=""):
        if texto == "falla":
            raise MateoError("Mateo no responde")
        return {"prompt": self.salida_prompt, "modelo": self.modelo, "caracteres_antes": len(texto.strip()), "caracteres_despues": len(self.salida_prompt), "advertencias": [], "intentos": 1, "resumen_iteracion": "r", "preguntas_del_original": [], "preguntas_sugeridas": ["¿Y si falla?"], "pista": pista}


def pedir(base, ruta, cuerpo=None, token=None, ctx=None):
    datos = None if cuerpo is None else json.dumps(cuerpo).encode()
    cab = {"Content-Type": "application/json"}
    if token:
        cab["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + ruta, data=datos, headers=cab, method="POST" if datos is not None else "GET")
    try:
        with urllib.request.urlopen(req, timeout=10, context=ctx) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


ORIGINAL = (
    "Quiero que en `ui/Editor.kt` haya un boton nuevo junto al de Mateo. Debe llamar a POST /mateo/prompt "
    "y usar el campo caracteres_antes. El tope es 8000 caracteres y la estimacion 1,5 veces. "
    "Hay que revisar servidor/rite_puente/mateo.py tambien, con mucho cuidado y detalle porque es importante."
)


class FidelidadTest(unittest.TestCase):
    def test_literales(self):
        l = fidelidad.literales(ORIGINAL)
        for x in ("ui/Editor.kt", "caracteres_antes", "8000", "1,5", "servidor/rite_puente/mateo.py", "/mateo/prompt"):
            self.assertIn(x, l)

    def test_faltantes(self):
        bueno = "Objetivo: boton en ui/Editor.kt; POST /mateo/prompt; caracteres_antes; 8000; 1,5; servidor/rite_puente/mateo.py"
        self.assertEqual(fidelidad.faltantes(ORIGINAL, bueno), [])
        malo = "Objetivo: boton; 8000"
        f = fidelidad.faltantes(ORIGINAL, malo)
        self.assertIn("ui/Editor.kt", f)
        self.assertIn("caracteres_antes", f)
        self.assertNotIn("8000", f)



def mateo_guionado(*salidas):
    """Mateo con respuestas preparadas; guarda los mensajes que recibe."""
    class M(Mateo):
        def __init__(self):
            super().__init__()
            self.pedidos = []
            self._s = list(salidas)

        def _sugerir(self, texto, modelo):
            return "- ¿Que pasa si el CSV queda vacio?\n1. ¿Conviene separar por comas?\nno es pregunta\n¿Y con mas de 500 filas?\n¿Una cuarta pregunta?"

        def _llamar(self, mensajes, modelo, opciones):
            self.pedidos.append((mensajes, opciones))
            return self._s.pop(0) if len(self._s) > 1 else self._s[0]

    return M()


NOTA = (
    "Che, necesito que el boton de exportar en `ui/Lista.kt` baje un CSV con las notas, usando `ExportadorCsv`, "
    "con maximo 500 filas, guardando en la carpeta Descargas del celular. Hoy no hace nada y ya me canse de esperar. "
    "No quiero librerias nuevas. No se si conviene separar por comas o por punto y coma. Gracias, saludos!"
)


class ArmadorTest(unittest.TestCase):
    def test_largo_nunca_supera_al_original_aunque_el_modelo_se_pase(self):
        m = mateo_guionado("Objetivo: " + "relleno " * 200)
        r = m.armar_prompt(NOTA)
        self.assertLessEqual(r["caracteres_despues"], r["caracteres_antes"])
        self.assertEqual(r["caracteres_despues"], len(r["prompt"]))
        self.assertEqual(len(m.pedidos), 3)  # reintento hasta 3 veces
        self.assertEqual(r["intentos"], 3)
        self.assertEqual(fidelidad.faltantes(NOTA, r["prompt"]), [])

    def test_buen_resultado_se_usa_con_dudas_tal_cual_y_sin_advertencia(self):
        buena = "Objetivo: boton exportar de `ui/Lista.kt` baja CSV.\nRequisitos:\n- usar `ExportadorCsv`\n- max 500 filas\n- Descargas"
        m = mateo_guionado(buena)
        r = m.armar_prompt(NOTA)
        self.assertEqual(r["intentos"], 1)
        self.assertEqual(r["advertencias"], [])
        self.assertIn("Preguntas abiertas:\n- No se si conviene separar por comas o por punto y coma.", r["prompt"])
        self.assertLess(r["caracteres_despues"], 0.8 * r["caracteres_antes"])
        # el modelo no recibe las dudas, para que no las reescriba
        self.assertNotIn("punto y coma", m.pedidos[0][0][-1]["content"])

    def test_sin_dudas_en_el_original_no_hay_seccion_aunque_el_modelo_la_invente(self):
        nota = "Agregar boton de compartir en `ui/Editor.kt` con el intent ACTION_SEND, usando el texto de la nota actual, sin librerias nuevas ni cambios de diseño."
        m = mateo_guionado("Objetivo: compartir en `ui/Editor.kt` con ACTION_SEND.\nPreguntas abiertas:\n- ¿Y si no hay red?\n- ¿Cual icono?")
        r = m.armar_prompt(nota)
        self.assertNotIn("Preguntas", r["prompt"])
        self.assertNotIn("red", r["prompt"])

    def test_reintento_con_la_lista_exacta_de_literales_que_faltaron(self):
        sin = "Objetivo: exportar CSV.\nRequisitos:\n- max 500 filas"
        con = "Objetivo: exportar CSV desde `ui/Lista.kt` con `ExportadorCsv`.\n- 500 filas"
        m = mateo_guionado(sin, con)
        r = m.armar_prompt(NOTA)
        self.assertEqual(r["intentos"], 2)
        segundo = m.pedidos[1][0][-1]["content"]
        self.assertIn("Conserva tal cual:", segundo)
        self.assertIn("ui/Lista.kt", segundo)
        self.assertIn("ExportadorCsv", segundo)
        self.assertEqual(fidelidad.faltantes(NOTA, r["prompt"]), [])

    def test_si_siguen_faltando_literales_van_a_datos(self):
        m = mateo_guionado("Objetivo: exportar CSV.")
        r = m.armar_prompt(NOTA)
        self.assertIn("Datos:", r["prompt"])
        self.assertIn("ui/Lista.kt", r["prompt"])
        self.assertIn("ExportadorCsv", r["prompt"])
        self.assertEqual(fidelidad.faltantes(NOTA, r["prompt"]), [])
        self.assertFalse(any("Falta" in a for a in r["advertencias"]))

    def test_invenciones_se_reintentan_y_se_sacan(self):
        inv = "Objetivo: exportar con Retrofit a `ui/Lista.kt`.\n- usar `ExportadorCsv`\n- 500 filas\n- Hilt"
        m = mateo_guionado(inv)
        r = m.armar_prompt(NOTA)
        self.assertNotIn("Retrofit", r["prompt"])
        self.assertNotIn("Hilt", r["prompt"])
        self.assertIn("NO uses estos terminos", m.pedidos[1][0][-1]["content"])

    def test_nota_corta_ya_densa_se_deja_compactada_con_mensaje_neutro(self):
        corta = "Que Rite abra directo en la ultima nota editada."
        m = mateo_guionado("Objetivo: Rite abre directo en la ultima nota editada, siempre y sin demora alguna.")
        r = m.armar_prompt(corta)
        self.assertEqual(r["prompt"], corta)
        self.assertEqual(r["advertencias"], ["Tu nota ya era corta; la dejé compactada, sin cambios."])

    def test_presupuesto_y_num_predict_en_el_pedido(self):
        m = mateo_guionado("Objetivo: x `ui/Lista.kt` `ExportadorCsv` 500")
        m.armar_prompt(NOTA)
        mensajes, ops = m.pedidos[0]
        self.assertIn("Maximo", mensajes[-1]["content"])
        self.assertLess(ops["num_predict"], len(NOTA))
        self.assertLessEqual(ops["temperature"], 0.1)

    def test_errores_de_ollama_suben(self):
        class Roto(Mateo):
            def _llamar(self, *a):
                raise MateoError("no")

        with self.assertRaises(MateoError):
            Roto().armar_prompt(NOTA)
        with self.assertRaises(MateoError):
            Roto().armar_prompt("   ")


class IteracionTest(unittest.TestCase):
    def test_preguntas_sugeridas_aparte_del_prompt(self):
        buena = "Objetivo: boton exportar de `ui/Lista.kt` baja CSV.\nRequisitos:\n- usar `ExportadorCsv`\n- max 500 filas"
        r = mateo_guionado(buena).armar_prompt(NOTA)
        self.assertEqual(
            r["preguntas_sugeridas"],
            ["¿Que pasa si el CSV queda vacio?", "¿Conviene separar por comas?", "¿Y con mas de 500 filas?"],
        )
        for q in r["preguntas_sugeridas"]:
            self.assertNotIn(q, r["prompt"])
        self.assertEqual(r["preguntas_del_original"], ["No se si conviene separar por comas o por punto y coma."])
        self.assertLessEqual(r["caracteres_despues"], r["caracteres_antes"])

    def test_si_fallan_las_sugerencias_el_prompt_sale_igual(self):
        m = mateo_guionado("Objetivo: boton exportar de `ui/Lista.kt` con `ExportadorCsv` y 500 filas")
        m._sugerir = lambda t, mo: (_ for _ in ()).throw(MateoError("no"))
        r = m.armar_prompt(NOTA)
        self.assertEqual(r["preguntas_sugeridas"], [])
        self.assertIn("Objetivo", r["prompt"])

    def test_devuelve_solo_el_mejor_candidato_y_explica(self):
        malo = "Objetivo: exportar con Retrofit."
        medio = "Objetivo: exportar CSV.\n- 500 filas"
        bueno = "Objetivo: exportar CSV de `ui/Lista.kt` con `ExportadorCsv`.\n- 500 filas"
        m = mateo_guionado(malo, medio, bueno)
        r = m.armar_prompt(NOTA)
        self.assertEqual(r["intentos"], 3)
        self.assertIn("`ExportadorCsv`", r["prompt"])
        self.assertNotIn("Retrofit", r["prompt"])
        self.assertNotIn("Datos:", r["prompt"])
        self.assertIn("3 versiones", r["resumen_iteracion"].replace("probó", "probó"))
        self.assertIn("3ª", r["resumen_iteracion"])

    def test_para_de_iterar_cuando_un_candidato_pasa(self):
        m = mateo_guionado("Objetivo: exportar CSV de `ui/Lista.kt` con `ExportadorCsv`, 500 filas")
        r = m.armar_prompt(NOTA)
        self.assertEqual(r["intentos"], 1)
        self.assertEqual(len(m.pedidos), 1)

    def test_pista_de_rehacer_llega_al_modelo(self):
        m = mateo_guionado("Objetivo: exportar CSV de `ui/Lista.kt` con `ExportadorCsv`, 500 filas")
        m.armar_prompt(NOTA, "mas corto y sin Descargas")
        self.assertIn("mas corto y sin Descargas", m.pedidos[0][0][-1]["content"])

    def test_organizar_elige_el_mejor_y_devuelve_uno(self):
        nota = "comprar leche y pan en `Super.kt`, 2 litros"
        m = mateo_guionado("Titulo\n- leche con Retrofit", "Compras\n- leche 2 litros\n- pan\n- `Super.kt`")
        r = m.organizar(nota)
        self.assertEqual(set(r), {"texto", "modelo", "resumen_iteracion"})
        self.assertIn("`Super.kt`", r["texto"])
        self.assertNotIn("Retrofit", r["texto"])
        self.assertLessEqual(len(m.pedidos), 3)

    def test_limpiar_preguntas(self):
        q = armador.limpiar_preguntas("1) ¿Una?\n- ¿Pregunta valida numero uno?\nafirmacion\n¿Pregunta valida numero uno?\n¿" + "x" * 200 + "?", [])
        self.assertEqual(q, ["¿Pregunta valida numero uno?"])
        self.assertEqual(armador.limpiar_preguntas(["¿Esta ya estaba en la nota?"], ["¿esta ya estaba en la nota?"]), [])


class PuntuacionTest(unittest.TestCase):
    def test_orden_de_criterios(self):
        o = "Hacer algo en `ui/A.kt` con 500 filas, con bastante texto de relleno para que sea largo y no corto"
        sin_inv = puntuacion.puntuar(o, "Objetivo: `ui/A.kt` y 500", 60)
        inv = puntuacion.puntuar(o, "Objetivo: `ui/A.kt` y 500 con Hilt", 60)
        largo = puntuacion.puntuar(o, "Objetivo: `ui/A.kt` y 500 " + "x" * 80, 60)
        falta = puntuacion.puntuar(o, "Objetivo: algo", 60)
        self.assertTrue(sin_inv.pasa)
        self.assertEqual(puntuacion.elegir([inv, largo, falta, sin_inv]), 3)
        self.assertEqual(puntuacion.elegir([largo, falta]), 1)  # largo pesa mas que un literal faltante
        self.assertEqual(puntuacion.elegir([inv, largo]), 1)    # invencion es lo peor
        self.assertTrue(any("Conserva tal cual" in x for x in falta.pistas()))
        self.assertTrue(any("NO uses" in x for x in inv.pistas()))
        self.assertTrue(any("demasiado largo" in x for x in largo.pistas()))

    def test_desempate_por_largo(self):
        o = "Hacer algo con 500 filas y mucho relleno aqui y alla para tener largo suficiente"
        a = puntuacion.puntuar(o, "Objetivo: 500 filas", 80)
        b = puntuacion.puntuar(o, "Objetivo: 500 filas ya", 80)
        self.assertEqual(puntuacion.elegir([b, a]), 1)


class DudasTest(unittest.TestCase):
    def test_extrae_frases_con_duda_tal_cual_maximo_3(self):
        t = "Hacer X. ¿Va en Room? No se si conviene Y. Capaz Z. Tal vez W. Listo."
        resto, d = fidelidad.separar_dudas(t)
        self.assertEqual(d, ["¿Va en Room?", "No se si conviene Y.", "Capaz Z."])
        self.assertIn("Hacer X.", resto)
        self.assertNotIn("Room", resto)

    def test_sin_dudas(self):
        self.assertEqual(fidelidad.separar_dudas("Hacer X. Hacer Y.")[1], [])

    def test_numeros_y_versiones(self):
        self.assertEqual(fidelidad.literales("de 8.9 a 8.11.1 y Room (que esta en la 2)"), ["8.9", "8.11.1", "2"])
        self.assertEqual(fidelidad.faltantes("Room 2 y 8.11.1", "Room 22 y 8.11"), ["2", "8.11.1"])
        self.assertEqual(fidelidad.faltantes("tarda 400 ms", "tarda 400ms"), [])


class PuenteTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.datos = carpeta_datos(self.tmp.name)
        self.ahora = 1000.0
        self.vinculos = Vinculos(self.datos, reloj=lambda: self.ahora)
        self.mateo = MateoFalso()
        self.puente = Puente(("127.0.0.1", 0), self.vinculos, self.mateo, Proyectos(self.datos))
        self.puente.en_segundo_plano()
        self.base = f"http://127.0.0.1:{self.puente.puerto}"

    def tearDown(self):
        self.puente.shutdown()
        self.puente.server_close()
        self.vinculos.db.close()
        self.tmp.cleanup()

    def vincular(self):
        codigo = self.vinculos.nuevo_codigo()
        estado, r = pedir(self.base, "/vincular", {"codigo": codigo, "nombre": "S23"})
        self.assertEqual(estado, 200)
        return r["token"]

    def test_vincular_y_organizar(self):
        token = self.vincular()
        estado, r = pedir(self.base, "/mateo/organizar", {"texto": "comprar leche"}, token)
        self.assertEqual(estado, 200)
        self.assertEqual(r["texto"], "# Titulo\n- comprar leche")
        self.assertEqual(r["modelo"], "falso:1b")

    def test_prompt_endpoint(self):
        token = self.vincular()
        self.mateo.salida_prompt = "Objetivo: ver ui/Editor.kt"
        orig = "Quiero que mires ui/Editor.kt con mucho detalle y cuidado porque es importante para todo"
        estado, r = pedir(self.base, "/mateo/prompt", {"texto": orig}, token)
        self.assertEqual(estado, 200)
        self.assertEqual(r["prompt"], "Objetivo: ver ui/Editor.kt")
        self.assertEqual((r["caracteres_antes"], r["caracteres_despues"]), (len(orig), 26))
        self.assertEqual(r["advertencias"], [])
        self.assertEqual(r["modelo"], "falso:1b")
        self.assertEqual(r["preguntas_sugeridas"], ["¿Y si falla?"])
        self.assertEqual(r["preguntas_del_original"], [])
        self.assertEqual(pedir(self.base, "/mateo/prompt", {"texto": orig, "pista": "mas corto"}, token)[1]["pista"], "mas corto")
        self.assertEqual(pedir(self.base, "/mateo/prompt", {"texto": orig, "pista": 5}, token)[0], 400)

    def test_prompt_errores(self):
        token = self.vincular()
        self.assertEqual(pedir(self.base, "/mateo/prompt", {"texto": "x"})[0], 401)
        self.assertEqual(pedir(self.base, "/mateo/prompt", {"texto": "falla"}, token)[0], 502)
        self.assertEqual(pedir(self.base, "/mateo/prompt", {"otro": 1}, token)[0], 400)

    def test_sin_token_o_token_falso_da_401(self):
        self.assertEqual(pedir(self.base, "/mateo/organizar", {"texto": "x"})[0], 401)
        self.assertEqual(pedir(self.base, "/mateo/organizar", {"texto": "x"}, "inventado")[0], 401)
        self.assertEqual(pedir(self.base, "/estado")[0], 401)
        self.assertEqual(self.mateo.recibido, [])

    def test_codigo_de_un_solo_uso(self):
        codigo = self.vinculos.nuevo_codigo()
        self.assertEqual(pedir(self.base, "/vincular", {"codigo": codigo})[0], 200)
        self.assertEqual(pedir(self.base, "/vincular", {"codigo": codigo})[0], 403)

    def test_codigo_vence(self):
        codigo = self.vinculos.nuevo_codigo()
        self.ahora += VIGENCIA_CODIGO + 1
        self.assertEqual(pedir(self.base, "/vincular", {"codigo": codigo})[0], 403)

    def test_demasiados_intentos_invalidan_el_codigo(self):
        codigo = self.vinculos.nuevo_codigo()
        malo = "000000" if codigo != "000000" else "111111"
        for _ in range(MAX_INTENTOS):
            self.assertEqual(pedir(self.base, "/vincular", {"codigo": malo})[0], 403)
        self.assertEqual(pedir(self.base, "/vincular", {"codigo": codigo})[0], 403, "ni el correcto sirve ya")

    def test_codigo_nuevo_invalida_el_anterior(self):
        viejo = self.vinculos.nuevo_codigo()
        nuevo = self.vinculos.nuevo_codigo()
        if viejo != nuevo:
            self.assertEqual(pedir(self.base, "/vincular", {"codigo": viejo})[0], 403)

    def test_revocar_dispositivo_corta_el_acceso(self):
        token = self.vincular()
        (d,) = self.vinculos.listar()
        self.assertTrue(self.vinculos.revocar(d["id"]))
        self.assertEqual(pedir(self.base, "/mateo/organizar", {"texto": "x"}, token)[0], 401)

    def test_token_se_guarda_con_hash(self):
        token = self.vincular()
        guardado = self.vinculos.db.execute("SELECT token_hash FROM dispositivo").fetchone()[0]
        self.assertNotEqual(guardado, token)
        self.assertEqual(guardado, hashlib.sha256(token.encode()).hexdigest())

    def test_errores_de_mateo_y_entradas_malas(self):
        token = self.vincular()
        self.assertEqual(pedir(self.base, "/mateo/organizar", {"texto": "falla"}, token)[0], 502)
        self.assertEqual(pedir(self.base, "/mateo/organizar", {"otro": 1}, token)[0], 400)
        self.assertEqual(pedir(self.base, "/nada", {"a": 1}, token)[0], 404)

    def test_estado_lista_proyectos_con_productor_por_defecto(self):
        token = self.vincular()
        estado, r = pedir(self.base, "/estado", token=token)
        self.assertEqual(estado, 200)
        self.assertTrue(r["mateo"])
        self.assertEqual([(p["nombre"], p["predeterminado"]) for p in r["proyectos"]], [("Productor", True)])

    def test_ip_permitida(self):
        for ok in ["127.0.0.1", "192.168.1.35", "10.0.2.2", "100.101.102.103", "::1", "::ffff:192.168.1.5"]:
            self.assertTrue(ip_permitida(ok), ok)
        for mal in ["8.8.8.8", "100.128.0.1", "2800:3f0::1", "basura"]:
            self.assertFalse(ip_permitida(mal), mal)


class ProyectosTest(unittest.TestCase):
    def test_registrar_otro_proyecto_y_no_quitar_el_predeterminado(self):
        with tempfile.TemporaryDirectory() as t, tempfile.TemporaryDirectory() as carpeta:
            p = Proyectos(carpeta_datos(t))
            p.agregar("TVon", carpeta)
            self.assertEqual({x["nombre"] for x in p.listar()}, {"Productor", "TVon"})
            self.assertEqual(p.obtener("tvon")["nombre"], "TVon")
            self.assertEqual(p.obtener()["nombre"], "Productor")
            with self.assertRaises(ValueError):
                p.quitar("Productor")
            with self.assertRaises(ValueError):
                p.agregar("Roto", str(Path(carpeta) / "no_existe"))
            p.agregar("TVon", carpeta, predeterminado=True)
            self.assertEqual(p.obtener()["nombre"], "TVon")
            p.quitar("Productor")
            self.assertEqual([x["nombre"] for x in p.listar()], ["TVon"])


class TlsTest(unittest.TestCase):
    def test_https_con_huella_fijada(self):
        with tempfile.TemporaryDirectory() as t:
            datos = carpeta_datos(t)
            cert, clave, huella = certificado.asegurar(datos)
            self.assertEqual(certificado.asegurar(datos)[2], huella, "el certificado se reutiliza")
            v = Vinculos(datos)
            puente = Puente(("127.0.0.1", 0), v, MateoFalso(), Proyectos(datos), cert, clave)
            puente.en_segundo_plano()
            try:
                ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
                ctx.check_hostname = False
                ctx.verify_mode = ssl.CERT_NONE
                with socket.create_connection(("127.0.0.1", puente.puerto)) as s, ctx.wrap_socket(s) as ss:
                    visto = hashlib.sha256(ss.getpeercert(binary_form=True)).hexdigest()
                self.assertEqual(visto, huella)
                estado, _ = pedir(f"https://127.0.0.1:{puente.puerto}", "/estado", ctx=ctx)
                self.assertEqual(estado, 401)
            finally:
                puente.shutdown()
                puente.server_close()
                v.db.close()


class MateoRealTest(unittest.TestCase):
    """Contra un Ollama falso: arma bien el pedido y limpia el <think>."""

    def test_pedido_y_limpieza(self):
        from http.server import BaseHTTPRequestHandler, HTTPServer
        import threading

        visto = {}

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_POST(self):
                visto["cuerpo"] = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                out = json.dumps({"message": {"content": "<think>pienso</think>\nNota limpia"}}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(out)))
                self.end_headers()
                self.wfile.write(out)

        srv = HTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            m = Mateo(f"http://127.0.0.1:{srv.server_port}", "qwen3:8b")
            self.assertEqual(m.organizar("  hola  ")["texto"], "Nota limpia")
            self.assertEqual(visto["cuerpo"]["model"], "qwen3:8b")
            self.assertFalse(visto["cuerpo"]["stream"])
            self.assertEqual(visto["cuerpo"]["messages"][1]["content"], "hola")
            with self.assertRaises(MateoError):
                m.organizar("   ")
            with self.assertRaises(MateoError):
                m.organizar("x" * 9000)
        finally:
            srv.shutdown()
            srv.server_close()


if __name__ == "__main__":
    unittest.main()
