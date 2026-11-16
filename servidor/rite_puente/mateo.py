"""Cliente de Mateo: el LLM local (Ollama)."""

import json
import re
import urllib.error
import urllib.request

from . import fidelidad

LIMITE_TEXTO = 8000

SISTEMA_ORGANIZAR = (
    "Sos Mateo, el asistente de notas de Facundo. Te paso una nota suya. Reorganizala para que sea mas clara: "
    "un titulo corto en la primera linea, despues las ideas agrupadas con vinietas y las tareas pendientes como "
    "lista con casillas (☐). "
    "Reglas: NO inventes datos, nombres, fechas ni cifras que no esten en la nota. No agregues ideas nuevas; "
    "solo si algo queda ambiguo, agrega al final una seccion 'Preguntas' con un maximo de 3 dudas que surjan de lo escrito. "
    "Mantene el idioma y el tono de la nota. Devolve solamente la nota reorganizada, sin explicaciones."
)

SISTEMA_PROMPT = (
    "Sos Mateo. Te paso una idea ya desarrollada de Facundo. Convertila en un PROMPT para Claude Code: eficiente, "
    "telegrafico y corto, para gastar pocos tokens. Estructura, solo con las secciones que apliquen:\n"
    "Objetivo: una sola linea.\n"
    "Contexto: lo minimo necesario.\n"
    "Requisitos: vinietas cortas (una idea por linea).\n"
    "Restricciones: vinietas cortas.\n"
    "Hecho cuando: criterio de aceptacion verificable.\n"
    "Preguntas abiertas: maximo 3, solo lo realmente ambiguo.\n"
    "Omiti las secciones que no tengan contenido propio del original (no rellenes). No copies estas instrucciones ni agregues una seccion de reglas. "
    "Reglas: conserva TAL CUAL nombres, rutas, identificadores, codigo y numeros del original. NO inventes nada: ni "
    "requisitos, ni tecnologias, ni cifras que no esten escritos. Sin saludos, sin explicaciones, sin relleno, sin "
    "repetir ideas. Mismo idioma del original. Devolve solamente el prompt, que no debe ser mas largo que el original."
).replace("\u200b", "")


class MateoError(Exception):
    pass


class Mateo:
    def __init__(
        self, url: str = "http://127.0.0.1:11434", modelo: str = "qwen3:8b", timeout: float = 240, modelo_prompt: str | None = None
    ):
        self.url = url.rstrip("/")
        self.modelo = modelo
        self.modelo_prompt = modelo_prompt or modelo
        self.timeout = timeout

    def disponible(self) -> bool:
        try:
            with urllib.request.urlopen(self.url + "/api/tags", timeout=2) as r:
                return r.status == 200
        except Exception:
            return False

    def organizar(self, texto: str) -> str:
        return self._chat(SISTEMA_ORGANIZAR, texto, self.modelo)

    def armar_prompt(self, texto: str) -> dict:
        """Prompt compacto para Claude Code + control de fidelidad. No se envia a ningun lado."""
        original = texto.strip()
        salida = self._chat(SISTEMA_PROMPT, texto, self.modelo_prompt or self.modelo)
        return controlar_prompt(original, salida, self.modelo_prompt or self.modelo)

    def _chat(self, sistema: str, texto: str, modelo: str) -> str:
        texto = texto.strip()
        if not texto:
            raise MateoError("La nota esta vacia")
        if len(texto) > LIMITE_TEXTO:
            raise MateoError(f"La nota es demasiado larga ({len(texto)} caracteres; maximo {LIMITE_TEXTO})")
        cuerpo = {
            "model": modelo,
            "stream": False,
            "think": False,
            "options": {"temperature": 0.2},
            "messages": [
                {"role": "system", "content": sistema},
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


def controlar_prompt(original: str, prompt: str, modelo: str) -> dict:
    """Si el prompt pierde literales lo avisa; si no achica, devuelve el original compactado."""
    advertencias: list[str] = []
    final = prompt.strip()
    if len(final) >= len(original):
        final = fidelidad.compactar(original)
        advertencias.append("El prompt de Mateo no era mas corto que tu texto; te dejo tu texto compactado, sin cambios de contenido.")
    else:
        falta = fidelidad.faltantes(original, final)
        if falta:
            advertencias.append("Falta en el prompt algo literal del original: " + ", ".join(f"«{x}»" for x in falta[:12]))
    return {
        "prompt": final,
        "modelo": modelo,
        "caracteres_antes": len(original),
        "caracteres_despues": len(final),
        "advertencias": advertencias,
    }
