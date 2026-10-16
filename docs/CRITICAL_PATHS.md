# Rutas críticas, invariantes y diagnóstico

Wallpurr combina lifecycle de wallpaper, Room, WorkManager, múltiples engines, MediaPlayer y EGL. Un cambio pequeño puede funcionar en preview y fallar únicamente al bloquear un Samsung. Este documento señala dónde ser conservador.

## Regla general

Antes de cambiar una ruta crítica, responde:

1. ¿Quién es la fuente de verdad?
2. ¿Quién posee el recurso y quién lo libera?
3. ¿Cómo se invalida una operación async anterior?
4. ¿Este evento puede llegar dos veces desde fuentes diferentes?
5. ¿Funciona distinto si el engine representa Home, Lock o ambas?
6. ¿La ruta sin blur/opacidad sigue evitando trabajo adicional?

## 1. Una sola lógica de avance

### Invariante

Todo avance pasa por `RotationController`.

Fuentes válidas:

- notificación: anterior/siguiente;
- tap en Home;
- `LockRefreshWorker`;
- finalización de un video configurado por repeticiones;
- selección directa desde la playlist.

No añadas un `Handler`, countdown o timer que también modifique el cursor desde UI o servicio.

### Protecciones existentes

- `commands: Mutex`: serializa operaciones lógicas.
- `manualAdvanceInFlight`: descarta una ráfaga en curso.
- `MANUAL_ADVANCE_GUARD_MS = 750`: evita doble evento casi simultáneo.
- `expectedImageId`: un callback solo avanza el medio que esperaba.
- `rotationToken`: invalida un WorkManager anterior.
- unique work `REPLACE`: solo queda el schedule más reciente.

### Si cambia dos veces seguido

Revisa, en este orden:

1. logs de `WallpurrRotation`;
2. si llegaron dos acciones de receiver/tap;
3. si el video completó al mismo tiempo que un worker;
4. `nextChangeAt`, token y expected id persistidos;
5. que ningún código nuevo llame directamente `advanceShuffle` y después al controller.

## 2. Pausa significa pausa

`pause()` guarda el flag, invalida schedule y emite reload. Tanto workers como callbacks esperados consultan `isRotationSuspendedLocked()`.

Al agregar un origen de avance:

- debe usar controller;
- debe respetar pausa;
- debe respetar ahorro `STATIC`;
- no debe volver a programar si la rotación está suspendida.

El play/pause de la notificación controla rotación, no necesariamente el frame interno de un video ya visible. La reproducción del medio también depende de visibilidad y settings.

## 3. Ciclo y selección directa

`ShuffleState` separa historial, actual y pendientes. No reemplaces el cursor por un índice aislado.

Comportamientos que deben conservarse:

- siguiente desde el último vuelve al primero;
- anterior desde el primero va al último;
- seleccionar el medio N conserva los anteriores como historial y los posteriores como pendientes;
- barajar modifica el ciclo persistido;
- “orden de lista” reconstruye el ciclo con `sortOrder`.

Los cambios de miembros (agregar, quitar, borrar medios) **no** reinician el cursor:
`ShuffleEngine.reconcile` elimina ids obsoletos, agrega los nuevos al final de los pendientes
(conservando un orden ya barajado) y mantiene el medio actual. Reiniciar el cursor movía el wallpaper
y dejaba huérfano al worker pendiente, que apunta al medio actual: la rotación se detenía en silencio.
Reordenar convierte el nuevo orden en ciclo, pero también conserva el medio actual.

## 4. Ownership del wallpaper `Surface`

### Problema

Un `SurfaceHolder` de wallpaper usa un `BufferQueue`. Canvas, EGL y MediaPlayer son productores distintos. En Samsung, desconectar uno y conectar otro rápidamente puede provocar:

- flash negro;
- wallpaper que no cambia hasta bloquear/desbloquear;
- `BufferQueue ... already connected`;
- MediaPlayer `what=1`, `extra=-38` o estado inválido.

### Estrategia actual

- Canvas usa un handle local obtenido por Parcel, no retiene directamente el handle del holder al cambiar a video.
- El renderer EGL posee el window surface de salida.
- MediaPlayer escribe a una `SurfaceTexture` de entrada propiedad del renderer.
- Imagen→video presenta un poster con EGL antes de preparar el player.
- Video→video conserva EGL y reemplaza el player.
- Video→imagen sube la imagen a una textura 2D sin devolver el output surface a Canvas cuando es posible.

### Prohibiciones prácticas

- No llames `MediaPlayer.setSurface(surfaceHolder.surface)` en paralelo a Canvas.
- No liberes la `Surface` de entrada desde el servicio; pertenece al renderer.
- No recrees EGL por cada medio sin una razón comprobada.
- No publiques un canvas negro “mientras carga”; conserva el último buffer visible.

En tablet, el tamaño entregado por `onSurfaceChanged` puede quedar transitoriamente detrás del
tamaño real del window surface EGL. El renderer mantiene ambos conceptos separados: `ImageLayout`
usa el viewport lógico confirmado por el engine y `glViewport`/las capturas usan el tamaño físico de
`EGL_WIDTH`/`EGL_HEIGHT`. Reemplazar el primero con el segundo aplica el recorte de una generación
transitoria y acumula zoom al alternar orientaciones. Después de una actualización se programan
reconciliaciones cortas para cubrir el buffer completo sin modificar la geometría persistida. La
consulta por frame queda limitada a una ventana corta después del resize; no permanece activa
durante toda la reproducción del video.

## 5. Ownership de bitmaps y recursos GL

### Servicio/Canvas

El engine posee `bitmap`, `gif` y `containBackdrop` hasta reemplazarlos o liberar media.

### Renderer

Transferir cualquiera de estos argumentos transfiere ownership:

- `initialFrame`
- `initialBackdrop`
- `presentBitmap(frame, backdrop)`
- `updateLayout(..., backdrop)`
- `updateBitmapLayout(..., backdrop)`

El renderer sube la textura y recicla el bitmap en su hilo. El caller no debe reciclarlo después de una transferencia exitosa.

Checklist de cambios:

- cada `Bitmap` tiene un único owner en todo momento;
- caminos de cancelación reciclan lo que todavía no fue adoptado;
- texturas viejas se eliminan después de subir reemplazo;
- cleanup ocurre con EGL current antes de destruir el context;
- drawable animado se detiene y pierde callback al salir.

## 6. Cargas y callbacks obsoletos

`loadCurrent()` puede suspender durante Room, decode, blur o release de renderer. Mientras tanto puede llegar otro pulse o destruirse el surface.

### `loadGeneration`

Se incrementa al iniciar load y al destruir surface. Después de una suspensión costosa:

```kotlin
if (!isCurrentLoad(generation)) {
    // liberar resultado no adoptado
    return
}
```

### `videoSessionId`

Cada player/reemplazo recibe una sesión. Todos los listeners validan:

- renderer esperado;
- player esperado;
- session id;
- surface activo.

No captures `image`/`file` viejos en un callback que puede sobrevivir al medio. Cuando falle un renderer persistente, vuelve a leer repository con `loadCurrent()`.

En video→video, layout, backdrop, captura saliente y barrera del input surface pertenecen a una
sola llamada de `prepareForNextVideo()`. Separarlas vuelve a introducir una transición corta y una
copia GPU redundante antes del crossfade real.

Durante rotación no renderices el transform anterior con el viewport nuevo. `prepareForLayoutChange`
conserva la composición anterior, los callbacks OES solo latchean y el target se crea cuando llega el
perfil final. El holding frame se muestra con center-crop, nunca estirado ni alineado a una esquina.
`layoutChangePending` solo se activa si la captura GPU fue creada correctamente. Si las animaciones
están deshabilitadas o la textura no cabe, continúa por el camino directo: nunca esperes un holding
frame que no existe.

La captura saliente de orientación siempre se genera desde la escena residente canónica, no desde
el bitmap/textura aplanado de una transición anterior. Volver a capturar ese holding frame aplica
`center-crop` sobre `center-crop` y acumula zoom después del primer giro, tanto en Canvas como EGL.

El perfil final también lleva su `ScreenOrientation`. No completes el target solamente porque llegó
un ancho/alto: `SurfaceHolder` y EGL pueden estar mostrando generaciones distintas. Cuando la
orientación esperada es conocida, nunca aceptes por timeout un EGL con la orientación opuesta. En
Samsung, BLAST puede publicar primero el nuevo `SurfaceHolder`; presenta nuevamente el holding frame
para forzar el dequeue/swap de la nueva generación y confirma el target solo cuando EGL coincida.
El fallback de Surface estable queda reservado para rutas donde no existe un perfil esperado.

Comprueba `requiredDecodeMaxSide` antes de redibujar un medio residente. Si la textura/bitmap no
alcanza para el nuevo zoom, conserva el holding anterior, decodifica primero y publica una sola vez.
Aplicar un layout de baja resolución y luego reemplazarlo crea dos transiciones y permite que una
captura intermedia se reutilice como origen del siguiente giro.

Cuando Lock usa `WallpaperManager` estático, aplica primero la solicitud conflated más reciente y
solo después emite el reload live si playlist e imagen siguen coincidiendo. Emitir Home antes vuelve
a crear un intervalo donde ambos destinos muestran elementos distintos.

## 7. Primer frame y pausa de video

En algunos dispositivos `onPrepared` ocurre antes de que exista un frame presentable. Pausar inmediatamente puede lanzar error `-38` y dejar una superficie negra.

Flujo obligatorio:

1. Player preparado.
2. Arranque temporal si hace falta.
3. `MEDIA_INFO_VIDEO_RENDERING_START` arma callback.
4. Renderer descarta el estado latched anterior, toma el buffer más reciente de `SurfaceTexture` y
   hace swap; si aún no es observable, espera el callback real del frame.
5. `onFirstVideoFramePresented` marca el frame.

El precompuesto Canvas de una transición también valida `loadGeneration` después de cada blur y
la identidad del frame saliente antes de adoptar el destino. No retires esos guards: el trabajo CPU
puede terminar después de un siguiente manual o de varios giros de tablet.
6. Solo entonces se permite pausar por invisibilidad.

No simplifiques `videoPrepared`, `videoFramePresented`, `pauseAfterFirstVideoFrame` y `weBelieveStarted` sin validar en dispositivo.

### Crossfade y primer frame

- La transición de video no empieza cuando `MediaPlayer` está preparado, sino después del primer
  frame OES realmente latched.
- `prepareForNextVideo` conserva la escena saliente y arma una transición pendiente.
- `armFirstVideoFrameCallback`/`presentLatchedVideoFrame` capturan la escena entrante y recién ahí
  arrancan los 750 ms.
- No reemplaces esto por dos `MediaPlayer`: duplica decoder, memoria y riesgo de `BufferQueue`.
- Una transición interrumpida se vuelve a capturar desde la composición visible actual; no se
  encolan fades ni se conservan texturas de destinos obsoletos.

## 8. Home, Lock y engine compartido

El target de playlist indica intención; `wallpaperFlags` indica el destino real del engine.

Casos posibles:

- engine Home;
- engine Lock;
- dos engines separados;
- un engine compartido Home+Lock;
- preview engine.

Solo un engine participa como owner de avance de videos para evitar que dos instancias completen el mismo medio y avancen dos veces.

### Efectos en engine compartido

No difumines permanentemente el source. Un bitmap horneado con blur de lock también aparecería en Home. El estilo se decide en draw con `visibleLockStyle()` según keyguard.

Los broadcasts screen on/off/user present solicitan redraw porque el mismo engine puede cambiar de apariencia sin cambiar de medio.

## 9. Video y GIF en lockscreen

La animación real solo existe si Android/OEM creó un engine live que incluya Lock.

En engine exclusivamente Lock:

- video requiere `play_video_on_lock = true`;
- blur u oscurecimiento fuerzan frame estático;
- GIF con blur usa frame estático;
- sin instancia live, `WallpaperApplier` aplica un bitmap.

No prometas animación lock universal. `WallpaperManager.setBitmap` nunca reproduce un video o GIF.

## 10. Blur y oscurecimiento

Prioridad:

```text
override del medio > estilo de playlist
```

Oscurecimiento es un overlay negro uniforme en toda la pantalla; no un gradiente de bordes.

Ruta rápida 0/0:

- no crear `RenderNode`;
- no ejecutar blur CPU;
- no convertir video/GIF a estático por efectos;
- no duplicar bitmaps.

Android 12+ usa un solo `RenderEffect` a la resolución original del Surface y deja sus pasadas
internas a Skia. No desatura. Para evitar posterización, aplica un dithering neutro de alta frecuencia
y aproximadamente un nivel de 8 bits antes del dim uniforme. El fallback CPU también conserva la
resolución original y aplica tres pasadas con dithering ordenado de un solo nivel. El nivel persistido
es intensidad 0–20; no es un radio literal y los valores 0–10 mantienen su fuerza anterior.

## 11. Relleno de `CONTAIN`

Se persiste dentro de cada transform/orientación.

- `NONE`: franjas actuales, cero backdrop extra.
- `COLOR`: un clear/fill sólido.
- `BLUR`: segunda capa de la misma media, reducida, difuminada, `COVER`, zoom 1.25.

Para GIF/video, la paleta y el backdrop usan un frame representativo. El foreground animado sigue separado.

El editor genera el backdrop con el mismo procesador reducido del runtime. Si el decode o el blur
fallan, todas las rutas usan `containBackgroundColor`; EGL debe descartar la textura del medio
anterior en vez de conservarla accidentalmente.

Al tocar esta función, revisa las cuatro implementaciones:

1. editor/preview Compose;
2. Canvas live;
3. EGL video/still;
4. `WallpaperApplier` estático.

## 12. Cambios de transform y cache

Editar posición, zoom, fit, rotación, color o efectos debe refrescar el wallpaper actual sin avanzar ni reiniciar su timer.

`EditorViewModel.persistTransform()` usa `rotation.refreshCurrentVisuals()`, que aplica y emite reload sin reconstruir metadatos de notificación.

Una cache solo es válida si su key incluye todo lo que cambia el resultado. Para transform/effects:

- `MediaDerivativeCache` incluye huella del original, viewport, orientación, transform completo,
  densidad, blur y opacidad en la key del render;
- al guardar transform o estilo, eliminar los renders del medio; al cambiar el estilo heredado de
  una playlist, eliminar los renders de sus medios que pudieran heredarlo;
- actualizar posiciones EGL;
- conservar/reemplazar backdrop según image id y modo;
- redibujar textura residente si no cambió el source.

Síntoma típico de cache incorrecta: el editor guarda el valor, pero el wallpaper solo cambia después de Next/Previous.

Los archivos derivados viven en `cacheDir`, nunca junto a los originales. Android puede borrarlos
cuando necesite espacio; una ausencia o fallo de escritura siempre cae al render normal. No se
precalcula toda la biblioteca: miniaturas y wallpapers finales se crean de forma perezosa para no
convertir una importación grande en una ráfaga de CPU, batería y almacenamiento.

Después de invalidar un transform o estilo hay 750 ms de calma sin escrituras de renders. Esto evita
comprimir un PNG de pantalla completa por cada paso de un slider; el wallpaper sí se refresca y el
resultado estable se guarda perezosamente en un uso posterior.

La rotación vertical↔horizontal también invalida el transform residente. No basta con recalcular el
viewport usando el mismo objeto: el engine debe leer de Room el perfil de la nueva orientación. Se
observan dos señales porque no todos los launchers actualizan igual el wallpaper:

- `Configuration.orientation` identifica el perfil vertical u horizontal;
- `onSurfaceChanged` actualiza las dimensiones reales usadas por Canvas/EGL.

Los giros de 180° permanecen en el mismo perfil. Si cambia el tamaño del surface, se redibuja con el
viewport nuevo, pero no se inventa un tercer o cuarto transform.

No uses únicamente `loadedOrientation == requestedOrientation` para ignorar un callback: puede
existir una carga opuesta todavía suspendida. Cada cambio de orientación deseada debe invalidar esa
carga aunque vuelva al perfil actualmente visible. Solo una recarga de orientación puede estar en
curso; al terminar, vuelve a comprobarse el último valor deseado.

## 13. Ahorro de energía

`PowerSavePolicy` configura la reacción de Wallpurr, no el estado de ahorro del teléfono.

Con `STATIC` y ahorro activo:

- rotación se considera suspendida;
- WorkManager actual se invalida;
- anterior/siguiente manual no actúa;
- se elige primera imagen de la lista, o primer medio congelado si no hay imagen;
- al salir, el monitor reconcilia y vuelve a programar.

No confundas el chip de notificación (estado real del sistema) con el selector de policy (preferencia del usuario).

## 14. Notificación

La notificación depende de:

- permiso `POST_NOTIFICATIONS` en Android 13+;
- channel `wallpurr-rotation-v3`, importancia default;
- ajustes del usuario para lockscreen;
- filtros OEM de Samsung.

Es ongoing y pública, pero no pertenece a un foreground service. No añadas código de anclaje invasivo sin confirmar que Android realmente lo requiere.

Al cambiar channel id, los ajustes anteriores del usuario no migran automáticamente. La configuración del channel es inmutable después de crearlo.

## 15. Room y backups

Nunca edites una entity persistida sin migración.

Checklist:

1. Entity actualizada.
2. `@Database(version = N+1)`.
3. `MIGRATION_N_N+1`.
4. `addMigrations(...)` en `AppContainer`.
5. DTO con default para backups viejos.
6. `toModel`, `toDto`, import entity y defaults.
7. Validación de upgrade sobre una instalación existente, no solo instalación limpia.

Si falta uno, puede compilar y aun así perder datos, fallar al abrir o importar configuración incompleta.

Nunca uses `OnConflictStrategy.REPLACE` sobre una tabla padre (`folders`, `playlists`, `images`).
SQLite implementa REPLACE como DELETE + INSERT y las foreign keys `CASCADE` borran en silencio los
hijos: imágenes de una carpeta, transforms, items de playlist. Usa insert-ignore + `@Update`
(`upsert` en los DAOs). REPLACE solo es aceptable en tablas hoja (transforms, items, settings, cursores).

Importar ZIP es atómico desde el punto de vista del usuario: los medios se extraen a
`cacheDir/import-staging/` con un presupuesto de bytes (espacio libre menos 512 MB), se exige un
`config.json` válido y solo entonces se mueven a `images/` los archivos nuevos. Un archivo existente
nunca se sobrescribe (los `storedName` son únicos por medio). Si falla la transacción de Room, se
eliminan los archivos recién movidos.

## 16. Cuadrículas y previews

Un player por tile visible es caro. La estrategia actual:

- poster estático por defecto;
- play solo para keys totalmente visibles;
- apagar players durante scroll;
- esperar 220 ms después de asentarse;
- limitar videos y GIFs en conjunto: dos en teléfono y uno en tablet;
- no iniciar ni terminar el encode de miniaturas nuevas durante un fling;
- miniatura persistente acotada en disco y cache de memoria de Coil;
- liberar player en `onDispose`/`onRelease`.

No reproduzcas todos los videos de la lista fuera del viewport aunque otras apps parezcan hacerlo; esas apps usan pools, prefetch y pipelines especializados.

## 17. Diagnóstico rápido

Tags relevantes:

```text
WallpurrWallpaper
WallpurrRotation
WallpurrPowerSave
WallpurrVideoGL
```

Filtro sugerido para que ejecute el usuario:

```sh
adb logcat -v time WallpurrWallpaper:V WallpurrRotation:V WallpurrPowerSave:V MediaPlayer:W BufferQueueProducer:W *:S
```

| Síntoma | Primer lugar a revisar |
| --- | --- |
| Dos cambios seguidos | Controller, worker token, final de video |
| Pausa pero sigue rotando | Origen que evita controller / schedule viejo |
| Negro al pasar a video | Surface ownership, bridge frame, first swap |
| Video aparece tras bloquear | primer frame/pause race, visibility callback |
| Lock negro tras reinstalar | chooser settle, flags reales, primer load |
| Blur de lock aparece en Home | engine compartido y efecto horneado |
| Transform no refresca | cache/key, `refreshCurrentVisuals`, resident texture |
| Scroll lageado | players visibles, decode en composición, keys inestables |
| FAB bajo navbar | `WindowInsets.safeDrawing` y consumo duplicado |
| Notificación no sale en Lock | permiso, channel y ajustes OEM |

## 18. Matriz mínima de validación manual

Para cambios de wallpaper/video, probar al menos:

- imagen→imagen;
- imagen→video;
- video→video;
- video→imagen;
- GIF→video y video→GIF;
- anterior/siguiente rápido desde notificación;
- tap rápido en Home;
- Home visible y app/notificación visible;
- bloquear/desbloquear;
- orientación vertical/horizontal;
- playlist Home, Lock y Both;
- blur/opacidad 0 y activos;
- ahorro ignore/static;
- reinicio del proceso o reinstalación si el cambio toca apply/restore.

En tablet, repetir al menos cambio de medio, scroll, orientación y safe areas.

Para transiciones, observar también que no exista frame negro al inicio/final, que el backdrop de
un medio no aparezca sobre el siguiente y que 10 acciones rápidas terminen en el último medio sin
una cola visual posterior.
