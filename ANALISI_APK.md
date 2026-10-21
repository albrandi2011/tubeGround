# Analisi statica di YTDLnis 1.6.1

File analizzato: `YTDLnis-1.6.1-arm64-v8a-release.apk` (APK ARM64, circa 45 MB). Questa è un'analisi statica: non è stata eseguita una cattura del traffico di rete dell'app installata.

## Evidenza direttamente leggibile nell'APK

Nel DEX e nelle risorse sono riconoscibili:

- Il namespace `com.deniscerri.ytdlnis` e le classi dell'app che compongono richieste di download.
- Il wrapper `com.yausername.youtubedl_android`, con classi `YoutubeDL`, `YoutubeDLRequest` e `YoutubeDLUpdater`. Il motore centrale è **yt-dlp**, non una singola API proprietaria chiamata direttamente dalla UI.
- API Invidious con host predefinito `invidious.nerdvpn.de`:
  - `GET https://invidious.nerdvpn.de/api/v1/search?q=...`
  - `GET https://invidious.nerdvpn.de/api/v1/videos/{videoId}`
  - `GET https://invidious.nerdvpn.de/api/v1/playlists/{playlistId}`
  - `GET https://invidious.nerdvpn.de/api/v1/stats`
  - `GET https://invidious.nerdvpn.de/api/v1/trending?type=music&region=...`
- Suggerimenti di ricerca Google/YouTube:
  - `https://suggestqueries.google.com/complete/search?client=youtube&ds=yt&client=firefox&q=...`
- Endpoint YouTube Data API v3, che servono a funzionalità metadata/tendenze e non costituiscono l'estrattore dello stream:
  - `https://youtube.googleapis.com/youtube/v3/search?part=snippet&q=...`
  - `https://youtube.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&id=...`
  - `https://www.googleapis.com/youtube/v3/videos?id=...`
  - chiamate `videos?chart=mostPopular` e `playlistItems?part=snippet&pageToken=...`
- Endpoint GitHub per il controllo degli aggiornamenti dell'app e del motore yt-dlp, inclusi i repository `deniscerri/ytdlnis`, `yt-dlp/yt-dlp` e `yt-dlp/yt-dlp-nightly-builds`.

## Cosa fa davvero il download/riproduzione

Le API Invidious aiutano soprattutto a cercare e leggere metadati. La parte che ricava un indirizzo video effettivamente riproducibile è `yt-dlp`, richiamato tramite il wrapper Android. Il wrapper include anche una funzione per controllare e scaricare l’ultima release stabile di `yt-dlp`, coerente con gli endpoint GitHub trovati nell’APK; non tutte le stringhe individuate sono necessariamente chiamate in ogni avvio o per ogni video. Questo componente segue la pagina YouTube e le risposte di riproduzione e si occupa dei dettagli variabili dei formati e del player. Le URL di media finali normalmente puntano alla CDN `googlevideo.com`. L’estrazione resta fragile nel tempo: l’attuale documentazione di yt-dlp descrive richieste con Proof-of-Origin token in alcuni percorsi/client YouTube e possibili errori HTTP 403 quando questi mancano. Per questo il progetto prova ad aggiornare il motore, ma non può garantire che ogni video funzioni indefinitamente.

Il codice dell'estrattore viene eseguito come motore separato: una scansione delle sole stringhe DEX non permette di ricostruire ogni richiesta HTTP, parametro o header usato durante una sessione. Per registrare precisamente il traffico della versione 1.6.1 servirebbe una cattura runtime, e l'eventuale traffico TLS potrebbe non essere visibile senza un ambiente di test adatto.

## Scelta implementativa di TubeGround

1. **Ricerca e metadati:** Invidious, con istanza modificabile nell'interfaccia. Su Android le chiamate passano dal bridge nativo per evitare che le policy CORS della WebView impediscano richieste cross-origin; in caso di errore vengono provate altre istanze pubbliche elencate dal progetto Invidious.
2. **Risoluzione del flusso:** `YoutubeDL.getInfo()` tramite `youtubedl-android`, con `--no-playlist`, `--no-warnings` e il selettore `best[acodec!=none][vcodec!=none]/best` per cercare un singolo URL con audio e video senza il warning legato al selettore letterale `-f best`.
3. **Riproduzione:** Media3/ExoPlayer.
4. **Background:** il player vive in un `MediaSessionService` Android dichiarato come `mediaPlayback`, non dentro la WebView; in questo modo la sessione audio sopravvive quando l'attività viene coperta o ridotta a icona.

## Riferimenti tecnici

- yt-dlp: https://github.com/yt-dlp/yt-dlp
- Guida corrente ai PO Token di YouTube in yt-dlp: https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide
- Wrapper Android e API `getInfo()`: https://github.com/yausername/youtubedl-android
- API Invidious: https://docs.invidious.io/api/
- Media3 background playback: https://developer.android.com/media/media3/session/background-playback

## Attenzione a termini e distribuzione

Questa è un'analisi tecnica, non un parere legale. I Termini di YouTube limitano l'accesso automatizzato, il download e l'elusione delle restrizioni del servizio; le norme per i client che usano YouTube API vietano espressamente la riproduzione in background dei contenuti YouTube. Questo prototipo estrae un URL diretto tramite `yt-dlp` e riproduce con un player nativo, quindi **non va considerato automaticamente conforme o idoneo al Play Store**. Prima di distribuirlo pubblicamente o monetizzarlo bisogna verificare diritti, termini applicabili e obblighi GPL-3.0 della libreria usata.

- Termini di YouTube: https://it.youtube.com/t/terms
- Norme per gli sviluppatori YouTube API: https://developers.google.com/youtube/terms/developer-policies
