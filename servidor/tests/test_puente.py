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
from rite_puente import fidelidad
from rite_puente.mateo import Mateo, MateoError, controlar_prompt
from rite_puente.servidor import Puente, ip_permitida
from rite_puente.vinculos import MAX_INTENTOS, VIGENCIA_CODIGO, Vinculos


class MateoFalso:
    modelo = "falso:1b"

    def __init__(self):
        self.recibido = []

    def disponible(self):
        return True

    def organizar(self, texto):
        if texto == "falla":
            raise MateoError("Mateo no responde")
        self.recibido.append(texto)
        return "# Titulo\n- " + texto.strip()

    salida_prompt = "Objetivo: x"

    def armar_prompt(self, texto):
        if texto == "falla":
            raise MateoError("Mateo no responde")
        return controlar_prompt(texto.strip(), self.salida_prompt, self.modelo)


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

    def test_controlar_avisa_faltantes(self):
        r = controlar_prompt(ORIGINAL, "Objetivo: boton nuevo; 8000", "m")
        self.assertEqual(r["prompt"], "Objetivo: boton nuevo; 8000")
        self.assertTrue(r["advertencias"])
        self.assertIn("ui/Editor.kt", r["advertencias"][0])
        self.assertEqual(r["caracteres_antes"], len(ORIGINAL))
        self.assertEqual(r["caracteres_despues"], len(r["prompt"]))

    def test_sin_advertencias_si_conserva_todo(self):
        p = "Obj: ui/Editor.kt, /mateo/prompt, caracteres_antes, 8000, 1,5, servidor/rite_puente/mateo.py"
        self.assertEqual(controlar_prompt(ORIGINAL, p, "m")["advertencias"], [])

    def test_mas_largo_devuelve_original_compactado(self):
        orig = "hacer   algo\n\n\n\ncon `x_y`"
        r = controlar_prompt(orig, "un texto muchisimo mas largo que el original, con `x_y` y relleno", "m")
        self.assertEqual(r["prompt"], "hacer algo" + chr(10) * 2 + "con `x_y`")
        self.assertEqual(len(r["advertencias"]), 1)


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
            self.assertEqual(m.organizar("  hola  "), "Nota limpia")
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
