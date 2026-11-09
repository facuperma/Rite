"""Vinculacion de celulares: codigo de un solo uso -> token por dispositivo (guardado con hash)."""

import hashlib
import hmac
import secrets
import sqlite3
import time
from pathlib import Path

VIGENCIA_CODIGO = 10 * 60
MAX_INTENTOS = 5


def _h(texto: str) -> str:
    return hashlib.sha256(texto.encode()).hexdigest()


class Vinculos:
    def __init__(self, datos: Path, reloj=time.time):
        self.reloj = reloj
        self.db = sqlite3.connect(str(datos / "puente.db"), check_same_thread=False, isolation_level=None)
        self.db.executescript(
            """
            CREATE TABLE IF NOT EXISTS dispositivo(
                id INTEGER PRIMARY KEY AUTOINCREMENT, nombre TEXT NOT NULL,
                token_hash TEXT NOT NULL UNIQUE, creado REAL NOT NULL, ultimo_uso REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS codigo(
                id INTEGER PRIMARY KEY CHECK (id = 1), hash TEXT NOT NULL, expira REAL NOT NULL, intentos INTEGER NOT NULL);
            """
        )

    def nuevo_codigo(self) -> str:
        """Un solo codigo vigente a la vez: pedir uno nuevo invalida el anterior."""
        codigo = f"{secrets.randbelow(10**6):06d}"
        self.db.execute(
            "INSERT OR REPLACE INTO codigo(id, hash, expira, intentos) VALUES (1, ?, ?, 0)",
            (_h(codigo), self.reloj() + VIGENCIA_CODIGO),
        )
        return codigo

    def canjear(self, codigo: str, nombre: str) -> str | None:
        """Si el codigo es correcto devuelve un token nuevo (y gasta el codigo). Si no, None."""
        fila = self.db.execute("SELECT hash, expira, intentos FROM codigo WHERE id = 1").fetchone()
        if not fila:
            return None
        hash_, expira, intentos = fila
        if self.reloj() > expira or intentos >= MAX_INTENTOS:
            self.db.execute("DELETE FROM codigo WHERE id = 1")
            return None
        if not hmac.compare_digest(hash_, _h(str(codigo).strip())):
            self.db.execute("UPDATE codigo SET intentos = intentos + 1 WHERE id = 1")
            return None
        self.db.execute("DELETE FROM codigo WHERE id = 1")
        token = secrets.token_urlsafe(32)
        ahora = self.reloj()
        self.db.execute(
            "INSERT INTO dispositivo(nombre, token_hash, creado, ultimo_uso) VALUES (?, ?, ?, ?)",
            ((nombre or "Celular").strip()[:60], _h(token), ahora, ahora),
        )
        return token

    def verificar(self, token: str) -> dict | None:
        if not token:
            return None
        fila = self.db.execute("SELECT id, nombre FROM dispositivo WHERE token_hash = ?", (_h(token),)).fetchone()
        if not fila:
            return None
        self.db.execute("UPDATE dispositivo SET ultimo_uso = ? WHERE id = ?", (self.reloj(), fila[0]))
        return {"id": fila[0], "nombre": fila[1]}

    def listar(self) -> list[dict]:
        filas = self.db.execute("SELECT id, nombre, creado, ultimo_uso FROM dispositivo ORDER BY id").fetchall()
        return [{"id": i, "nombre": n, "creado": c, "ultimo_uso": u} for i, n, c, u in filas]

    def revocar(self, id_: int) -> bool:
        return self.db.execute("DELETE FROM dispositivo WHERE id = ?", (id_,)).rowcount > 0
