"""Arma el prompt para Claude Code: Mateo comprime, el codigo garantiza largo, literales, dudas y cero invenciones."""

import re
from typing import Callable

from . import fidelidad, puntuacion

CANDIDATOS = 3  # K por defecto
MIN_PARA_PREGUNTAS = 120  # notas mas cortas no generan preguntas
RATIO_OBJETIVO = 0.55      # notas largas
RATIO_CORTAS = 0.80        # notas < 300 caracteres
UMBRAL_CORTA = 300

SISTEMA = (
    "Convertis una idea en un PROMPT para Claude Code: telegrafico, sin relleno, para gastar pocos tokens. "
    "Formato (omiti las secciones que no tengan contenido en la idea):\n"
    "Objetivo: una linea.\nContexto: lo minimo.\nRequisitos:\n- viñetas de pocas palabras\nRestricciones:\n- viñetas\nHecho cuando: criterio verificable.\n"
    "Solo pone 'Hecho cuando' si la idea dice como se comprueba. Reglas estrictas: (1) copia TAL CUAL nombres, rutas, archivos, identificadores, codigo, numeros y versiones. "
    "(2) NO inventes nada: ni tecnologias, ni requisitos, ni nombres que no esten en la idea. "
    "(3) NO escribas preguntas ni dudas ni 'Preguntas abiertas': eso se agrega aparte. "
    "(4) Sin saludos, sin explicaciones, sin repetir ideas, sin copiar estas reglas. "
    "(5) Respeta el maximo de caracteres que te indique el usuario; mas corto es mejor. Mismo idioma de la idea."
)

EJEMPLO_IDEA = (
    "Che, necesito que la pantalla de avisos en `ui/Avisos.kt` muestre un contador de no leidos. Hoy no muestra nada. "
    "Tiene que leer `AvisosRepo.pendientes()` y mostrarse en la barra, con tope de 99. "
    "Obvio no quiero librerias nuevas. Gracias!"
)
EJEMPLO_PROMPT = (
    "Objetivo: contador de no leidos en `ui/Avisos.kt`.\n"
    "Contexto: hoy no muestra nada.\n"
    "Requisitos:\n- leer `AvisosRepo.pendientes()`\n- mostrarlo en la barra\n- tope 99\n"
    "Restricciones:\n- sin librerias nuevas"
)

Llamar = Callable[[list, dict], str]


def _cortar_dudas_del_modelo(texto: str) -> str:
    """Saca cualquier seccion de preguntas/dudas/reglas que el modelo haya agregado igual."""
    out, saltando = [], False
    for linea in texto.splitlines():
        t = linea.strip().lstrip("#*- ").lower()
        if re.match(r"(preguntas?( abiertas?)?|dudas?|reglas?|open questions)\b", t):
            saltando = True
            continue
        if saltando and re.match(r"(objetivo|contexto|requisitos|restricciones|hecho cuando|datos)\b", t):
            saltando = False
        if not saltando:
            out.append(linea.rstrip())
    return "\n".join(out)


def _limpiar(texto: str) -> str:
    texto = re.sub(r"^```\w*\n?|\n?```$", "", texto.strip())
    texto = _cortar_dudas_del_modelo(texto)
    # sin lineas repetidas y sin espacios al final
    vistas, out = set(), []
    for l in texto.splitlines():
        k = l.strip().lower()
        if k and k in vistas:
            continue
        vistas.add(k)
        out.append(re.sub(r"[ \t]+$", "", l))
    return "\n".join(l for l in out if l.strip()).strip()


def _ensamblar(cuerpo: str, falta: list[str], original: str, dudas: list[str]) -> str:
    partes = [cuerpo.strip()] if cuerpo.strip() else []
    if falta:
        trozos: list[str] = []
        for x in sorted(falta, key=len, reverse=True):
            if not any(x in t for t in trozos):
                trozos.append(fidelidad.con_contexto(original, x))
        partes.append("Datos: " + "; ".join(trozos))
    if dudas:
        partes.append("Preguntas abiertas:\n" + "\n".join("- " + d for d in dudas))
    return "\n".join(partes)


def armar(original: str, llamar: Llamar, modelo: str, pista: str = "", k: int = CANDIDATOS, sugerir=None) -> dict:
    """Genera hasta k candidatos, los puntua, refina con feedback y devuelve solo el mejor.

    `llamar(mensajes, opciones) -> str` habla con el modelo; `sugerir(texto) -> list[str]` (opcional) arma las preguntas
    sugeridas, que quedan FUERA del prompt y del conteo de largo.
    """
    original = original.strip()
    sin_dudas, dudas = fidelidad.separar_dudas(original)
    base = fidelidad.compactar(sin_dudas) if sin_dudas.strip() else ""
    ratio = RATIO_CORTAS if len(original) < UMBRAL_CORTA else RATIO_OBJETIVO
    largo_dudas = sum(len(d) + 3 for d in dudas) + (22 if dudas else 0)
    compactado = fidelidad.compactar(original)
    aceptable = int((0.7 if len(original) < 500 else ratio) * len(original))

    cuerpo, falta, candidatos, resumen_it = "", [], 0, ""
    if base:
        presupuesto = max(40, int(0.35 * len(base)), int(ratio * len(original)) - largo_dudas)
        limite_cuerpo = max(presupuesto + 15, aceptable - largo_dudas)
        feedback: list[str] = []
        todos = []
        for i in range(max(1, k)):
            candidatos += 1
            tope = max(40, int(presupuesto * (0.8 ** i)))
            instr = f"Maximo {tope} caracteres en total."
            if pista.strip():
                instr += " Indicacion de Facundo: " + pista.strip()[:300] + "."
            if feedback:
                instr += " " + " ".join(feedback)
            mensajes = [
                {"role": "system", "content": SISTEMA},
                {"role": "user", "content": EJEMPLO_IDEA + "\n\n(Maximo 190 caracteres.)"},
                {"role": "assistant", "content": EJEMPLO_PROMPT},
                {"role": "user", "content": base + "\n\n(" + instr + ")"},
            ]
            opciones = {"temperature": 0.1 if i == 0 else 0.0, "num_predict": int(tope / 2.2) + 40}
            c = _limpiar(llamar(mensajes, opciones))
            p = puntuacion.puntuar(original, c, limite_cuerpo, literales_de=base)
            todos.append((c, p))
            if p.pasa:
                break
            feedback = p.pistas()
        idx = puntuacion.elegir([p for _, p in todos])
        cuerpo, mejor = todos[idx]
        resumen_it = puntuacion.resumen(idx, len(todos), mejor)
        if mejor.inventadas:  # las que sobreviven: se sacan las lineas que las contienen
            cuerpo = "\n".join(l for l in cuerpo.splitlines() if not any(x in l for x in mejor.inventadas))
        falta = fidelidad.faltantes(base, cuerpo)

    final = _ensamblar(cuerpo, falta, original, dudas)
    advertencias: list[str] = []
    if not final.strip() or len(final) >= len(compactado) or fidelidad.faltantes(original, final):
        final = compactado
        advertencias.append("Tu nota ya era corta; la dejé compactada, sin cambios." if len(original) < UMBRAL_CORTA * 2 else
                            "No logré achicarla sin perder datos; la dejé compactada, sin cambios.")
        resumen_it = "Tu texto ya era corto: no encontré una versión más corta que conservara todo, así que lo dejé compactado."
    sugeridas: list[str] = []
    if sugerir is not None:
        try:
            sugeridas = limpiar_preguntas(sugerir(original), dudas, original) if len(original) >= MIN_PARA_PREGUNTAS else []
        except Exception:  # las sugerencias son un extra: si fallan, el prompt sale igual
            sugeridas = []
    afuera = [] if final == compactado else fidelidad.omitidas(original, final)
    if afuera:
        resumen_it = (resumen_it + " " if resumen_it else "") + f"Dejé afuera {len(afuera)} frase{'s' if len(afuera) != 1 else ''} (podés volver a agregarlas)."
    return {
        "prompt": final,
        "dejado_afuera": afuera,
        "modelo": modelo,
        "caracteres_antes": len(original),
        "caracteres_despues": len(final),
        "advertencias": advertencias,
        "intentos": candidatos,
        "resumen_iteracion": resumen_it,
        "preguntas_del_original": dudas,
        "preguntas_sugeridas": sugeridas,
    }


SISTEMA_PREGUNTAS = (
    "Lees una nota y propones hasta 3 preguntas CORTAS que ayuden a pensar MEJOR ESA nota: dudas de diseño, casos borde o "
    "alternativas sobre lo que la nota dice. Formato de cada linea, exacto: ¿pregunta? | \"frase textual de la nota que la motivo\". "
    "La frase entre comillas tiene que estar copiada de la nota. Maximo 110 caracteres por pregunta. Prohibido preguntar "
    "generalidades (conexion, seguridad, 'el sistema', 'el usuario') que la nota no menciona. Si la nota es muy corta o no "
    "hay una base concreta, devolve una linea vacia."
)


def limpiar_preguntas(crudo, del_original: list[str] | None = None, nota: str | None = None, maximo: int = 3) -> list[str]:
    """Deja hasta `maximo` preguntas cortas, sin repetidas ni las del original.

    Con `nota`, exige formato «¿pregunta? | "cita"», que la cita este en la nota y que la pregunta comparta contenido con ella.
    """
    lineas = crudo.splitlines() if isinstance(crudo, str) else list(crudo)
    conocidas = {re.sub(r"\W+", "", d.lower()) for d in (del_original or [])}
    out: list[str] = []
    for l in lineas:
        q = re.sub(r"^\s*(?:[-*•]|\d+[.)])\s*", "", str(l)).strip()
        if nota is not None:
            q, sep, cita = q.partition("|")
            q = q.strip()
            if not sep or not fidelidad.contiene_cita(cita, nota) or not fidelidad.comparte_contenido(q, nota):
                continue
        if not q.endswith("?") or len(q) < 8 or len(q) > 140:
            continue
        k = re.sub(r"\W+", "", q.lower())
        if k in conocidas or any(k == re.sub(r"\W+", "", o.lower()) for o in out):
            continue
        out.append(q)
        if len(out) >= maximo:
            break
    return out


def organizar(original: str, llamar: Llamar, modelo: str, sistema: str, pista: str = "", k: int = CANDIDATOS) -> dict:
    """Organizar una nota con varios candidatos internos; devuelve solo el mejor."""
    original = original.strip()
    limite = int(1.6 * len(original)) + 60
    feedback: list[str] = []
    todos = []
    for i in range(max(1, k)):
        extra = ""
        if pista.strip():
            extra += "\n\n(Indicacion de Facundo: " + pista.strip()[:300] + ")"
        if feedback:
            extra += "\n\n(" + " ".join(feedback) + ")"
        mensajes = [{"role": "system", "content": sistema}, {"role": "user", "content": original + extra}]
        c = llamar(mensajes, {"temperature": 0.2 if i == 0 else 0.0}).strip()
        p = puntuacion.puntuar(original, c, limite, estructura_prompt=False)
        todos.append((c, p))
        if p.pasa:
            break
        feedback = p.pistas("la nota")
    idx = puntuacion.elegir([p for _, p in todos])
    texto, mejor = todos[idx]
    return {"texto": texto, "modelo": modelo, "resumen_iteracion": puntuacion.resumen(idx, len(todos), mejor)}
