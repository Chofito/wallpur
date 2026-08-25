# Identidad visual de Wallpurr

Wallpurr combina una cabeza de gato con una ventana de paisaje. El gato representa el “purr” y
la ventana comunica la función de fondos sin depender de una fotografía concreta.

## Paleta base

| Token | HEX | Uso principal |
| --- | --- | --- |
| Tinta | `#151617` | Texto, fondos oscuros y silueta |
| Leche | `#F7F6F2` | Fondos claros y contenido sobre oscuro |
| Ronroneo | `#D98945` | Marca, acciones y estados activos |
| Pelaje | `#F2DDCA` | Contenedores y selección suave |
| Pizarra | `#5F6D78` | Secundario y texto atenuado |
| Niebla | `#DFE3E1` | Superficies alternativas y divisores |

Los tonos adicionales de `ui/theme/Color.kt` son derivados de esta base para mantener contraste
en Material 3. El verde de la notificación se conserva exclusivamente como color semántico del
modo de ahorro de energía.

## Recursos

- `artwork/brand/wallpurr-icon.svg`: fuente vectorial principal.
- `artwork/brand/wallpurr-icon-round.svg`: fallback redondo para launchers legacy.
- `artwork/brand/wallpurr-notification.svg`: glyph monocromático de referencia.
- `drawable/ic_launcher_foreground.xml`: capa de color del icono adaptativo.
- `drawable/ic_launcher_background.xml`: fondo Ronroneo del icono adaptativo.
- `drawable/ic_launcher_monochrome.xml`: themed icon para Android 13+.
- `drawable/ic_stat_wallpurr.xml`: small icon de notificación, siempre monocromático.
- `mipmap-*/ic_launcher*.png`: fallbacks rasterizados de 48 a 192 px.

El foreground se escala alrededor de `(54, 54)` para que el gato completo permanezca dentro de la
zona segura adaptativa de 66 × 66 aun con las máscaras más cerradas de Samsung. No agregues texto
al icono ni reutilices el foreground de color como recurso `monochrome`: Android interpreta su
alpha completo como una sola máscara.

## Previsualización

Abre `docs/design/wallpurr-brand-preview.html` para revisar máscaras adaptativas, themed icon,
notificación y ejemplos claros/oscuros. La página es autocontenida y no requiere servidor.
