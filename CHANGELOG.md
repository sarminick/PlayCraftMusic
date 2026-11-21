# Historial de Cambios - PlayCraftMusic

Todos los cambios relevantes de este proyecto se documentan en este archivo siguiendo el estándar de **Versionado Semántico (SemVer)**.

## [1.1.0] - 2026-11-21

### Motor de Audio v2
- Ampliado el anillo de OpenAL a 24 buffers rotativos (~2.4 segundos) con sincronización estricta (`alLock`) sin bloquear operaciones de E/S.
- Agregado caché con TTL de 2 horas para URLs resueltas y pre-carga en segundo plano de la siguiente pista (en cola o sugerida por Autoplay).
- Corregido el Watchdog para cubrir estancamientos tanto durante la carga inicial (>18s) como en reproducción activa (>5s) o cortes prematuros.
- Añadido tercer intento automático mediante *pipe* (`yt-dlp --http-chunk-size 10M -o - | ffmpeg`) junto con opciones de reconexión HTTP/red para mitigar bloqueos 403 y throttling.
- Añadido botón **Reconectar** en la pestaña *Now Playing* para recuperar el audio localmente al segundo actual.
- Estado de conexión en vivo en el HUD flotante y giro del vinilo condicionado a emisión real de audio.

### Playlists e Importación
- Resolución de playlists de YouTube ejecutada desde el cliente (hasta 50 pistas por importación) evitando bloqueos de IP en servidores cloud (Azure/AWS).
- Extracción automática del título real de la playlist de YouTube (`%(playlist_title)s`) con nombres alternativos descriptivos.
- Nueva pestaña **Playlists ("Mis Playlists")** persistente en `config/playcraft_playlists.json`:
  - Colección automática de **Favoritos**, creación, eliminación y renombrado en línea de playlists.
  - Botón rápido para guardar la cola actual como playlist.
  - Listado con miniaturas completas HD en proporción 16:9 y duración total acumulada.
  - Vista de detalle con banner panorámico de fondo, botones **Play**, **Mezclar**, **+ Actual** y gestión individual de pistas.

### Autoplay y Controles de Sesión
- Agregado modo **Autoplay** tipo YouTube Mix (`list=RD`) resuelto desde los clientes conectados, con vista previa en **Queue** y botón **Cambiar** para elegir otra sugerencia.
- Agregados modos **Loop** (*Desactivado*, *1 Canción*, *Toda la cola*) y **Shuffle** para mezclar la cola en cualquier momento.
- Agregada sub-pestaña **Historial** en **Queue** con las últimas pistas reproducidas.

### Interfaz y Ajustes
- Silenciado automático de la música de fondo vanilla de Minecraft mientras PlayCraftMusic está activo (configurable en **Settings**).
- Agregado verificador multiplataforma **Buscar actualizaciones** para `yt-dlp` en **Settings**, mostrando versión actual y comandos recomendados para Windows, Arch Linux, Debian/Ubuntu, Fedora y macOS.
- Agregada ventana emergente de novedades al iniciar el centro musical por primera vez tras actualizar de versión.
- Unificada la lógica de botones y mensajería formal en toda la interfaz.

## [1.0.0] - 2026-10-21

### Lanzamiento Inicial
- Reproductor de audio de YouTube sincronizado en tiempo real para servidores Fabric (`26.2`) mediante `yt-dlp`, `ffmpeg` y OpenAL.
- HUD flotante configurable con vinilo animado, escala, opacidad, temas de color y opción para ocultarlo.
- Bandeja de controles rápidos (`,`) y Centro Musical (`.`) con pestañas *Now Playing*, *Search* (búsqueda y enlace directo), *Playlist* y *Settings*.
- Configuración persistente por cliente en `config/playcraftmusic.json`.
