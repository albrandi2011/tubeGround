# TubeGround 0.2.2

- Aggiornata la sorgente Invidious predefinita e la lista di fallback usando istanze presenti nell’elenco canonico corrente.
- Ridotto il timeout lato interfaccia per le chiamate API, così gli errori di rete non lasciano l’utente in attesa troppo a lungo.
- La home richiede i video di tendenza generali, non solo contenuti musicali.
- In caso di errore di estrazione, tenta un aggiornamento di yt-dlp (canale nightly) e ripete una volta la richiesta, con cooldown per evitare download ripetuti.
- Il player mostra un errore esplicito se lo stream non si carica e offre l’apertura del video su YouTube.
- Versione Android incrementata a 0.2.2 (versionCode 4).
