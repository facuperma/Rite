"""Carpeta de datos y registro de proyectos de Claude Code."""

import json
import os
from pathlib import Path


def carpeta_datos(override=None) -> Path:
    if override:
        base = Path(override)
    elif os.environ.get("RITE_DATOS"):
        base = Path(os.environ["RITE_DATOS"])
    else:
        base = Path(os.environ.get("LOCALAPPDATA") or Path.home()) / "Rite"
    base.mkdir(parents=True, exist_ok=True)
    return base


class Proyectos:
    """Proyectos de Claude Code a los que Rite puede mandar notas.

    Productor es el predeterminado; se pueden registrar otros (cada uno es una carpeta
    con su propio CLAUDE.md) sin tocar el codigo.
    """

    def __init__(self, datos: Path):
        self.archivo = datos / "proyectos.json"
        if not self.archivo.exists():
            productor = Path.home() / "Documents" / "productor"
            self._guardar({"predeterminado": "Productor", "proyectos": [{"nombre": "Productor", "ruta": str(productor)}]})

    def _leer(self) -> dict:
        return json.loads(self.archivo.read_text(encoding="utf-8"))

    def _guardar(self, d: dict) -> None:
        self.archivo.write_text(json.dumps(d, ensure_ascii=False, indent=2), encoding="utf-8")

    def listar(self) -> list[dict]:
        d = self._leer()
        return [{**p, "predeterminado": p["nombre"].lower() == d["predeterminado"].lower()} for p in d["proyectos"]]

    def obtener(self, nombre: str | None = None) -> dict | None:
        d = self._leer()
        buscado = (nombre or d["predeterminado"]).lower()
        return next((p for p in d["proyectos"] if p["nombre"].lower() == buscado), None)

    def agregar(self, nombre: str, ruta: str, predeterminado: bool = False) -> None:
        nombre = nombre.strip()
        if not nombre:
            raise ValueError("El proyecto necesita un nombre")
        if not Path(ruta).is_dir():
            raise ValueError(f"La carpeta no existe: {ruta}")
        d = self._leer()
        d["proyectos"] = [p for p in d["proyectos"] if p["nombre"].lower() != nombre.lower()]
        d["proyectos"].append({"nombre": nombre, "ruta": str(Path(ruta).resolve())})
        if predeterminado:
            d["predeterminado"] = nombre
        self._guardar(d)

    def quitar(self, nombre: str) -> None:
        d = self._leer()
        if nombre.lower() == d["predeterminado"].lower():
            raise ValueError("No se puede quitar el proyecto predeterminado")
        d["proyectos"] = [p for p in d["proyectos"] if p["nombre"].lower() != nombre.lower()]
        self._guardar(d)
