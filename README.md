# PlayCraftMusic (v1.1.0)

Sincronizador y reproductor de música de YouTube en tiempo real para **Minecraft (Fabric 26.2)**. Permite buscar canciones, añadir enlaces directos o playlists completas, guardar colecciones locales y escuchar de forma sincronizada con todos los jugadores del servidor a través de un motor de audio de alta fidelidad (48kHz 16-bit estéreo sobre OpenAL).

---

## Características Principales

- **Motor de Audio v2 (Estabilidad Multijugador):** Anillo de 24 buffers (~2.4s), caché con TTL, pre-carga de la siguiente canción, watchdog automático desde la carga inicial, respaldo vía *pipe* (`yt-dlp | ffmpeg`) contra bloqueos HTTP 403/throttling y botón local **Reconectar**.
- **Sincronización Multijugador en Tiempo Real:** Todos los jugadores escuchan exactamente la misma canción y posición (`STREAM_READY`), con **Autoplay** tipo YouTube Mix, modos de **Repetición** (*1 canción* / *cola*) y **Shuffle**.
- **HUD Flotante con Vinilo Animado:** Muestra la carátula oficial en HD girando únicamente cuando el audio está activo, título, estado de reproducción en vivo y progreso.
- **Centro Musical Completo (`.`):**
  - **Now Playing:** Barra de progreso (*Seek*), volumen, silencio, controles de reproducción, modos *Autoplay / Loop / Shuffle*, *Favorito* y botón *Reconectar*.
  - **Search:** Búsqueda con carga de más resultados (`Ver más resultados`) e importación de **Playlists de YouTube desde el cliente** con extracción automática del título real (evita bloqueos en servidores cloud como Azure/AWS).
  - **Queue:** Gestión interactiva de la cola de reproducción, vista previa de la siguiente pista sugerida por *Autoplay* (con opción *Cambiar*) e **Historial reciente**.
  - **Playlists ("Mis Playlists"):** Crea, renombra y guarda colecciones locales en `config/playcraft_playlists.json`, administra **Favoritos**, visualiza miniaturas completas 16:9, duración total y banner panorámico HD al abrir cada lista, y reprodúcelas (`Play`) o mézclalas al instante.
  - **Settings:** Personalización de posición (`X`, `Y`), escala, opacidad, 5 temas de color (*Gold*, *Cyan*, *Purple*, *Emerald*, *Crimson*), ocultar HUD flotante, silenciar la música vanilla de Minecraft automáticamente, avisos de actividad y **Buscar actualizaciones** de `yt-dlp` con comandos recomendados según Windows, Linux o macOS.
  - **Ventana de Novedades:** Resumen automático mostrado una sola vez al abrir el mod tras actualizar de versión.
- **Bandeja de Controles Rápidos (`,`):** Controles esenciales de reproducción y volumen justo debajo del HUD sin cubrir la pantalla de juego.

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
`build/libs/playcraftmusic-1.1.0.jar`

---

## Licencia

Distribuido bajo la licencia **MIT**. Creado por **Sarminick.dev**.
