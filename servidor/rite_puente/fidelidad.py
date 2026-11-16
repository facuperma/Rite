"""Control de fidelidad: lo literal del original (codigo, rutas, identificadores, numeros) debe estar en el prompt."""

import re

_CODIGO = re.compile(r"`([^`\n]+)`")
_RUTA = re.compile(r"(?<![\w`])(?:[A-Za-z]:)?[/\\]?(?:[\w.\-]+[/\\])+[\w.\-]*|(?<![\w`/\\])[\w\-]+\.(?:kt|py|md|json|kts|xml|ps1|ts|tsx|js|sql|gradle|toml|yml|yaml|txt|html|css)\b")
_IDENT = re.compile(r"\b(?:[a-z]+(?:_[a-z0-9]+)+|[a-z]+[A-Z][A-Za-z0-9]*|[A-Z][a-z0-9]+[A-Z][A-Za-z0-9]*|[A-Z]{2,}(?:_[A-Z0-9]+)+)\b")
_NUMERO = re.compile(r"(?<![\w.])\d+(?:[.,]\d+)*(?!\d)(?![.,]\d)")


def literales(texto: str) -> list[str]:
    """Fragmentos que deben conservarse tal cual, sin repetidos y en orden de aparicion."""
    vistos: list[str] = []

    def agregar(x: str):
        x = x.strip().rstrip(".,;:")
        while x.endswith(")") and x.count(")") > x.count("("):
            x = x[:-1].rstrip(".,;:")
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


# ---- v2: numeros con limites de palabra, contexto, preguntas e invenciones ----

def _esta(lit: str, texto: str) -> bool:
    if re.fullmatch(r"\d+(?:[.,]\d+)*", lit):
        alt = {lit, lit.replace(",", "."), lit.replace(".", ",")}
        return any(re.search(r"(?<![\w.,])" + re.escape(a) + r"(?!\d|[.,]\d)", texto) for a in alt)
    return lit in texto or lit.replace("\\", "/") in texto.replace("\\", "/")


def faltantes(original: str, prompt: str) -> list[str]:  # noqa: F811 (reemplaza la version simple)
    return [lit for lit in literales(original) if not _esta(lit, prompt)]


def con_contexto(original: str, lit: str) -> str:
    """Para un numero suelto devuelve 2 palabras antes y 2 despues (de la misma frase); el resto va tal cual."""
    if not re.fullmatch(r"\d+(?:[.,]\d+)*", lit):
        return lit
    m = re.search(r"(?<![\w.,])" + re.escape(lit) + r"(?!\d|[.,]\d)", original)
    if not m:
        return lit
    corte = re.compile(r"[.!?](?=\s)|[\n:;]")
    ini = max([c.end() for c in corte.finditer(original, 0, m.start())] + [0])
    sig = corte.search(original, m.end())
    fin = sig.start() if sig else len(original)
    antes = original[ini:m.start()].replace("(", " ").split()[-2:]
    despues = re.sub(r"[()]", " ", original[m.end():fin]).split()[:2]
    limpias = [re.sub(r"^[^\w`/]+|[^\w`/%]+$", "", w) for w in antes + [lit] + despues]
    return " ".join(w for w in limpias if w)


_DUDA = re.compile(
    r"\?|\bno s[eé] (?:si|cu[aá]l|cu[aá]les|qu[eé]|c[oó]mo|cu[aá]nto)\b|\bno estoy segur[oa]\b|\bcapaz\b|\btal vez\b|\bquiz[aá]s?\b"
    r"|\ba definir\b|\bpor definir\b|\blo decidimos\b|\bhabr[ií]a que (?:ver|decidir)\b|\bto be decided\b|\bnot sure\b|\bmaybe\b|\bTBD\b",
    re.I,
)


def _frases(texto: str) -> list[str]:
    out = []
    for linea in texto.splitlines():
        out += [f.strip() for f in re.split(r"(?<=[.!?])\s+", linea) if f.strip()]
    return out


def separar_dudas(texto: str, maximo: int = 3) -> tuple[str, list[str]]:
    """(texto sin las frases de duda, hasta `maximo` dudas tal cual). Determinista: nada se reescribe."""
    dudas, resto = [], []
    for linea in texto.splitlines():
        partes = [f for f in re.split(r"(?<=[.!?])\s+", linea) if f.strip()]
        quedan = []
        for f in partes:
            if _DUDA.search(f) and len(dudas) < maximo:
                dudas.append(re.sub(r"^(?:ah,?|y|pero|igual|ademas|además)\s+(?:y\s+)?", "", f.strip(), flags=re.I).strip())
            else:
                quedan.append(f)
        resto.append(" ".join(quedan))
    return "\n".join(resto).strip(), dudas


_CABECERAS = {"Objetivo", "Contexto", "Requisitos", "Restricciones", "Hecho", "Datos", "Preguntas"}
_PALABRA = re.compile(r"[\w`/.\-]+", re.U)


def _norm(s: str) -> str:
    import unicodedata

    return "".join(c for c in unicodedata.normalize("NFD", s.lower()) if unicodedata.category(c) != "Mn")


def invenciones(original: str, prompt: str) -> list[str]:
    """Identificadores / nombres propios / terminos con digitos del prompt que no estan en el original."""
    conocidas = {_norm(w.strip("`.-/")) for w in _PALABRA.findall(original)}
    conocidas |= {_norm(w) for w in re.findall(r"\w+", original)}
    malos: list[str] = []
    for linea in prompt.splitlines():
        previa = ""
        for m in re.finditer(r"[\w][\w./\-]*", linea):
            w = m.group(0).strip(".-/")
            n = _norm(w)
            sospechosa = (
                re.search(r"[a-z][A-Z]", w) or "_" in w or (len(w) >= 2 and w.isupper() and w.isalpha()) or re.search(r"[A-Za-z]\d", w)
                or (w[:1].isupper() and previa and previa[-1:].islower() and not re.search(r"[:.\-•]\s*$", linea[: m.start()]))
            )
            if not sospechosa and w[:1].isupper() and w not in _CABECERAS and len(linea.lstrip("-•* ").split()) <= 2 and linea.lstrip().startswith(("-", "•", "*")) and linea.lstrip("-•* ").startswith(w):
                sospechosa = not linea.rstrip().endswith(":")  # item suelto con nombre propio (ej. "- Hilt")
            if sospechosa and n not in conocidas and w not in malos and not w.isdigit():
                malos.append(w)
            previa = linea[: m.end()]
    return malos
