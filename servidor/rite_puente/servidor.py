"""Servidor HTTP(S) del puente. Solo biblioteca estandar."""

import ipaddress
import json
import ssl
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import VERSION
from .mateo import Mateo, MateoError
from .vinculos import Vinculos

MAX_CUERPO = 256 * 1024
_CGNAT = ipaddress.ip_network("100.64.0.0/10")  # Tailscale


def ip_permitida(ip: str) -> bool:
    """Solo red local, loopback y Tailscale. Nada de internet abierta."""
    try:
        a = ipaddress.ip_address(ip.split("%")[0])
    except ValueError:
        return False
    if a.version == 6 and a.ipv4_mapped:
        a = a.ipv4_mapped
    return a.is_private or a.is_loopback or a in _CGNAT


class Puente(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, direccion, vinculos: Vinculos, mateo: Mateo, proyectos, cert=None, clave=None):
        super().__init__(direccion, Manejador)
        self.vinculos = vinculos
        self.mateo = mateo
        self.proyectos = proyectos
        if cert and clave:
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.minimum_version = ssl.TLSVersion.TLSv1_2
            ctx.load_cert_chain(str(cert), str(clave))
            self.socket = ctx.wrap_socket(self.socket, server_side=True)

    @property
    def puerto(self) -> int:
        return self.server_address[1]

    def en_segundo_plano(self) -> threading.Thread:
        t = threading.Thread(target=self.serve_forever, daemon=True)
        t.start()
        return t


class Manejador(BaseHTTPRequestHandler):
    server_version = "RitePuente/" + VERSION
    protocol_version = "HTTP/1.1"

    def log_message(self, formato, *args):  # silencioso: no se loguean cuerpos ni tokens
        pass

    # ---- utilidades ----
    def _responder(self, codigo: int, cuerpo: dict):
        datos = json.dumps(cuerpo, ensure_ascii=False).encode()
        self.send_response(codigo)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(datos)))
        self.end_headers()
        self.wfile.write(datos)

    def _leer_json(self) -> dict | None:
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = -1
        if n < 0 or n > MAX_CUERPO:
            self._responder(413, {"error": "Cuerpo demasiado grande"})
            return None
        try:
            d = json.loads(self.rfile.read(n) or b"{}")
            if not isinstance(d, dict):
                raise ValueError
            return d
        except ValueError:
            self._responder(400, {"error": "JSON invalido"})
            return None

    def _dispositivo(self) -> dict | None:
        cab = self.headers.get("Authorization", "")
        dispositivo = self.server.vinculos.verificar(cab[7:] if cab.startswith("Bearer ") else "")
        if dispositivo is None:
            self._responder(401, {"error": "No autorizado: vincula el celular de nuevo"})
        return dispositivo

    def _filtrar_ip(self) -> bool:
        if ip_permitida(self.client_address[0]):
            return True
        self._responder(403, {"error": "Origen no permitido"})
        return False

    # ---- rutas ----
    def do_GET(self):
        if not self._filtrar_ip():
            return
        if self.path == "/estado":
            if self._dispositivo() is None:
                return
            self._responder(
                200,
                {
                    "version": VERSION,
                    "mateo": self.server.mateo.disponible(),
                    "modelo": self.server.mateo.modelo,
                    "proyectos": self.server.proyectos.listar(),
                },
            )
        else:
            self._responder(404, {"error": "No existe"})

    def do_POST(self):
        if not self._filtrar_ip():
            return
        if self.path == "/vincular":
            d = self._leer_json()
            if d is None:
                return
            token = self.server.vinculos.canjear(str(d.get("codigo", "")), str(d.get("nombre", "")))
            if token is None:
                self._responder(403, {"error": "Codigo incorrecto o vencido"})
            else:
                self._responder(200, {"token": token})
        elif self.path in ("/mateo/organizar", "/mateo/prompt"):
            if self._dispositivo() is None:
                return
            d = self._leer_json()
            if d is None:
                return
            texto = d.get("texto")
            if not isinstance(texto, str):
                self._responder(400, {"error": "Falta el campo texto"})
                return
            pista = d.get("pista", "")
            if not isinstance(pista, str):
                self._responder(400, {"error": "El campo pista debe ser texto"})
                return
            titulo = d.get("titulo", "")
            if not isinstance(titulo, str):
                self._responder(400, {"error": "El campo titulo debe ser texto"})
                return
            try:
                if self.path == "/mateo/prompt":
                    self._responder(200, self.server.mateo.armar_prompt(texto, pista, titulo))
                else:
                    self._responder(200, self.server.mateo.organizar(texto, pista))
            except MateoError as e:
                self._responder(502, {"error": str(e)})
        else:
            self._responder(404, {"error": "No existe"})
