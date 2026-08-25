# Wallpurr — instrucciones para agentes

Estas reglas aplican a todo el repositorio. El producto es **Wallpurr** y el package es `dev.chofito.wallpurr`; la carpeta en disco puede llamarse `wallpur`.

## Antes de modificar

1. Lee `README.md`.
2. Para cambios de arquitectura, wallpaper, video, rotación o persistencia, lee también:
   - `docs/ARCHITECTURE.md`
   - `docs/CRITICAL_PATHS.md`
   - `docs/DEVELOPMENT.md`
3. Inspecciona el código actual. Los documentos explican intención e invariantes, pero el código es la fuente final para firmas y comportamiento presente.
4. Mantén el alcance pedido. No aproveches un bug específico para rediseñar subsistemas no relacionados.

## Ejecución y validación

- Nunca ejecutes tests de forma autónoma. El usuario siempre ejecuta los tests.
- No compiles ni instales el APK salvo solicitud explícita. El usuario normalmente recompila desde Android Studio.
- Al entregar cambios, indica qué se revisó estáticamente y qué comandos/pruebas manuales debe ejecutar el usuario.
- No afirmes que algo está validado en Samsung, Android 16, Home o lockscreen sin evidencia de dispositivo compartida en esa sesión.
- Si el usuario pide compilar, usa el JBR de Android Studio y `~/Library/Android/sdk`; no instales otro JDK ni Android CLI.

Comandos de referencia, para ejecución explícita por el usuario:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

Este no es un proyecto Bun. Ignora defaults heredados de carpetas superiores.

## Invariantes del producto

- Nunca modifiques el archivo original elegido por el usuario. Importa una copia en `filesDir/images/` y aplica transformaciones al renderizar.
- Mantén soporte offline; no agregues red, analytics o servicios externos sin autorización explícita.
- Conserva perfiles de encuadre separados para `PORTRAIT` y `LANDSCAPE`.
- `ImageLayout` debe seguir siendo el contrato común de geometría. No implementes fórmulas diferentes en UI, Canvas y EGL.
- El estilo propio de un medio gana sobre el estilo de su playlist.
- Blur u oscurecimiento de lockscreen desactivan video animado en una instancia exclusivamente de bloqueo y usan un frame estático.
- La opción beta de relleno de `CONTAIN` no debe añadir decodes, bitmaps o draws extra cuando está desactivada.
- Home, Lock y Both son destinos de playlist; la propiedad real de cada pantalla la determina Android/OEM y debe reconciliarse.

## Arquitectura y propietarios

### Estado y datos

- `WallpurrRepository` es la fachada de Room, almacenamiento privado, shuffle, settings y backups.
- `WallpurrDatabase` está en versión 4. Toda modificación de entidades persistidas requiere:
  1. subir la versión;
  2. migración explícita;
  3. registrar la migración en `AppContainer`;
  4. actualizar DTOs JSON/ZIP con defaults compatibles;
  5. revisar importación y exportación.
- El `AppContainer` de `WallpurrApplication` es DI manual. No introduzcas un framework de DI para un cambio pequeño.

### Rotación

- `RotationController` es el único coordinador de anterior/siguiente, pausa/play, selección directa, timers, aplicación de lock y notificación.
- No crees contadores o timers paralelos en pantallas, servicio, receiver o worker.
- Los avances manuales no se encolan: `manualAdvanceInFlight` y el guard de 750 ms evitan ráfagas.
- Los workers deben validar tanto `expectedImageId` como `rotationToken` para no ejecutar schedules viejos.
- `WallpaperPulse` es una señal conflated y state-based. El consumidor vuelve a leer Room; no transporta un snapshot.

### Wallpaper y video

- `WallpurrWallpaperService` es código crítico y sensible a OEM. Mantén pequeños los parches.
- `loadGeneration` invalida cargas async antiguas. Comprueba la generación después de cada decode/suspensión costosa.
- `videoSessionId` invalida callbacks de `MediaPlayer` antiguos.
- `TransformedVideoRenderer` es dueño de su `HandlerThread`, EGL, `SurfaceTexture` y `Surface` de entrada. Todo GL se ejecuta en ese hilo.
- Un bitmap pasado como `initialFrame`, `initialBackdrop`, `frame` o `backdrop` al renderer transfiere ownership; el renderer lo recicla.
- Evita alternar productores Canvas y MediaPlayer directamente sobre el `SurfaceHolder`. En Samsung puede causar `BufferQueue already connected` o error `-38`.
- Para transiciones a video, presenta primero un poster/bridge mediante EGL y luego conecta `MediaPlayer`.
- No pauses un video preparado hasta que el primer frame haya sido presentado. El estado `videoFramePresented` existe por esa carrera.
- Libera `MediaPlayer`, `Surface`, `SurfaceTexture`, texturas, bitmaps y drawables exactamente una vez.

### UI y rendimiento

- Las cuadrículas solo deben reproducir videos completamente visibles y después de que termine el scroll.
- Conserva keys estables y evita decodes o creación de players dentro de recomposiciones.
- Previews y thumbnails pueden usar cache acotado; el wallpaper actual debe refrescarse cuando cambia su transform.
- Respeta `WindowInsets.safeDrawing`, especialmente para FABs y navegación de tablets.
- No agregues una dependencia grande para una capacidad que Android/Compose ya cubre de forma pequeña.

## Mapa rápido para cambios

| Solicitud | Empieza aquí | Revisa también |
| --- | --- | --- |
| Importar/compartir medios | `ImageFileStore`, `WallpurrRepository` | `MainActivity`, `ui/library` |
| Carpetas/biblioteca | `ui/library`, `LibraryViewModel` | DAOs, repository |
| Orden/aleatorio/anterior | `ShuffleEngine` | repository, `RotationController` |
| Intervalo o doble cambio | `RotationController` | scheduler, worker, service callback de video |
| Notificación | `RotationNotifier` | receiver, repository schedule |
| Encuadre/editor | `ImageLayout`, `EditorViewModel` | preview, static applier, EGL |
| Home/lock/both | `WallpaperApplier`, service | Apply flow, OEM flags |
| GIF/video live | service, `TransformedVideoRenderer` | `MutedSurfacePlayer` |
| Blur/oscurecimiento | `LockStyle`, renderer de lock | service y preview |
| Ahorro de energía | `PowerSaveMonitor`, controller | repository y service |
| Backup o esquema | repository, DTOs, entities | database migrations |

## Estilo de implementación

- Kotlin idiomático, coroutines estructuradas y Compose state hoisting.
- Prefiere cambios locales y reversibles.
- Usa nombres que distingan media source, frame estático, backdrop y destino de wallpaper.
- Documenta el motivo de workarounds OEM junto al código; evita comentarios que solo repitan la instrucción.
- No captures estado mutable viejo en callbacks. Valida renderer, player, session y generation antes de actuar.
- No bloquees `RotationController.commands` con decodes o `WallpaperManager.setBitmap`; usa las colas conflated existentes.

## Entrega

Incluye siempre:

- resultado implementado;
- archivos principales cambiados;
- riesgos o comportamiento OEM que sigue sin validar;
- pruebas concretas para Android Studio/dispositivo;
- confirmación de que no se compilaron ni ejecutaron tests, salvo que el usuario lo haya pedido.
