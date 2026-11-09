"""Cliente de Mateo: el LLM local (Ollama)."""

import json
import re
import urllib.error
import urllib.request

LIMITE_TEXTO = 8000

SISTEMA_ORGANIZAR = (
    "Sos Mateo, el asistente de notas de Facundo. Te paso una nota suya. Reorganizala para que sea mas clara: "
    "un titulo corto en la primera linea, despues las ideas agrupadas con vinietas y las tareas pendientes como "
    "lista con casillas (☐). "
    "Reglas: NO inventes datos, nombres, fechas ni cifras que no esten en la nota. No agregues ideas nuevas; "
    "solo si algo queda ambiguo, agrega al final una seccion 'Preguntas' con un maximo de 3 dudas que surjan de lo escrito. "
    "Mantene el idioma y el tono de la nota. Devolve solamente la nota reorganizada, sin explicaciones."
)


class MateoError(Exception):
    pass


class Mateo:
    def __init__(self, url: str = "http://127.0.0.1:11434", modelo: str = "qwen3:8b", timeout: float = 240):
        self.url = url.rstrip("/")
        self.modelo = modelo
        self.timeout = timeout

    def disponible(self) -> bool:
        try:
            with urllib.request.urlopen(self.url + "/api/tags", timeout=2) as r:
                return r.status == 200
        except Exception:
            return False

    def organizar(self, texto: str) -> str:
        texto = texto.strip()
        if not texto:
            raise MateoError("La nota esta vacia")
        if len(texto) > LIMITE_TEXTO:
            raise MateoError(f"La nota es demasiado larga ({len(texto)} caracteres; maximo {LIMITE_TEXTO})")
        cuerpo = {
            "model": self.modelo,
            "stream": False,
            "think": False,
            "options": {"temperature": 0.2},
            "messages": [
                {"role": "system", "content": SISTEMA_ORGANIZAR},
                {"role": "user", "content": texto},
            ],
        }
        pedido = urllib.request.Request(
            self.url + "/api/chat", data=json.dumps(cuerpo).encode(), headers={"Content-Type": "application/json"}
        )
        try:
            with urllib.request.urlopen(pedido, timeout=self.timeout) as r:
                data = json.loads(r.read())
        except urllib.error.URLError as e:
            raise MateoError("No pude hablar con Ollama. Esta abierto en la PC?") from e
        except TimeoutError as e:
            raise MateoError("Mateo tardo demasiado en responder") from e
        salida = (data.get("message") or {}).get("content", "")
        salida = re.sub(r"<think>.*?</think>", "", salida, flags=re.S).strip()
        if not salida:
            raise MateoError("Mateo devolvio una respuesta vacia")
        return salida
