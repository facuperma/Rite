"""Puntuacion y eleccion de candidatos de Mateo. Modulo puro: sin HTTP, sin Ollama, sin la app.

Uso: `puntuar(original, candidato, ...)` devuelve un `Puntaje`; `elegir(puntajes)` devuelve el indice del mejor.
Criterios, en orden de importancia: sin invenciones, largo dentro del limite, literales conservados, estructura
y, a igualdad, el mas corto.
"""

from dataclasses import dataclass, field

from . import fidelidad

CABECERAS_PROMPT = ("objetivo:", "requisitos:", "contexto:", "restricciones:", "hecho cuando:")


@dataclass
class Puntaje:
    largo: int
    limite: int
    ratio: float
    faltan: list = field(default_factory=list)
    inventadas: list = field(default_factory=list)
    estructura: bool = True
    omitidas: list = field(default_factory=list)

    @property
    def largo_ok(self) -> bool:
        return self.largo <= self.limite

    @property
    def pasa(self) -> bool:
        return self.largo_ok and not self.faltan and not self.inventadas and self.estructura

    @property
    def clave(self) -> tuple:
        """Menor es mejor."""
        return (bool(self.inventadas), not self.largo_ok, len(self.faltan), len(self.omitidas), not self.estructura, self.largo)

    def pistas(self, base_original: str = "la idea") -> list[str]:
        p = []
        if self.inventadas:
            p.append(f"NO uses estos terminos que no estan en {base_original}: " + ", ".join(self.inventadas) + ".")
        if self.faltan:
            p.append("Conserva tal cual: " + ", ".join(self.faltan) + ".")
        if not self.largo_ok:
            p.append(f"Quedo demasiado largo ({self.largo} caracteres): acortalo mucho mas, solo lo esencial.")
        if not self.estructura:
            p.append("Respeta la estructura pedida (empeza por 'Objetivo:').")
        return p

    def describir(self) -> str:
        return (
            f"{round(self.ratio * 100)} % del original, "
            + ("literales 100 %" if not self.faltan else f"faltaban {len(self.faltan)} literales")
            + (", sin invenciones" if not self.inventadas else f", {len(self.inventadas)} invenciones")
        )


def puntuar(original: str, candidato: str, limite: int, literales_de: str | None = None, estructura_prompt: bool = True) -> Puntaje:
    """`limite`: largo maximo aceptable del candidato. `literales_de`: texto cuyos literales hay que conservar."""
    base = original if literales_de is None else literales_de
    return Puntaje(
        largo=len(candidato),
        limite=limite,
        ratio=len(candidato) / max(1, len(original)),
        faltan=fidelidad.faltantes(base, candidato),
        omitidas=fidelidad.omitidas(base, candidato),
        inventadas=fidelidad.invenciones(original, candidato),
        estructura=(not estructura_prompt) or candidato.strip().lower().startswith(CABECERAS_PROMPT[0]) or _alguna_cabecera(candidato),
    )


def _alguna_cabecera(texto: str) -> bool:
    return any(l.strip().lower().startswith(CABECERAS_PROMPT) for l in texto.splitlines())


def elegir(puntajes: list[Puntaje]) -> int:
    return min(range(len(puntajes)), key=lambda i: (puntajes[i].clave, i))


def resumen(indice: int, total: int, p: Puntaje) -> str:
    return f"Mateo probó {total} {'versiones' if total != 1 else 'versión'} y te dejó la {indice + 1}ª: {p.describir()}."
