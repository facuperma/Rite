"""Banco de pruebas de «Prompt para Claude» (script, no unittest). Necesita Ollama.
Uso: python tests/banco_prompts.py [--repeticiones 3] [--modelo qwen3:8b] [--solo N,N]"""
import argparse
import re
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from rite_puente import fidelidad  # noqa: E402
from rite_puente.mateo import Mateo  # noqa: E402

NOTAS = [
 ("corta1", "Agregar un boton de compartir nota en `ui/Editor.kt` que use el intent ACTION_SEND con el texto."),
 ("corta2", "Che, que la papelera se vacie sola a los 30 dias. Ya esta en `Repositorio.limpiarPapeleraVieja`, solo falta llamarla al abrir."),
 ("corta_dudas", "Quiero un buscador en la lista de notas. No se si conviene filtrar en Room o en memoria."),
 ("media_info", "Che, estuve viendo y el autoguardado del editor tarda 400 ms despues de escribir, pero a veces pierdo el ultimo cambio si salgo rapido de la nota. Creo que pasa porque `salir()` guarda sin esperar el delay. Habria que guardar al toque en `salir()` y en `onPause`, en `ui/Editor.kt`. No quiero tocar el esquema de Room (version 2). Cuando termine tendria que poder escribir y volver atras al instante sin perder nada."),
 ("larga_dark", "Che, estuve pensando en lo del modo oscuro para Rite y la verdad es que lo quiero hacer bien. Mirá, hoy los colores de las notas salen de `PaletaNotas` en ui/Lista.kt y el tema general se define en MainActivity.kt, y la verdad es que no se ve nada bien de noche, se te quema la vista.\nEntonces la idea es que la app siga el modo del sistema (claro u oscuro) por defecto, pero que ademas yo pueda forzar uno u otro desde Ajustes (ui/Ajustes.kt), y que esa eleccion se guarde en la tabla ajuste con la clave `tema`, asi cuando cierro la app y la vuelvo a abrir queda como la deje.\nUn detalle importante: las notas de colores tienen que seguir viendose bien en oscuro, o sea con buen contraste. Pienso que habria que oscurecer los pasteles mas o menos al 60 por ciento, pero lo tendria que ver.\nAh, y no quiero tocar la base de datos ni subir la version de Room (que esta en la 2), porque ya me costo mucho que migrara bien. Tampoco quiero librerias nuevas, con lo que ya tiene Compose alcanza.\nCuando termine tendria que poder alternar el tema desde Ajustes y que persista al cerrar la app. Todavia no se si conviene un selector de tres opciones (sistema, claro, oscuro) o un switch simple, eso lo decidimos despues.\nGracias!"),
 ("sin_dudas_larga", "Necesito que el puente de Rite (servidor/rite_puente/servidor.py) tenga un endpoint GET /salud que devuelva JSON con version, uptime en segundos y si Ollama responde. No requiere token, pero solo debe contestar a IPs de la red local (ya existe ip_permitida). Tiene que ser rapido: timeout de 2 segundos para chequear Ollama, reusando Mateo.disponible. Agregar tests en servidor/tests/test_puente.py con el MateoFalso, uno con Ollama disponible y otro sin. Actualizar el README.md con una linea que explique el endpoint. No agregar dependencias, solo libreria estandar. El puerto sigue siendo 8787 y el HTTPS se mantiene. Cuando termine tienen que pasar los 21 tests existentes mas los nuevos."),
 ("informal_che", "che mira, la lista de notas anda lenta cuando tengo mas de 200 notas, se traba un toque al scrollear. creo que es porque `LazyColumn` en ui/Lista.kt recompone todo cada vez que cambia el estado de busqueda. capaz hay que usar `key` y `remember` mejor. no se bien cual es el problema real, habria que medir. lo que si quiero es que siga andando con 1000 notas sin trabarse, y que no cambie el diseño de las tarjetas. gracias!"),
 ("codigo_rutas", "En `C:\\Users\\Faierwall\\Documents\\rite\\servidor\\rite_puente\\config.py` la funcion `carpeta_datos` devuelve %LOCALAPPDATA%\\Rite pero en Linux deberia devolver ~/.local/share/rite. Usar `sys.platform` para decidir. Los tests de `test_puente.py::ProyectosTest` no deben romperse. Version minima de Python: 3.11."),
 ("numeros_versiones", "Actualizar Gradle de 8.9 a 8.11.1 y el plugin de Android (AGP) de 8.7.2 a 8.7.3. Compose BOM sigue en 2024.10.01. El minSdk queda en 26 y el targetSdk en 35. Si el build falla por Kotlin 2.0.21, no tocar nada mas y avisarme. Tiene que compilar `assembleDebug` y pasar `testDebugUnitTest` (20 tests)."),
 ("dudas_explicitas", "Quiero que Mateo resuma automaticamente cada 10 mensajes del chat y guarde el resumen en la tabla `resumen`. Para el modelo, ¿conviene qwen3:8b o el 14b? Tambien, ¿el resumen se guarda en la app o en el servidor? Y no se si el tope de contexto deberia ser 2200 caracteres como en Ideario. Lo importante es que el resumen sea corto y no invente datos."),
 ("lista", "Pendientes de la etapa 2:\n- carpetas anidadas en la lista\n- arrastrar notas entre carpetas\n- exportar una nota a Markdown (`NotaMarkdown.kt`)\n- importar desde Samsung Notes si se puede\n- icono propio de la app (192x192 y 512x512)\nPrioridad: primero Markdown, despues carpetas. No tocar `BaseRite.kt` salvo que haga falta migrar (hoy version 2)."),
 ("mezcla_en_es", "Necesito agregar retry con exponential backoff al `Cliente.llamar` in red/Cliente.kt. Max 3 retries, base delay 500 ms, solo para IOException (no para ErrorPuente). Tiene que loguear cada intento con Log.w. No cambiar la firma publica de `suspend fun estado()`. Add a unit test with MockWebServer if it is already in the dependencies, otherwise skip it."),
 ("repetida", "Hay que arreglar el boton Mateo. El boton Mateo no responde si no hay red. El boton Mateo deberia mostrar un mensaje claro si no hay red, algo como «No llego a la PC». Hoy el boton Mateo se queda cargando para siempre. Entonces: el boton Mateo debe mostrar error de red en menos de 8 segundos (el connectTimeout de `Cliente` ya es 8). El mensaje esta en `Cliente.kt`, en `llamar`. Repito: el boton Mateo tiene que mostrar el mensaje de red, no quedarse cargando."),
 ("larga_estado", "Quiero rehacer la pantalla de Ajustes de Rite. Hoy en `ui/Ajustes.kt` hay solo un boton para vincular la PC y otro para desvincular, y se ve feo. La idea es tener secciones: PC vinculada (nombre, direccion, estado del puente con el indicador verde o rojo segun `estadoPuente()`), Apariencia (tema, tamaño de letra: chica, normal, grande), Papelera (dias para vaciar, hoy 30, con opciones 7, 14, 30, 60), Datos (exportar todo a un .zip, importar desde un .zip) y Acerca de (version de la app 1.0 y del puente). Todo tiene que guardarse en la tabla ajuste con claves `tema`, `fuente`, `papelera_dias`. Los textos en español rioplatense. No usar librerias nuevas ni cambiar la version de Room (2). El zip de exportacion tiene que contener un `notas.json` y una carpeta `adjuntos/` aunque este vacia. Importante: importar nunca debe pisar notas existentes con el mismo id sin preguntar, y hay que mostrar cuantas notas se importaron. Como no estoy seguro de si el zip va cifrado, dejalo para despues. Cuando termine tienen que pasar `testDebugUnitTest` y compilar `assembleDebug`."),
 ("tecnico_log", "El puente tira 'RemoteDisconnected: Remote end closed connection without response' en `test_prompt_endpoint` cuando Mateo falso tarda. Pasa solo a veces (1 de 5). Sospecho del `ThreadingHTTPServer` y `protocol_version = HTTP/1.1` con keep-alive en `servidor.py`: si el cliente cierra antes de que respondamos, `wfile.write` revienta. Probar con `Connection: close` en `_responder`. Medir antes y despues corriendo los tests 20 veces."),
 ("pocas_palabras", "Que Rite abra directo en la ultima nota editada."),
 ("ux", "La barra de arriba del editor ya tiene muchos botones (Mateo, Prompt para Claude, Versiones, estrella, papelera) y en pantallas chicas se cortan. Quiero agrupar Mateo y Prompt en un menu desplegable con el icono de varita, dejando afuera solo la estrella y la papelera. Los textos de las opciones del menu: «Organizar con Mateo» y «Prompt para Claude». Versiones va dentro de un menu de tres puntos."),
]


def evaluar(nota, r):
    p = r["prompt"]
    lits = fidelidad.literales(nota)
    cons = [x for x in lits if x not in fidelidad.faltantes(nota, p)]
    pct = 100.0 if not lits else 100.0 * len(cons) / len(lits)
    cuerpo = re.sub(r"Preguntas abiertas:\n(?:- .*\n?)*", "", p)  # las dudas del propio texto van tal cual
    tec = fidelidad.invenciones(nota, cuerpo)
    sug = r.get("preguntas_sugeridas", [])
    sug_ok = len(sug) <= 3 and all(q.endswith("?") and len(q) <= 140 and q not in p for q in sug)
    return len(p) / len(nota), pct, tec, sug, sug_ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repeticiones", type=int, default=3)
    ap.add_argument("--modelo", default="qwen3:8b")
    ap.add_argument("--solo", default="")
    ap.add_argument("--ver", action="store_true", help="mostrar los prompts")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    m = Mateo(modelo_prompt=a.modelo, timeout=600)
    sel = {x for x in a.solo.split(",") if x}
    print(f"{'nota':16}{'antes':>6}{'desp':>6}{'ratio':>7}{'lit%':>6}{'sug':>4}{'tec+':>5}{'cand':>5}{'seg':>6}  inventadas")
    tot, largas, fallos = [], [], 0
    for nombre, nota in NOTAS:
        if sel and nombre not in sel:
            continue
        for _ in range(a.repeticiones):
            t = time.time()
            r = m.armar_prompt(nota)
            seg = time.time() - t
            ratio, pct, tec, sug, sug_ok = evaluar(nota, r)
            mal = ratio > 1.0 or pct < 100 or tec or not sug_ok
            fallos += bool(mal)
            tot.append(ratio)
            if len(nota) >= 500:
                largas.append(ratio)
            print(f"{nombre:16}{len(nota):6}{len(r['prompt']):6}{ratio:7.2f}{pct:6.0f}{len(sug):4}{len(tec):5}{r['intentos']:5}{seg:6.1f}  {','.join(tec)}{' <-- MAL' if mal else ''}{' [compactada]' if r['advertencias'] else ''}")
            if a.ver:
                print("   [" + r.get("resumen_iteracion", "") + "]")
                for q in sug:
                    print("   ? " + q)
                print("   " + r["prompt"].replace("\n", "\n   "))
    print(f"\nratio medio={statistics.mean(tot):.2f} max={max(tot):.2f}; notas>=500: medio={statistics.mean(largas) if largas else 0:.2f}; corridas con falla={fallos}/{len(tot)}")


if __name__ == "__main__":
    main()
