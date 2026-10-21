# TubeGround — client video leggero

TubeGround è un prototipo Android con interfaccia scritta in HTML, CSS e JavaScript. La UI è ospitata in una WebView locale a schermo intero; yt-dlp risolve URL di streaming diretti ed ExoPlayer sincronizza audio/video senza scaricare l’intero filmato prima dell’avvio, quando il video espone tracce separate compatibili. La barra di stato e quella di navigazione Android restano nascoste durante l’uso, con comparsa temporanea tramite swipe. Il progetto è orientato a dispositivi **Android ARM64**.

## Cosa è già impostato

- Ricerca e suggerimenti via API pubblica Invidious; le chiamate API Android passano dal codice nativo per evitare i blocchi CORS della WebView. In caso di errore vengono provate altre istanze Invidious pubbliche; preferiti e cronologia restano locali.
- Incolla un link YouTube completo nella barra di ricerca per aprire direttamente il video.
- Quando si seleziona un risultato nell'app Android, il bridge nativo passa l'ID a `yt-dlp` (attraverso `youtubedl-android`). Preferisce un flusso progressivo audio+video fino a 720p; se non è disponibile, prova a sincronizzare direttamente le tracce video e audio con Media3/ExoPlayer. Questo evita di scaricare e costruire prima l'intero file, riducendo attesa e uso della cache. Alcuni video possono comunque non esporre formati compatibili. Il controllo aggiornamenti di `yt-dlp` viene eseguito dopo una riproduzione riuscita, al massimo una volta ogni 24 ore, così non rallenta l'avvio del primo video.
- Il video si apre in un player Android Media3. Il player è collegato a un `MediaSessionService`, perciò l'audio può continuare quando si torna alla home o si mette l'app in secondo piano; i controlli multimediali di Android sono gestiti dalla sessione.
- Non è richiesto un API key YouTube Data API per la ricerca di base.
- Il browser desktop mostra una anteprima attraverso il player YouTube incorporato; la riproduzione in background è un comportamento dell'app Android, non della sola pagina web.

## Build su Mac

1. Estrai la cartella del progetto in una directory scrivibile.
2. Fai clic destro su `BUILD_ANDROID.command` e scegli **Apri** la prima volta, oppure eseguila dal Terminale.
3. Lo script scarica in `.build-tools/` JDK (se necessario), Android Command-line Tools, Android SDK API 36 e Gradle. Non richiede Android Studio, ma la prima esecuzione scarica diverse centinaia di MB e richiede una connessione Internet.
4. L'APK debug firmato per test viene creato in `dist/TubeGround-debug.apk`.
5. Copia l'APK sul telefono e installalo. Se Android lo richiede, abilita l'installazione da questa sorgente.

Il progetto è impostato per `arm64-v8a`, adatto ai moderni telefoni Android a 64 bit. Non è ancora una release universale per dispositivi x86 o ARM a 32 bit. L'APK `debug` è per test, non è una release firmata con una chiave personale.

## Architettura

```text
HTML / CSS / JavaScript (WebView)
     ├── AndroidBridge.apiGet(): Invidious API e fallback istanze
     └── AndroidBridge.playVideo(videoId)
              └── youtubedl-android / yt-dlp
                       └── URL stream HTTPS
                                └── Media3 ExoPlayer
                                         └── MediaSessionService
                                              audio/controlli in background
```

## Limitazioni attuali

- Le istanze Invidious pubbliche possono cambiare dominio, andare offline o limitare le chiamate. La lista di fallback è mantenuta vicino all’elenco canonico del progetto Invidious e viene provata in parallelo dopo il primo errore. Se nessuna istanza risponde o tutte limitano le richieste, la ricerca può fallire; si può selezionare un’altra istanza dalle impostazioni.
- YouTube modifica periodicamente le procedure di estrazione. Se la prima estrazione fallisce, TubeGround prova ad aggiornare il motore yt-dlp tramite il canale nightly (con un periodo di raffreddamento) e ripete una volta la richiesta. Non può comunque garantire la riproduzione di ogni video: alcuni stream possono essere limitati, scaduti o non compatibili.
- Durante la lettura dei metadati, yt-dlp fornisce i formati realmente disponibili per il video. TubeGround preferisce MP4/AVC/AAC progressive entro 720p; quando serve, prova a sincronizzare direttamente due URL di traccia in ExoPlayer invece di effettuare un download completo. Il percorso a tracce separate è più veloce da avviare ma può fallire se codec, URL o sincronizzazione non sono supportati dal dispositivo. Se Media3 non riesce a caricare il flusso, il player mostra un messaggio e offre un collegamento per aprire il video nell’app o nel sito YouTube.
- L'audio resta attivo quando l'app è ridotta a icona; il video non resta visibile sulla home. Il video può essere controllato dal player nativo, mentre la notifica multimediale permette pausa/ripresa.
- Non c'è login YouTube, download su file, sincronizzazione cloud, commenti o playlist personalizzate in questo MVP.

## Licenze e distribuzione

La dipendenza `youtubedl-android` è distribuita con licenza GPL-3.0 e incorpora componenti di terze parti. Prima di distribuire pubblicamente l'app occorre verificare e rispettare le licenze di tutte le dipendenze, oltre alle condizioni applicabili ai servizi video utilizzati. Il nome commerciale mostrato nell’app è TubeGround; l’applicationId Android resta `com.albrandigames.tubelite` per consentire l’aggiornamento dell’installazione esistente.

## Nota importante prima della distribuzione

TubeGround è un prototipo tecnico e **non è pronto per una pubblicazione sul Play Store**. I Termini di YouTube pongono limiti all'accesso automatizzato, al download e all'elusione delle restrizioni; le norme per i client che usano YouTube API proibiscono la riproduzione in background dei contenuti. Questa app usa un estrattore esterno e un player nativo, perciò prima di pubblicarla o monetizzarla occorre una revisione dei termini, dei diritti sui contenuti e degli obblighi GPL-3.0 della libreria.
