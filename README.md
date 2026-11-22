# PlayCraftMusic (v2.0.0)

Sincronizador y reproductor de música de YouTube en tiempo real para **Minecraft (Fabric 26.2)**. Permite buscar canciones, añadir enlaces directos o playlists completas, guardar colecciones locales y escuchar de forma sincronizada con todos los jugadores del servidor a través de un motor de audio de alta fidelidad (48kHz 16-bit estéreo sobre OpenAL).

---

## Características Principales

- **Interfaz Moderna v2.0 (Estilo App):** Ventana completamente rediseñada sin componentes grises vanilla, con barra lateral vertical fija, tarjetas elevadas oscuras, bordes redondeados y mini-reproductor siempre activo en el menú.
- **Tema Dinámico Adaptativo:** Extrae en tiempo real el color vibrante dominante de la carátula activa para teñir acentos, controles, sliders y bordes de toda la interfaz (junto a 5 temas manuales: *Gold*, *Cyan*, *Purple*, *Emerald*, *Crimson*).
- **Tipografía Moderna Alternable:** Incluye la fuente **Noto Sans Medium** (`assets/playcraft/font/modern.json`) aplicable de forma aislada al Centro Musical y HUD, con cambio instantáneo en vivo desde *Ajustes* hacia la fuente clásica de Minecraft.
- **28 Iconos Vectoriales HD Propios:** Set personalizado en `64x64` px para controles de reproducción, navegación, playlists, favoritos y estado de audio.
- **Motor de Audio v2 (Estabilidad Multijugador):** Anillo de 24 buffers (~2.4s), caché con TTL, pre-carga de la siguiente canción, watchdog automático desde la carga inicial, respaldo vía *pipe* (`yt-dlp | ffmpeg`) contra bloqueos HTTP 403/throttling y botón local **Reconectar**.
- **Sincronización Multijugador en Tiempo Real:** Todos los jugadores escuchan exactamente la misma canción y posición (`STREAM_READY`), con **Autoplay** tipo YouTube Mix, modos de **Repetición** (*1 canción* / *cola*) y **Shuffle**.
- **HUD Flotante con Vinilo Animado:** Tarjeta moderna redondeada que muestra la carátula oficial en HD girando únicamente cuando el audio está activo, título, estado de reproducción en vivo y progreso.
- **Centro Musical Completo (`.`):**
  - **Reproducción:** Tarjeta hero con carátula de fondo adaptativa, barra de progreso (*Seek*), volumen, silencio, controles de reproducción, modos *Autoplay / Loop / Shuffle*, *Favorito*, selector rápido `+ Playlist` y botón *Reconectar*.
  - **Buscar / Enlace:** Búsqueda con carga progresiva (`Cargar más`) e importación de **Playlists de YouTube desde el cliente** con extracción automática del título real (evita bloqueos en servidores cloud como Azure/AWS).
  - **Cola e Historial:** Gestión interactiva de la cola de reproducción, vista previa de la siguiente pista sugerida por *Autoplay* (con opción *Cambiar*) e **Historial reciente**.
  - **Playlists ("Mis Playlists"):** Crea, renombra y guarda colecciones locales en `config/playcraft_playlists.json`, administra **Favoritos**, visualiza miniaturas completas 16:9, duración total y banner panorámico HD de fondo al abrir cada lista, y reprodúcelas (`Play`) o mézclalas al instante.
  - **Ajustes:** Personalización de posición (`X`, `Y`), escala, opacidad, tema dinámico o manual, selector de fuente (*Moderna* / *Minecraft*), ocultar HUD flotante, silenciar música vanilla, avisos de actividad y **Comprobar yt-dlp** multiplataforma (Windows, Linux, macOS).
  - **Ventana de Novedades:** Resumen automático mostrado una sola vez al abrir el mod tras actualizar de versión (y accesible desde el botón `?`).
- **Bandeja de Controles Rápidos (`,`):** Controles esenciales modernos con iconos HD y barras adaptativas integrados directamente bajo el HUD sin cubrir el juego.

---

## Controles por Defecto

| Tecla | Acción |
| :---: | :--- |
| **`.`** *(Punto)* | Abrir / Cerrar el Centro Musical (**PlayCraftMusic**) |
| **`,`** *(Coma)* | Abrir / Cerrar la Bandeja de Controles Rápidos |

*(Puedes personalizar estas teclas desde el menú **Opciones -> Controles** de Minecraft).*

---

## Requisitos Previos

1. **Minecraft:** `26.2` con **Fabric Loader** (`>= 0.16.0`) y **Fabric API**.
2. **Java:** `25` (o `>= 21`).
3. **Dependencias del Sistema (`yt-dlp` y `ffmpeg`) en cada Cliente:**
   Cada jugador necesita `yt-dlp` y `ffmpeg` en su equipo para extraer y decodificar el audio de YouTube localmente. El servidor ya no depende de resolver playlists ni recomendaciones de Autoplay directamente, evitando bloqueos por IP en servidores cloud (Azure, AWS, etc.). Puedes verificar el estado y buscar actualizaciones desde el juego en la pestaña **Settings** (`Dependencias: yt-dlp: OK | ffmpeg: OK`).

### Instalación rápida de `yt-dlp` y `ffmpeg`:

#### En Windows
- **Opción A (Por terminal con WinGet):**
  ```powershell
  winget install yt-dlp.yt-dlp
  winget install Gyan.FFmpeg
  ```
- **Opción B (Portable en la carpeta de Minecraft):**
  Descarga `yt-dlp.exe` y `ffmpeg.exe` y colócalos dentro de `%APPDATA%\.minecraft\playcraft\bin\`. El mod los detectará automáticamente sin necesidad de modificar el `PATH` del sistema.

#### En Linux
- **Arch / Manjaro:** `sudo pacman -S yt-dlp ffmpeg`
- **Ubuntu / Debian:** `sudo apt install ffmpeg && sudo curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp && sudo chmod a+rx /usr/local/bin/yt-dlp`

---

## Compilación desde el Código Fuente

```bash
./gradlew build
```

El archivo `.jar` compilado se generará en:
`build/libs/playcraftmusic-2.0.0.jar`

---

## Licencia

Distribuido bajo la licencia **MIT**. Creado por **Sarminick.dev**.
