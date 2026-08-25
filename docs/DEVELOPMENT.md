# Guía de desarrollo

## Requisitos locales

- Android Studio con JBR incluido.
- Android SDK existente en `~/Library/Android/sdk`.
- Dispositivo/emulador API 26+ con soporte para live wallpaper.
- Para los casos prioritarios: Galaxy S25 Ultra y Galaxy Tab S9.

No instales un JDK alterno ni herramientas Android globales para arreglar configuración local.

## Comandos de referencia

El usuario ejecuta compilaciones y tests. Una sesión de agente no debe correrlos automáticamente.

```sh
# APK debug
./gradlew :app:assembleDebug

# Unit tests JVM existentes
./gradlew :app:testDebugUnitTest

# Instrumentación en dispositivo conectado
./gradlew :app:connectedDebugAndroidTest
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Configuración Gradle actual

| Elemento | Valor |
| --- | --- |
| Módulo | `:app` |
| applicationId | `dev.chofito.wallpurr` |
| minSdk | 26 |
| compileSdk / targetSdk | 37 |
| JVM | 11 |
| Kotlin | 2.2.10 |
| AGP | 9.3.1 |
| Room schema | 4 |

Las versiones completas viven en `gradle/libs.versions.toml`.

## Qué cubren los tests actuales

### JVM

- Matemática básica de `ImageLayout` para cover/contain y clamp.
- Recorrido básico de `ShuffleEngine`.

### Instrumentación

- Solo el test template de package/context.

No hay cobertura automatizada suficiente para:

- migraciones Room;
- importación/backup;
- WorkManager y tokens;
- notificación;
- lifecycle del wallpaper;
- EGL/MediaPlayer;
- Home/Lock/Both;
- behavior de Samsung.

Por eso una compilación verde no sustituye la matriz manual de [CRITICAL_PATHS.md](CRITICAL_PATHS.md).

## Flujo recomendado para una sesión nueva

1. Leer `README.md` y `AGENTS.md`.
2. Localizar el owner de la feature con la tabla de `AGENTS.md`.
3. Leer solo los documentos/rutas críticas aplicables.
4. Confirmar el estado actual del código con `rg` antes de proponer.
5. Hacer parches pequeños.
6. Revisar estáticamente imports, firmas, resources, ownership y migraciones.
7. Entregar al usuario comandos y casos manuales específicos; esperar sus resultados.

## Recetas de cambio

### Agregar un setting global

1. Define una key estable en `WallpurrRepository`.
2. Agrega getter/observer y setter.
3. Expón state y acción en el ViewModel correspondiente.
4. Usa el valor desde el owner runtime, no desde una pantalla global mutable.
5. Si afecta wallpaper actual, llama `refreshCurrentVisuals()` o `applyCurrentAndNotify()` según cambie o no el schedule.
6. Documenta el default; instalaciones existentes no tendrán una fila.

### Agregar un campo persistido

1. Modifica entity.
2. Sube versión Room.
3. Escribe migración con default compatible.
4. Regístrala en `AppContainer`.
5. Modifica backup DTO con default.
6. Modifica todos los mappers/imports/default entities.
7. Pide validación de instalación limpia y upgrade sobre datos existentes.

### Agregar un tipo de medio

Debes tocar como mínimo:

- `MediaKind`;
- detección/extensión/metadata/decode de `ImageFileStore`;
- entity/backup si requiere metadata;
- thumbnails y previews;
- editor;
- selección de pipeline en `WallpurrWallpaperService`;
- aplicación estática;
- reglas de schedule;
- ahorro de energía.

No modeles un nuevo medio como `IMAGE` si después necesita lifecycle o duración propia.

### Cambiar transformaciones

Mantén paridad en:

- `ImageLayout`;
- editor Compose;
- `TransformPreview`;
- Canvas live;
- `WallpaperApplier`;
- posiciones EGL;
- persistencia por orientación;
- backup.

Agrega unit tests a la matemática pura; el usuario los ejecutará.

### Cambiar notificación

- La UI es `RemoteViews`, no Compose.
- Actualiza layout compacto y expandido según corresponda.
- Mantén PendingIntents únicos y `FLAG_IMMUTABLE`.
- No muestres nombre de archivo; el estado de producto usa playlist/destino/progreso.
- Conserva `setOnlyAlertOnce`, silent y ongoing.
- No cambies channel id salvo que quieras crear un channel nuevo deliberadamente.
- El cuerpo debe incluir `EXTRA_OPEN_PLAYLIST_ID`; `MainActivity` consume el destino tanto en
  arranque frío como en `onNewIntent` y elimina el extra después de navegar.
- Verifica forma compacta, expandida y lockscreen con permiso concedido.

### Cambiar video live

Antes de editar, lee las secciones 4–7 de `CRITICAL_PATHS.md`.

- Evita mezclar trabajo GL en Main.
- Toda textura se crea/elimina en el hilo EGL.
- Pasa poster antes de player.
- Valida generation/session en callbacks.
- Define explícitamente ownership de cada bitmap.
- Pide logs de `MediaPlayer`, `BufferQueueProducer` y `WallpurrWallpaper`.
- Conserva el crossfade dentro de `TransformedVideoRenderer`; el servicio solo entrega frames de
  enlace cuando la ruta cambia entre Canvas y EGL.
- No programes frames con un `Handler` fijo: EGL y Canvas usan `Choreographer` y se detienen cuando
  el wallpaper deja de estar visible.

### Cambiar tiempos

No implementes countdown con actualizaciones por segundo dentro de la notificación. Actualmente se muestra la hora absoluta del siguiente cambio, que no necesita un timer visual.

Todo cambio de intervalos debe revisar:

- imagen/GIF;
- video por minutos;
- video por repeticiones;
- pausa;
- acción manual;
- ahorro de energía;
- restauración después de reboot.

## Validación estática sin compilar

Comprobaciones útiles que no ejecutan la app:

```sh
# Archivos y símbolos
rg --files app/src/main
rg -n "NombreDelSímbolo" app/src/main/java

# XML bien formado
xmllint --noout app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml

# Referencias de migrations
rg -n "MIGRATION_|version =" app/src/main/java/dev/chofito/wallpurr
```

Esto detecta problemas estructurales, pero no reemplaza Kotlin/Gradle.

## Checklist de compilación para el usuario

Si Android Studio reporta error:

1. Compartir el bloque desde `Task :app:compileDebugKotlin FAILED`.
2. Incluir archivo, línea y mensaje completos.
3. Evitar “limpiar todo” antes de revisar el primer error; errores posteriores suelen ser cascada.
4. Después del fix, volver a compilar desde Android Studio.

Para Room/KSP, el primer error en entity/migration suele ser más útil que los generados.

## Checklist manual de producto

### Biblioteca

- Importar imagen, GIF y video.
- Compartir uno y varios archivos desde Galería/WhatsApp/Files.
- Confirmar que borrar en Wallpurr no borra el original.
- Crear, renombrar, borrar carpeta y mover medios.
- Cambiar 2/3/4 columnas y scrollear con videos.

### Playlist

- Crear y agregar medios.
- Reordenar y restaurar orden.
- Barajar.
- Seleccionar “mostrar ahora”.
- Anterior desde el primer elemento y siguiente desde el último.
- Verificar espacio inferior para el FAB.

### Editor

- Perfiles vertical/horizontal diferentes.
- En tablet, repetir horizontal→vertical al menos diez veces, rápido y lento; el último perfil debe
  quedar centrado y una espera posterior no debe cambiarlo por una carga tardía.
- Durante esos giros, confirmar que el cambio de perfil use un fade corto y que nunca quede una
  transición pendiente al detenerse en la orientación final.
- Probar específicamente Cover vertical → Contain horizontal con backdrop blur: solo debe verse un
  holding frame center-crop y el fade final; nunca franja lateral, estiramiento ni doble transición.
- Contener/cubrir/estirar.
- Zoom, pan y rotación.
- Cinco colores, HEX y backdrop difuminado.
- Preview de lock blur/opacidad.
- Guardar y confirmar actualización inmediata del wallpaper actual.

Para validar el relleno de franjas, usar al menos un medio panorámico en vertical y uno vertical en
horizontal. Comparar `Desactivado`, `Color` y `Fondo difuminado` entre editor, preview completa y
wallpaper real. Repetir con imagen, GIF y video; al avanzar entre dos medios, el segundo nunca debe
mostrar el backdrop del primero ni un flash negro.

### Runtime

- Aplicar Home, Lock y Both desde selector del sistema.
- Secuencias entre todos los tipos de medio.
- Pausa/play y anterior/siguiente en notificación.
- Tap en Home habilitado/deshabilitado.
- Bloquear/desbloquear durante transición.
- Entrar/salir de ahorro con ambas políticas.
- Reboot y restauración del próximo cambio.

## Información útil al reportar bugs

Incluye:

- dispositivo, versión Android y One UI/launcher;
- destino aplicado (Home/Lock/Both);
- si Android creó live wallpaper en lock;
- tipo anterior y siguiente;
- fit/rotación/efectos;
- visibilidad (Home, lockscreen, shade, app abierta);
- pasos exactos y frecuencia;
- logcat filtrado;
- video o captura cuando sea visual.

## Definition of done por área

| Área | Evidencia mínima |
| --- | --- |
| UI pura | Compila + revisión visual teléfono/tablet |
| Persistencia | Compila + clean install + upgrade + round-trip backup |
| Shuffle/timing | Unit tests relevantes + espera/acciones manuales |
| Notificación | Compacta/expandida/lockscreen en dispositivo |
| Wallpaper estático | Home/Lock, ambas orientaciones |
| Live imagen/GIF | Transiciones, ocultar/mostrar y lock/unlock |
| Live video/EGL | Matriz completa y logcat sin surface/player errors |
| OEM workaround | Reproducción y evidencia en el dispositivo objetivo |
