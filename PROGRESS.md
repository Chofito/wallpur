# Estado de implementación

Snapshot del código: 24 de agosto de 2026.

Este archivo inventaría lo presente en el árbol. No es evidencia de compilación ni de validación en dispositivo para la revisión actual.

## Base de la aplicación

- [x] App Android Kotlin/Compose, minSdk 26, target/compile 37.
- [x] Material 3, color dinámico, edge-to-edge y navegación adaptativa bar/rail.
- [x] DI manual mediante `AppContainer`.
- [x] Room v4 con migraciones 1→2→3→4.
- [x] Sin permiso de red ni backend.

## Biblioteca e importación

- [x] Copia privada de imágenes, GIF y videos.
- [x] Metadata de dimensiones, duración y rotación de video.
- [x] Photo Picker y proveedor de archivos.
- [x] Recepción de `ACTION_SEND` y `ACTION_SEND_MULTIPLE`.
- [x] Biblioteca raíz y carpetas.
- [x] Crear, renombrar y borrar carpetas sin borrar sus medios.
- [x] Mover múltiples medios.
- [x] Cuadrícula de 2, 3 o 4 columnas.
- [x] Preview/poster y reproducción acotada de videos visibles en grids.

## Playlists

- [x] Crear, renombrar y borrar.
- [x] Agregar/quitar múltiples medios.
- [x] Drag reorder.
- [x] Recorrido ordenado o barajado persistido.
- [x] Loop anterior/siguiente.
- [x] Selección directa de “mostrar ahora” preservando ciclo.
- [x] Destino Home, Lock o Both.
- [x] Intervalo 1–180 minutos.

## Editor y previews

- [x] Transform por medio y orientación.
- [x] Contain, cover y stretch.
- [x] Zoom, pan y rotación.
- [x] Clamp basado en viewport y decode requerido.
- [x] Preview de teléfono/tablet.
- [x] Preview de lock style.
- [x] Relleno estable de franjas con cinco colores, HEX o backdrop blur al 125 %.
- [x] Persistencia y refresh visual del wallpaper actual.

## Medios y wallpaper

- [x] Live wallpaper de imágenes.
- [x] GIF animado mediante `AnimatedImageDrawable`.
- [x] Video silenciado mediante `MediaPlayer` + EGL + `SurfaceTexture`.
- [x] Transform compartido en Canvas y EGL.
- [x] Renderer EGL persistente para transiciones entre video/still.
- [x] Poster bridge para evitar surface negro al entrar a video.
- [x] Reemplazo video→video sin recrear window surface.
- [x] Pausa configurable de GIF/video cuando el engine no es visible.
- [x] Tap en Home configurable para avanzar.
- [x] Aplicación estática Home y Lock.
- [x] Reconciliación de destinos reales Home/Lock después del chooser.
- [x] Recarga del perfil vertical/horizontal al girar, incluso con surfaces estables de tablet.
- [x] Crossfade de 750 ms estilo Muzei con escenas completas y transición corta de orientación.
- [x] Retarget de transiciones rápidas sin colas y hand-off visual EGL↔Canvas.

## Lockscreen

- [x] Fallback estático con el mismo transform.
- [x] Detección de instancia live de Lock o compartida.
- [x] Setting para permitir video live en Lock.
- [x] Blur 0–10 y oscurecimiento 0–20 % por playlist.
- [x] Override por medio con prioridad.
- [x] Efectos aplicados en draw para no contaminar Home en engine compartido.
- [x] Video/GIF degradado a frame cuando los efectos requieren composición estática.

## Rotación y background work

- [x] `RotationController` como owner único.
- [x] WorkManager one-shot único.
- [x] `expectedImageId` + `rotationToken` contra workers obsoletos.
- [x] Restauración en boot.
- [x] Pausa/play persistido.
- [x] Debounce/non-queue de avances manuales.
- [x] Video por repeticiones o minutos.
- [x] Pulse conflated hacia engines.
- [x] Aplicación estática Lock conflated fuera del mutex.

## Notificación

- [x] Channel público de importancia default.
- [x] RemoteViews compacta y expandida.
- [x] Miniatura, playlist, destino, progreso y hora siguiente.
- [x] Anterior, pausa/play y siguiente.
- [x] Chip de ahorro cuando el sistema está en ese modo.
- [x] Publicación debounced/conflated.

## Ahorro de energía

- [x] Política `IGNORE`.
- [x] Política `STATIC`.
- [x] Monitor del estado real del sistema.
- [x] Congelar primera imagen o frame del primer medio.
- [x] Invalidar schedule y bloquear avances mientras aplica.
- [x] Reconciliar al entrar/salir.

## Backup y mantenimiento

- [x] Export/import JSON.
- [x] Export/import ZIP con copias privadas.
- [x] Compatibilidad de DTOs con defaults.
- [x] Reset local sin tocar Galería.
- [x] Documentación de arquitectura y rutas críticas.

## Cobertura automatizada existente

- [x] Unit tests básicos de `ImageLayout`.
- [x] Unit tests básicos de `ShuffleEngine`.
- [ ] Tests de migraciones Room.
- [ ] Tests de repository/backups.
- [ ] Tests de controller/scheduler.
- [ ] Tests reales de Compose principales.
- [ ] Tests de EGL/MediaPlayer (requieren dispositivo/instrumentación especializada).

## Pendiente de validación sistemática

- [ ] Build completo después del snapshot documental.
- [ ] Matriz de transiciones en Galaxy S25 Ultra.
- [ ] Crossfade y giros repetidos con imagen/GIF/video en Galaxy Tab S9.
- [ ] Rendimiento y safe areas en Galaxy Tab S9.
- [ ] Home/Lock/Both en distintas elecciones del chooser Samsung.
- [ ] Notificación compacta/expandida/lockscreen con ajustes OEM.
- [ ] Reboot, Doze y ahorro por periodos largos.
- [ ] Upgrade real de una base v1/v2/v3 a v4.
- [ ] Round-trip de backup grande con mezcla de medios.

Consulta [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) para la matriz manual y definition of done.
