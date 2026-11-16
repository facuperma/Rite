"""Linea de comandos: python -m rite_puente servir | vincular | dispositivos | proyectos"""

import argparse
import socket
import sys

from . import certificado
from .config import Proyectos, carpeta_datos
from .mateo import Mateo
from .servidor import Puente
from .vinculos import Vinculos


def _ip_local() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


def _enlace(direccion: str, puerto: int, codigo: str, huella: str) -> str:
    return f"rite://{direccion}:{puerto}?c={codigo}&h={huella}"


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="rite_puente")
    p.add_argument("--datos", help="carpeta de datos (por defecto %%LOCALAPPDATA%%\\Rite)")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("servir", help="arranca el puente")
    s.add_argument("--puerto", type=int, default=8787)
    s.add_argument("--escuchar", default="0.0.0.0")
    s.add_argument("--direccion", help="IP que se escribe en el enlace de vinculacion (por defecto la de la LAN)")
    s.add_argument("--modelo", default="qwen3:8b")
    s.add_argument("--modelo-prompt", default=None, help="modelo para 'Prompt para Claude' (por defecto el mismo que --modelo)")
    s.add_argument("--ollama", default="http://127.0.0.1:11434")
    s.add_argument("--sin-codigo", action="store_true", help="no genera ni imprime codigo de vinculacion (para arranque automatico)")

    v = sub.add_parser("vincular", help="genera un codigo de vinculacion nuevo (10 min, un solo uso)")
    v.add_argument("--puerto", type=int, default=8787)
    v.add_argument("--direccion")

    d = sub.add_parser("dispositivos")
    d.add_argument("accion", choices=["listar", "revocar"])
    d.add_argument("id", nargs="?", type=int)

    pr = sub.add_parser("proyectos")
    pr.add_argument("accion", choices=["listar", "agregar", "quitar"])
    pr.add_argument("nombre", nargs="?")
    pr.add_argument("ruta", nargs="?")
    pr.add_argument("--predeterminado", action="store_true")

    a = p.parse_args(argv)
    datos = carpeta_datos(a.datos)

    if a.cmd == "proyectos":
        proy = Proyectos(datos)
        try:
            if a.accion == "listar":
                for x in proy.listar():
                    print(("* " if x["predeterminado"] else "  ") + f"{x['nombre']}  ->  {x['ruta']}")
            elif a.accion == "agregar":
                proy.agregar(a.nombre or "", a.ruta or "", a.predeterminado)
                print("Proyecto registrado.")
            else:
                proy.quitar(a.nombre or "")
                print("Proyecto quitado.")
        except ValueError as e:
            print("Error:", e)
            return 1
        return 0

    vinculos = Vinculos(datos)
    if a.cmd == "dispositivos":
        if a.accion == "listar":
            for x in vinculos.listar():
                print(f"{x['id']}  {x['nombre']}")
        elif a.id is None or not vinculos.revocar(a.id):
            print("No existe ese dispositivo.")
            return 1
        else:
            print("Dispositivo desvinculado.")
        return 0

    cert, clave, huella = certificado.asegurar(datos)
    direccion = a.direccion or _ip_local()
    if a.cmd == "vincular":
        print(_enlace(direccion, a.puerto, vinculos.nuevo_codigo(), huella))
        return 0

    puente = Puente((a.escuchar, a.puerto), vinculos, Mateo(a.ollama, a.modelo, modelo_prompt=a.modelo_prompt), Proyectos(datos), cert, clave)
    print(f"Puente de Rite escuchando en {a.escuchar}:{puente.puerto} (HTTPS). Huella: {huella}")
    if not a.sin_codigo:
        print("Para vincular un celular, pega este enlace en la app (vale 10 minutos, un solo uso):")
        print(_enlace(direccion, puente.puerto, vinculos.nuevo_codigo(), huella))
    try:
        puente.serve_forever()
    except KeyboardInterrupt:
        print("Puente detenido.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
