"""Control de fidelidad: lo literal del original (codigo, rutas, identificadores, numeros) debe estar en el prompt."""

import re

_CODIGO = re.compile(r"`([^`\n]+)`")
_RUTA = re.compile(r"(?<![\w`])(?:[A-Za-z]:)?[/\\]?(?:[\w.\-]+[/\\])+[\w.\-]*|(?<![\w`/\\])[\w\-]+\.(?:kt|py|md|json|kts|xml|ps1|ts|tsx|js|sql|gradle|toml|yml|yaml|txt|html|css)\b")
_IDENT = re.compile(r"\b(?:[a-z]+(?:_[a-z0-9]+)+|[a-z]+[A-Z][A-Za-z0-9]*|[A-Z][a-z0-9]+[A-Z][A-Za-z0-9]*|[A-Z]{2,}(?:_[A-Z0-9]+)+)\b")
_NUMERO = re.compile(r"(?<![\w.])\d+(?:[.,]\d+)?(?![\w])")


def literales(texto: str) -> list[str]:
    """Fragmentos que deben conservarse tal cual, sin repetidos y en orden de aparicion."""
    vistos: list[str] = []

    def agregar(x: str):
        x = x.strip().rstrip(".,;:)")
        if x and x not in vistos:
            vistos.append(x)

    for m in _CODIGO.finditer(texto):
        agregar(m.group(1))
    sin_codigo = _CODIGO.sub(" ", texto)
    for rx in (_RUTA, _IDENT):
        for m in rx.finditer(sin_codigo):
            agregar(m.group(0))
    for m in _NUMERO.finditer(sin_codigo):
        agregar(m.group(0))
    return vistos


def faltantes(original: str, prompt: str) -> list[str]:
    destino = prompt.replace("\\", "/")
    res = []
    for lit in literales(original):
        if lit in prompt or lit.replace("\\", "/") in destino:
            continue
        # un numero suelto puede aparecer pegado a otro texto (1,5 vs 1.5)
        if re.fullmatch(r"\d+[.,]\d+", lit) and (lit.replace(",", ".") in prompt or lit.replace(".", ",") in prompt):
            continue
        res.append(lit)
    return res


def compactar(texto: str) -> str:
    """Saca espacios y lineas en blanco sobrantes sin tocar el contenido."""
    lineas = [re.sub(r"[ \t]+", " ", l).rstrip() for l in texto.strip().splitlines()]
    out: list[str] = []
    for l in lineas:
        if l == "" and (not out or out[-1] == ""):
            continue
        out.append(l)
    return "\n".join(out).strip()
