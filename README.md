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
| D-pad | Salta entre los elementos de la web |
| OK | Activa el elemento marcado |
| ATRÁS | Página anterior |
| ATRÁS (mantener) | Abre el menú |
| PLAY / PAUSA | Reproduce o pausa el vídeo |
| AVANCE / RETROCESO | Salta 10 segundos |
| AMARILLO / INFO | Cambia de modo del mando al vuelo |

El elemento con el foco se marca con un recuadro azul y la página se
desplaza sola para mantenerlo a la vista, como en una app nativa de TV.

Con el vídeo a pantalla completa, izquierda y derecha saltan en el tiempo y
OK reproduce o pausa.

### Cambiar de modo

Tres formas, de más rápida a más explícita:

1. Pulsa la tecla **AMARILLA** o **INFO** del mando: rota entre los tres modos
   y muestra en pantalla en cuál has entrado.
2. Mantén **ATRÁS** y pulsa OK: «Modo del mando» es la **primera** entrada del
   menú.
3. Desde esa entrada se abre un diálogo donde elegir el modo concreto.

El modo elegido se recuerda entre sesiones.

### Reproductores incrustados

Cuando el vídeo viene en un iframe de otro dominio, la política de mismo
origen impide que la app entre en él o gobierne ese vídeo por JavaScript. En
ese caso, al pulsar OK sobre el reproductor el mando pasa a ser suyo: el
D-pad y las teclas multimedia llegan directas al reproductor, que las maneja
por su cuenta. **ATRÁS** devuelve el mando a la página.

Si ese reproductor no responde al teclado, queda el modo **puntero**, que
sintetiza toques y sí funciona sobre sus controles.

### Modos del mando

- **Elementos** (por defecto): el D-pad recorre enlaces, botones, tarjetas y
  campos de la página eligiendo el destino por geometría. Si no hay ningún
  elemento en esa dirección, desplaza la página.
- **Puntero**: mueve un cursor de ratón por la pantalla, útil en webs con
  zonas activas que no son elementos reconocibles.
- **Desplazamiento**: deja el comportamiento nativo del WebView.

## Menú

Se abre manteniendo **ATRÁS** (o con la tecla MENU si el mando la tiene):

- Ir a una dirección o buscar
- Marcadores: añadir, abrir y eliminar
- Página de inicio: ir y fijar la actual
- Bloqueador de anuncios y ventanas emergentes, con el recuento de
  peticiones bloqueadas
- Recargar, atrás, adelante
- Modo del mando: **elementos**, **puntero** o **desplazamiento**
- Vista **escritorio** o **móvil** (cambia el user-agent)
- Zoom de la página, del 50 % al 200 %
- Reproducir/pausar y forzar pantalla completa del vídeo
- Borrar cookies y datos de navegación
- Diagnóstico de navegación: informa de cuántos elementos detecta el motor
  en la página, su reparto por etiqueta y cuál tiene el foco

## Bloqueador

Activado por defecto. Actúa en cuatro frentes:

- **Red**: `shouldInterceptRequest` compara cada petición contra una lista de
  105 dominios de publicidad, rastreo y popunder, por sufijo de dominio, y
  devuelve una respuesta vacía en las que coinciden. La lista no incluye CDNs
  ni dominios de reproducción, para no romper el vídeo.
- **Ventanas emergentes**: `onCreateWindow` las rechaza de raíz, de forma que
  la página actual se queda donde está — que es justo lo que rompe los
  popunder.
- **`window.open`**: se sustituye por una función que devuelve un objeto
  inerte, para que el script que la llama no falle y la página siga
  funcionando.
- **Redirecciones**: una navegación hacia un dominio de la lista se corta
  antes de cargarse.

Además oculta los huecos publicitarios que quedan vacíos, con una lista de
selectores deliberadamente conservadora para no ocultar contenido legítimo.

Se puede desactivar desde el menú si alguna web lo necesita.

## Notas técnicas

- `minSdk 21`, `compileSdk 35`. Cubre Android TV 5.0 en adelante.
- WebView configurado para webs modernas: JavaScript, DOM storage, cookies
  propias y de terceros, contenido mixto, `window.open` redirigido a la misma
  vista, vídeo HTML5 con pantalla completa y reproducción sin gesto previo.
- Se concede `PROTECTED_MEDIA_ID` para que funcione el vídeo con DRM.
- Los candidatos pequeños que caen dentro de otro mayor (menos del 30 % de
  su área, con el 75 % de la suya dentro) se descartan: son los botones
  superpuestos sobre las tarjetas, que superan en número a las tarjetas y
  acaparaban el foco. La comprobación es geométrica, no de árbol, para pillar
  también los posicionados en absoluto.
- La pasada por `cursor: pointer` solo se ejecuta si la web expone menos de
  25 candidatos reales. Obliga a un `getComputedStyle` por elemento y en las
  webs con enlaces y botones de verdad no aporta nada.
- La navegación por elementos se inyecta como JavaScript en cada carga y en
  las navegaciones de las webs de una sola página. Descubre los candidatos
  por selector (enlaces, botones, controles, roles ARIA) y por heurística de
  `cursor: pointer`, descarta contenedores que envuelven a otros candidatos y
  puntúa el destino por distancia y alineación en el eje perpendicular.
- La página de inicio, el zoom y el modo del mando se guardan entre sesiones.

## Compilar

El proyecto se compila en GitHub Actions en cada push. Para hacerlo en local
hace falta el SDK de Android:

```bash
./gradlew assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```
