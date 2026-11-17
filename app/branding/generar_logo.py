"""Logo de Rite v3, estilo TVon/TPT: cuadrado negro de esquinas redondeadas, marco cuadrado blanco y 'Rite'
en una sola linea, todo blanco, tipografia recta. Uso: python logo_rite3.py <dir> [hoja|final]"""
import os
import sys

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
from PySide6.QtCore import QRectF, Qt
from PySide6.QtGui import (QBrush, QColor, QFont, QFontDatabase, QGuiApplication, QImage, QLinearGradient,
                           QPainter, QPainterPath, QPen, QTransform)

app = QGuiApplication(sys.argv)
SALIDA = sys.argv[1] if len(sys.argv) > 1 else "."
MODO = sys.argv[2] if len(sys.argv) > 2 else "hoja"
os.makedirs(SALIDA, exist_ok=True)


def fuente(archivo, peso=None):
    ident = QFontDatabase.addApplicationFont(os.path.join(r"C:\Windows\Fonts", archivo))
    f = QFont(QFontDatabase.applicationFontFamilies(ident)[0])
    f.setPixelSize(300)
    if peso is not None:
        f.setWeight(peso)
    return f


def logo(archivo_fuente, salida, tam=1024, peso=None, ancho_texto=0.50, marco_lado=0.72, marco_grosor=0.07,
         solo_primer_plano=False):
    f = fuente(archivo_fuente, peso)
    cam = QPainterPath()
    cam.addText(0, 0, f, "Rite")
    caja = cam.boundingRect()
    esc = tam * ancho_texto / caja.width()
    cam = QTransform().translate(tam / 2, tam / 2).scale(esc, esc).translate(-caja.center().x(), -caja.center().y()).map(cam)

    img = QImage(tam, tam, QImage.Format_ARGB32)
    img.fill(Qt.transparent)
    p = QPainter(img)
    p.setRenderHints(QPainter.Antialiasing | QPainter.SmoothPixmapTransform)

    if not solo_primer_plano:
        m = tam * 0.012
        rect = QRectF(m, m, tam - 2 * m, tam - 2 * m)
        r = tam * 0.23
        for i in range(10, 0, -1):  # sombra suave del cuadrado exterior
            p.setPen(Qt.NoPen)
            p.setBrush(QColor(0, 0, 0, 8))
            p.drawRoundedRect(rect.translated(0, tam * 0.010 + i * tam * 0.0009).adjusted(-i * 0.6, -i * 0.6, i * 0.6, i * 0.6), r, r)
        g = QLinearGradient(0, rect.top(), 0, rect.bottom())
        g.setColorAt(0.0, QColor("#0F1012"))
        g.setColorAt(0.33, QColor("#050506"))
        g.setColorAt(1.0, QColor("#000000"))
        p.setPen(Qt.NoPen)
        p.setBrush(QBrush(g))
        p.drawRoundedRect(rect, r, r)

    # marco cuadrado blanco (el aro de TVon, pero cuadrado)
    lado = tam * marco_lado
    grosor = tam * marco_grosor
    ext = QRectF((tam - lado) / 2, (tam - lado) / 2, lado, lado)
    interior = ext.adjusted(grosor, grosor, -grosor, -grosor)
    marco = QPainterPath()
    marco.addRoundedRect(ext, lado * 0.13, lado * 0.13)
    hueco = QPainterPath()
    hueco.addRoundedRect(interior, lado * 0.13 - grosor * 0.55, lado * 0.13 - grosor * 0.55)
    marco = marco.subtracted(hueco)
    gm = QLinearGradient(0, ext.top(), 0, ext.bottom())
    gm.setColorAt(0.0, QColor("#FFFFFF"))
    gm.setColorAt(1.0, QColor("#E7EAF0"))
    p.setPen(Qt.NoPen)
    p.setBrush(QBrush(gm))
    p.drawPath(marco)

    # letras blancas con sombra tenue
    p.setPen(Qt.NoPen)
    for i in range(6, 0, -1):
        p.setBrush(QColor(0, 0, 0, 40))
        p.drawPath(cam.translated(0, tam * 0.004 + i * tam * 0.0008))
    gl = QLinearGradient(0, cam.boundingRect().top(), 0, cam.boundingRect().bottom())
    gl.setColorAt(0.0, QColor("#FFFFFF"))
    gl.setColorAt(1.0, QColor("#F1F3F8"))
    p.setBrush(QBrush(gl))
    p.drawPath(cam)
    p.end()
    img.save(salida)
    return img


if __name__ == "__main__":
    opciones = {
        "segoe_bold": ("segoeuib.ttf", None),
        "segoe_black": ("seguibl.ttf", None),
        "bahnschrift": ("bahnschrift.ttf", QFont.Weight.Bold),
    }
    if MODO == "hoja":
        hoja = QImage(1536, 512, QImage.Format_ARGB32)
        hoja.fill(QColor("#8A8A8A"))
        q = QPainter(hoja)
        for col, (n, (arch, peso)) in enumerate(opciones.items()):
            q.drawImage(col * 512, 0, logo(arch, os.path.join(SALIDA, f"{n}.png"), tam=512, peso=peso))
        q.end()
        hoja.save(os.path.join(SALIDA, "hoja.png"))
        print("hoja lista")
    else:
        arch, peso = opciones[sys.argv[3] if len(sys.argv) > 3 else "segoe_bold"]
        logo(arch, os.path.join(SALIDA, "rite_logo_1024.png"), peso=peso)
        print("final listo")
