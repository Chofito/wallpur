# Limitaciones y comportamiento dependiente de Android

Snapshot: 24 de agosto de 2026.

Estas limitaciones separan lo controlado por Wallpurr de lo que depende del framework, launcher, fabricante o estado del proceso. Deben verificarse en el dispositivo objetivo antes de convertir una observación en garantía.

## El live wallpaper requiere confirmación del sistema

Una app de terceros no puede instalar silenciosamente un live wallpaper. Wallpurr abre `ACTION_CHANGE_LIVE_WALLPAPER`; Android muestra su selector y decide si ofrece Home, Lock o ambas.

`SET_WALLPAPER_COMPONENT` es privilegiado y no se usa.

## Video/GIF en lockscreen no es universal

Wallpurr puede animar Lock únicamente cuando Android/OEM crea una instancia live que incluya esa pantalla. Si no existe, el fallback usa `WallpaperManager.setBitmap(FLAG_LOCK)` y necesariamente es estático.

Incluso con live Lock:

- el setting “reproducir videos en bloqueo” debe estar activo;
- blur u oscurecimiento fuerzan un frame estático en engine exclusivamente Lock;
- un GIF con blur también usa frame estático.

Samsung puede cambiar este comportamiento entre versiones de One UI.

## Un engine puede representar Home y Lock

Android puede entregar engines separados o uno compartido. En el compartido, los efectos de Lock se aplican según keyguard para evitar que Home quede difuminado. Los callbacks de flags/keyguard pueden variar por fabricante, por lo que Home/Lock/Both necesita prueba real.

## Launcher zoom, parallax y crop

Algunos launchers aplican zoom o desplazamiento adicional sobre el surface final. Wallpurr controla su composición interna, pero no puede desactivar toda transformación posterior del launcher.

La aplicación estática también puede ser reinterpretada por el selector de fondos del fabricante.

## Scheduling no es tiempo real

WorkManager no garantiza ejecución exacta. Doze, Adaptive Battery, restricciones OEM y proceso detenido pueden atrasar el cambio.

Wallpurr guarda una hora objetivo y usa one-shot work, pero no es un alarm clock exacto. La exclusión de optimización de batería puede ayudar, no garantiza puntualidad.

Los videos configurados por repeticiones avanzan al completar loops solo cuando existe un destino live reproduciéndolos; en fallback estático usan el intervalo.

## Notificación en lockscreen

La notificación es ongoing, pública, silenciosa y de importancia default, pero no es un foreground service. Para verla en lockscreen hacen falta:

- permiso de notificaciones en Android 13+;
- channel habilitado;
- contenido de lockscreen permitido;
- que el filtro OEM no la oculte.

El Galaxy S25 y la Tab S9 pueden mostrar resultados distintos con el mismo APK por settings de One UI.

## Superficies de video y fabricantes

La combinación wallpaper `Surface`, EGL, `SurfaceTexture` y `MediaPlayer` tiene diferencias OEM. El código conserva EGL y usa bridge frames para reducir:

- flashes negros;
- video que aparece solo después de lock/unlock;
- conflictos `BufferQueue already connected`;
- errores MediaPlayer `-38`.

Estas mitigaciones deben validarse en cada dispositivo; no eliminan todos los bugs del driver/launcher.

El crossfade conserva temporalmente dos composiciones RGB del tamaño del viewport en GPU. Solo
existen durante 750 ms y se liberan al terminar u ocultarse el engine; si el driver no puede reservar
una textura, el renderer omite la animación y presenta el destino directamente. Al salir de EGL hacia
GIF/Canvas se hace un único frame de enlace acotado a 2048 px (1024 px en low-RAM), por lo que esa transición puede verse
ligeramente menos nítida que el wallpaper estable en pantallas de resolución extrema.

## Rendimiento de grids

Cada video reproduciéndose requiere decoder, player y textura. Wallpurr solo activa videos totalmente visibles después de detener el scroll. No intenta reproducir toda la cuadrícula a la vez como apps con pools y pipelines de video dedicados.

El número de videos simultáneos y la fluidez dependen de codec, resolución, SoC y cantidad de columnas.

## Blur

Android 12+ dispone de ruta hardware para ciertos renders Canvas. Versiones anteriores usan un blur CPU acotado, que puede verse diferente.

El backdrop se procesa en resolución reducida (máximo 720 px) y después se escala; prioriza rendimiento y consistencia entre preview, Canvas, EGL y aplicación estática, no equivalencia pixel-perfect con un editor fotográfico.

Cuando blur y oscurecimiento están en cero, se usa una ruta rápida que omite esas capas.

## Formatos de medios

La detección soporta principalmente:

- imágenes decodificables por `ImageDecoder`;
- GIF/formatos animados detectados por metadata;
- MP4, WebM, MKV y algunos 3GP decodificables por el dispositivo.

Que una extensión se importe no garantiza que el codec hardware/software del dispositivo pueda reproducir su contenido.

Los videos no tienen audio por diseño.

## Photo Picker y proveedores

El Photo Picker y los proveedores de documentos muestran fuentes diferentes. Algunas apps entregan URIs temporales o metadata incompleta. Wallpurr copia inmediatamente el stream; si el proveedor revoca acceso antes de terminar, la importación falla y elimina el archivo parcial.

Al compartir desde otra app, esa app debe incluir `EXTRA_STREAM` o `ClipData` y conceder lectura.

## Almacenamiento privado

Las copias viven en almacenamiento privado. Desinstalar o borrar datos de Wallpurr elimina biblioteca y configuración, aunque los originales de Galería permanecen.

El JSON no contiene los binarios. Usa ZIP para un backup completo.

JSON y ZIP no están cifrados por Wallpurr. Su privacidad depende de la ubicación/proveedor elegido en el selector de documentos.

## Restauración y upgrades

Room tiene migraciones hasta v4, pero el upgrade desde todas las versiones anteriores necesita validación explícita sobre bases reales. Una instalación limpia no prueba migraciones.

Android Auto Backup puede restaurar archivos/base en momentos diferentes; faltantes se deben tratar como medios no disponibles, no recrear desde rutas originales.

## Ahorro de energía

Wallpurr puede ignorar el modo o congelarse en contenido estático. No puede activar/desactivar el ahorro del sistema ni anular restricciones OEM.

La política estática busca una imagen real; si la playlist solo tiene video/GIF, congela un frame representativo.

## Estado de validación

El código y estas decisiones se han revisado estáticamente. El snapshot documental no incluye una compilación ni una ejecución nueva. Consulta la sección pendiente de [PROGRESS.md](PROGRESS.md) y registra evidencia de dispositivo antes de cerrar bugs OEM.
