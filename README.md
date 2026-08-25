# Wallpurr

Wallpurr es un administrador offline de fondos para Android. Importa imágenes, GIF y videos, guarda una copia privada, permite configurar el encuadre por orientación y rota playlists sobre un live wallpaper. También puede aplicar un frame estático cuando Android o el fabricante no permiten el mismo comportamiento en la pantalla de bloqueo.

El nombre del producto y el package usan doble `r`: **Wallpurr** y `dev.chofito.wallpurr`. La carpeta del repositorio todavía puede llamarse `wallpur`.

## Estado del proyecto

El árbol contiene una aplicación funcional en desarrollo, no una release validada. La documentación describe lo implementado en código al 24 de agosto de 2026; no implica que cada ruta haya sido compilada o verificada en todos los dispositivos.

Dispositivos importantes para validación manual:

- Samsung Galaxy S25 Ultra, Android 16 / One UI 8.5.
- Samsung Galaxy Tab S9, especialmente rotación, rendimiento, barras del sistema y superficies de video.

Consulta [PROGRESS.md](PROGRESS.md) para el inventario de implementación y [LIMITATIONS.md](LIMITATIONS.md) para restricciones de Android/OEM.

## Capacidades actuales

- Biblioteca local con carpetas y cuadrícula configurable de 2 a 4 columnas.
- Importación desde Photo Picker, proveedores de archivos y `ACTION_SEND`/`ACTION_SEND_MULTIPLE` de otras apps.
- Copia propia en `filesDir/images/`; Wallpurr nunca edita el original.
- Playlists ordenables con recorrido secuencial o aleatorio, anterior/siguiente circular y selección directa de un elemento.
- Imágenes, GIF animados y videos sin audio.
- Crossfade estilo Muzei entre medios y suavizado corto al readaptar una orientación.
- Encuadre independiente en vertical y horizontal: contener, cubrir, estirar, zoom, posición y rotación.
- Relleno para las franjas de `Contener`: color extraído/HEX o segunda capa difuminada al 125 %.
- Estilo de bloqueo por playlist con override por medio: blur y oscurecimiento.
- Opción para reproducir video en una instancia live de bloqueo; los efectos de bloqueo fuerzan un frame estático.
- Rotación persistida con WorkManager, restauración después del reinicio y protección contra workers obsoletos.
- Notificación persistente personalizada con miniatura, playlist, destino, próximo cambio, posición y controles.
- Política de ahorro de energía: ignorar o congelar la playlist en contenido estático.
- Exportación/importación JSON y ZIP con los archivos privados.

## Comportamiento por destino

| Destino | Ruta preferida | Fallback / condición |
| --- | --- | --- |
| Inicio | `WallpurrWallpaperService` live | Bitmap estático desde la pantalla Aplicar |
| Bloqueo | Instancia live si Android/OEM la concede | `WallpaperManager.setBitmap(..., FLAG_LOCK)` |
| Ambas | Una o dos instancias según Android/OEM | Home live + lock estático cuando no existe live lock |

El selector del sistema decide dónde instala el live wallpaper. Wallpurr inspecciona las instancias reales y evita aplicar simultáneamente un bitmap estático sobre una pantalla que ya pertenece al live wallpaper.

## Stack

- Kotlin 2.2, Java 11.
- Jetpack Compose + Material 3 + Navigation Compose.
- Room 2.7, esquema actual v4.
- WorkManager para el siguiente cambio persistente.
- `MediaPlayer` + EGL/OpenGL ES para el wallpaper de video.
- Media3/ExoPlayer únicamente para previews de video en cuadrículas.
- Coil para miniaturas de biblioteca.
- `kotlinx.serialization` para backups.
- minSdk 26, compileSdk/targetSdk 37.

La aplicación no declara permiso de red y no necesita backend.

## Abrir y ejecutar

1. Abre esta carpeta en Android Studio.
2. Usa el JBR incluido con Android Studio y el SDK existente en `~/Library/Android/sdk`.
3. Sincroniza Gradle.
4. Ejecuta el módulo `app` en un dispositivo o emulador compatible con live wallpapers.

Comando equivalente para que lo ejecute una persona:

```sh
./gradlew :app:assembleDebug
```

APK esperado:

```text
app/build/outputs/apk/debug/app-debug.apk
```

No instales otro JDK ni Android CLI para este proyecto. Las sesiones de Codex no deben compilar ni correr tests salvo que el usuario lo solicite expresamente.

## Arquitectura en un vistazo

```text
Compose UI / ViewModels
        │
        ▼
WallpurrRepository ─── Room + filesDir/images + JSON/ZIP
        │
        ├── RotationController ─── WorkManager + notificación
        │              │
        │              └── WallpaperPulse (conflated)
        │
        ├── WallpaperApplier ─── bitmaps estáticos Home/Lock
        │
        └── WallpurrWallpaperService
                ├── Canvas: imágenes y GIF / efectos
                └── EGL + SurfaceTexture + MediaPlayer: video y stills rápidos
```

Principios centrales:

- Room y los archivos privados son la fuente de verdad; los pulsos solo indican “vuelve a leer el estado”.
- `ImageLayout` es el contrato geométrico compartido por editor, preview, Canvas, aplicación estática y EGL.
- `RotationController` es el único coordinador de avance, pausa, scheduling, aplicación y notificación.
- El renderer EGL conserva un único productor sobre el `Surface` para evitar flashes negros y conflictos de `BufferQueue`.

Lee [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) antes de cambiar flujos y [docs/CRITICAL_PATHS.md](docs/CRITICAL_PATHS.md) antes de tocar wallpaper, rotación o video.

## Mapa del código

| Área | Ubicación | Responsabilidad |
| --- | --- | --- |
| Entrada y DI manual | `MainActivity.kt`, `WallpurrApplication.kt` | Intents compartidos, Compose y `AppContainer` |
| UI | `ui/` | Pantallas Compose, navegación, previews y ViewModels |
| Persistencia | `storage/` | Room, archivos privados, metadata y backups |
| Geometría | `editor/` | Transformaciones y viewport de teléfono/tablet |
| Orden | `playlist/` | Estado circular de playlist y selección directa |
| Runtime | `wallpaper/` | Servicio live, EGL, aplicación estática, rotación y notificación |

## Documentación

- [AGENTS.md](AGENTS.md): reglas operativas para Codex y otros agentes.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): componentes, datos, concurrencia y renderizado.
- [docs/CRITICAL_PATHS.md](docs/CRITICAL_PATHS.md): invariantes, regresiones frecuentes y diagnóstico.
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md): setup, recetas de cambio y matriz de validación.
- [docs/BRAND.md](docs/BRAND.md): iconos, paleta y reglas de identidad visual.
- [PROGRESS.md](PROGRESS.md): snapshot de funcionalidades presentes en el árbol.
- [LIMITATIONS.md](LIMITATIONS.md): límites del sistema y trabajo aún no validado.

## Empezar otra sesión

Al abrir un chat nuevo en esta carpeta, `AGENTS.md` debe guiar al agente automáticamente. Si quieres fijar el alcance desde el primer mensaje, puedes usar:

```text
Trabaja en Wallpurr. Lee AGENTS.md y la documentación enlazada que aplique.
No compiles ni ejecutes tests; yo lo haré en Android Studio.
No afirmes validación en dispositivo sin mis resultados.
Tarea: <describe aquí el cambio o bug>.
```

Para un bug de runtime, agrega dispositivo, Android/One UI, destino Home/Lock/Both, medio anterior/siguiente y pasos exactos.

## Licencia

Copyright (C) 2026 Rodolfo Robles

Wallpurr es software libre: puedes redistribuirlo y/o modificarlo bajo los términos de la GNU General Public License versión 3 (GPL-3.0-only), publicada por la Free Software Foundation. Cualquier versión distribuida, modificada o no, debe conservar este aviso de copyright y publicar su código fuente bajo la misma licencia.

Wallpurr se distribuye con la esperanza de que sea útil, pero SIN NINGUNA GARANTÍA. Consulta [LICENSE](LICENSE) para el texto completo.

