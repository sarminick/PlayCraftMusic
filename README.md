# PlayCraftMusic (v1.0.0)

Sincronizador y reproductor de música de YouTube en tiempo real para **Minecraft (Fabric 26.2)**. Permite buscar canciones y añadir enlaces directos, escuchar de forma sincronizada con todos los jugadores del servidor a través de un motor de audio de alta fidelidad (48kHz 16-bit estéreo sobre OpenAL).

---

## Características Principales

- **Sincronización Multijugador en Tiempo Real:** Todos los jugadores del servidor escuchan exactamente la misma canción y posición, con inicio sincronizado (`STREAM_READY`) y pre-carga automática (*pre-fetching*) de la siguiente canción en cola.
- **HUD Flotante con Vinilo Animado:** Muestra la carátula oficial de YouTube girando como un disco de vinilo, título, artista y progreso en vivo.
- **Centro Musical Completo (`.`):**
  - **Now Playing:** Barra de progreso interactiva (*Seek*), control de volumen individual, botón de silencio y controles de reproducción (*Prev*, *Play/Pause*, *Skip*, *Stop*).
  - **Search:** Búsqueda directa en YouTube con opción de cargar más resultados (`➕ Ver más resultados`) y **Modo Enlace Directo** para pegar URLs de videos o playlists.
  - **Playlist:** Cola de reproducción interactiva para subir (`▲`), bajar (`▼`), eliminar (`✖`) canciones o vaciar la lista.
  - **Settings:** Personalización de posición (`X`, `Y`), escala, opacidad, 5 temas de color (*Gold*, *Cyan*, *Purple*, *Emerald*, *Crimson*), opción para **ocultar el HUD flotante**, y avisos de actividad.
- **Bandeja de Controles Rápidos (`,`):** Despliega los controles esenciales justo debajo del HUD sin cubrir la pantalla de juego.

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
3. **Dependencias del Sistema (`yt-dlp` y `ffmpeg`):**
   El mod utiliza `yt-dlp` y `ffmpeg` para extraer y decodificar el stream de audio de YouTube sin bloqueos. Puedes verificar si están detectados dentro del juego en la pestaña **Settings** (`Dependencias: yt-dlp: OK | ffmpeg: OK`).

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
`build/libs/playcraftmusic-1.0.0.jar`

---

## Licencia

Distribuido bajo la licencia **MIT**. Creado por **Sarminick.dev**.
