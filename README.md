# TV Navegador

Navegador web para **Android TV** manejable por completo con el mando a
distancia. El mismo APK funciona también en móvil y tablet Android.

Es un navegador de propósito general: carga la web que se le indique y no
conoce ni interpreta el contenido de ningún sitio concreto.

## Instalación en Android TV

1. Descarga `tv-navegador.apk` desde la pestaña **Actions** del repositorio
   (artefacto `tv-navegador-apk` de la última ejecución correcta).
2. En la TV: **Ajustes → Sistema → Info → Compilación** (pulsa 7 veces para
   activar Opciones de desarrollador) y activa **Orígenes desconocidos**.
3. Copia el APK a la TV con una app tipo *Send Files to TV*, *X-plore* o
   `adb install tv-navegador.apk` desde el ordenador.
4. La app aparece en la fila de aplicaciones del lanzador de Android TV.

## Manejo con el mando

| Tecla | Acción |
|---|---|
| D-pad | Mueve el puntero (mantén pulsado para acelerar) |
| OK | Clic en la posición del puntero |
| ATRÁS | Página anterior |
| ATRÁS (mantener) | Abre el menú |
| PLAY / PAUSA | Reproduce o pausa el vídeo |
| AVANCE / RETROCESO | Salta 10 segundos |

Con el vídeo a pantalla completa, izquierda y derecha saltan en el tiempo y
OK reproduce o pausa.

## Menú

Se abre manteniendo **ATRÁS** (o con la tecla MENU si el mando la tiene):

- Ir a una dirección o buscar
- Marcadores: añadir, abrir y eliminar
- Página de inicio: ir y fijar la actual
- Recargar, atrás, adelante
- Modo del mando: **puntero** o **desplazamiento**
- Vista **escritorio** o **móvil** (cambia el user-agent)
- Zoom de la página, del 50 % al 200 %
- Reproducir/pausar y forzar pantalla completa del vídeo
- Borrar cookies y datos de navegación

## Notas técnicas

- `minSdk 21`, `compileSdk 35`. Cubre Android TV 5.0 en adelante.
- WebView configurado para webs modernas: JavaScript, DOM storage, cookies
  propias y de terceros, contenido mixto, `window.open` redirigido a la misma
  vista, vídeo HTML5 con pantalla completa y reproducción sin gesto previo.
- Se concede `PROTECTED_MEDIA_ID` para que funcione el vídeo con DRM.
- La página de inicio, el zoom y el modo del mando se guardan entre sesiones.

## Compilar

El proyecto se compila en GitHub Actions en cada push. Para hacerlo en local
hace falta el SDK de Android:

```bash
./gradlew assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```
