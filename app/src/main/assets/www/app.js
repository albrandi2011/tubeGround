(() => {
  'use strict';

  const DEFAULT_INSTANCE = 'https://invidious.nerdvpn.de';
  const INSTANCE_KEY = 'tubeground.instance';
  const FAVORITES_KEY = 'tubeground.favorites';
  const HISTORY_KEY = 'tubeground.history';
  const MAX_HISTORY = 35;

  // Keep personal data from the earlier prototype after the app's rebrand.
  for (const [oldKey, newKey] of [
    ['tubelite.instance', INSTANCE_KEY],
    ['tubelite.favorites', FAVORITES_KEY],
    ['tubelite.history', HISTORY_KEY],
  ]) {
    if (localStorage.getItem(newKey) === null) {
      const oldValue = localStorage.getItem(oldKey);
      if (oldValue !== null) localStorage.setItem(newKey, oldValue);
    }
  }

  const $ = (selector) => document.querySelector(selector);
  const ui = {
    searchForm: $('#search-form'),
    searchInput: $('#search-input'),
    suggestions: $('#search-suggestions'),
    results: $('#results'),
    status: $('#status'),
    emptyState: $('#empty-state'),
    emptyTitle: $('#empty-title'),
    emptyCopy: $('#empty-copy'),
    title: $('#section-title'),
    eyebrow: $('#section-eyebrow'),
    tabs: [...document.querySelectorAll('.tab')],
    favCount: $('#fav-count'),
    refresh: $('#refresh-button'),
    settingsDialog: $('#settings-dialog'),
    instanceInput: $('#instance-input'),
    settingsForm: $('#settings-form'),
    resetInstance: $('#reset-instance'),
    webPlayerDialog: $('#web-player-dialog'),
    webPlayerFrame: $('#web-player-frame'),
  };

  let currentView = 'home';
  let isBusy = false;
  let suggestionTimer = null;
  let instance = normalizeInstance(localStorage.getItem(INSTANCE_KEY) || DEFAULT_INSTANCE);
  let lastQuery = '';
  let apiSequence = 0;
  const pendingApiRequests = new Map();

  function normalizeInstance(value) {
    try {
      const url = new URL(String(value).trim());
      if (url.protocol !== 'https:') return DEFAULT_INSTANCE;
      return url.origin.replace(/\/$/, '');
    } catch (_) {
      return DEFAULT_INSTANCE;
    }
  }

  function escapeText(value) {
    return String(value ?? '');
  }

  async function api(path, params = {}) {
    const url = new URL(instance + path);
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null && value !== '') url.searchParams.set(key, value);
    }
    // The app's WebView has a local appassets origin. Many public Invidious instances
    // do not grant CORS to that origin, so native Android performs API GETs when available.
    if (window.AndroidBridge && typeof window.AndroidBridge.apiGet === 'function') {
      return await new Promise((resolve, reject) => {
        const requestId = `api-${Date.now()}-${++apiSequence}`;
        const timer = setTimeout(() => {
          pendingApiRequests.delete(requestId);
          reject(new Error('La sorgente API non ha risposto in tempo. Prova un’altra istanza nelle impostazioni.'));
        }, 25000);
        pendingApiRequests.set(requestId, { resolve, reject, timer });
        try {
          window.AndroidBridge.apiGet(instance, `${url.pathname}${url.search}`, requestId);
        } catch (error) {
          clearTimeout(timer);
          pendingApiRequests.delete(requestId);
          reject(new Error('Non riesco a richiamare il servizio API Android.'));
        }
      });
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 16000);
    try {
      const response = await fetch(url.toString(), {
        method: 'GET',
        headers: { Accept: 'application/json' },
        signal: controller.signal,
        cache: 'no-store',
      });
      if (!response.ok) throw new Error(`L'istanza ha risposto con HTTP ${response.status}.`);
      return await response.json();
    } catch (error) {
      if (error.name === 'AbortError') throw new Error('La sorgente non risponde. Prova un’altra istanza Invidious nelle impostazioni.');
      if (error instanceof TypeError) throw new Error('Non riesco a raggiungere la sorgente API. Potrebbe essere offline o bloccare le richieste dal client.');
      throw error;
    } finally {
      clearTimeout(timer);
    }
  }

  function setStatus(message = '', isError = false) {
    ui.status.textContent = message;
    ui.status.hidden = !message;
    ui.status.classList.toggle('error', Boolean(isError));
  }

  function setBusy(busy) {
    isBusy = busy;
    ui.refresh.disabled = busy;
    ui.refresh.style.opacity = busy ? '.5' : '1';
  }

  function getFavorites() {
    try { return JSON.parse(localStorage.getItem(FAVORITES_KEY) || '[]'); }
    catch (_) { return []; }
  }

  function getHistory() {
    try { return JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]'); }
    catch (_) { return []; }
  }

  function saveFavorites(items) {
    localStorage.setItem(FAVORITES_KEY, JSON.stringify(items));
    updateFavoriteCount();
  }

  function updateFavoriteCount() {
    ui.favCount.textContent = String(getFavorites().length);
  }

  function saveHistory(item) {
    const history = getHistory().filter((saved) => saved.videoId !== item.videoId);
    history.unshift({ ...item, playedAt: Date.now() });
    localStorage.setItem(HISTORY_KEY, JSON.stringify(history.slice(0, MAX_HISTORY)));
  }

  function isFavorite(id) {
    return getFavorites().some((item) => item.videoId === id);
  }

  function toggleFavorite(item) {
    const favorites = getFavorites();
    const existing = favorites.some((saved) => saved.videoId === item.videoId);
    saveFavorites(existing ? favorites.filter((saved) => saved.videoId !== item.videoId) : [{ ...item, savedAt: Date.now() }, ...favorites]);
    if (currentView === 'favs') renderLocalView('favs');
    else renderCards(window.__lastResults || []);
    setStatus(existing ? 'Rimosso dai preferiti.' : 'Aggiunto ai preferiti.');
  }

  function pickThumbnail(item) {
    if (item.thumbnail) return item.thumbnail;
    const thumbs = Array.isArray(item.videoThumbnails) ? item.videoThumbnails : [];
    const good = thumbs.find((thumb) => /maxres|high|medium/i.test(thumb.quality || '')) || thumbs.at(-1) || thumbs[0];
    return good?.url || `https://i.ytimg.com/vi/${encodeURIComponent(item.videoId || '')}/hqdefault.jpg`;
  }

  function toVideo(item) {
    return {
      videoId: String(item.videoId || item.id || ''),
      title: String(item.title || 'Video senza titolo'),
      author: String(item.author || item.uploader || 'Canale YouTube'),
      thumbnail: pickThumbnail(item),
      lengthSeconds: Number(item.lengthSeconds || item.duration || 0),
      viewCount: Number(item.viewCount || 0),
      publishedText: String(item.publishedText || ''),
      type: String(item.type || 'video'),
    };
  }

  function formatDuration(seconds) {
    seconds = Math.max(0, Number(seconds) || 0);
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = Math.floor(seconds % 60);
    return h ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`;
  }

  function formatViews(count) {
    count = Number(count) || 0;
    if (count <= 0) return '';
    return new Intl.NumberFormat('it-IT', { notation: count >= 1000 ? 'compact' : 'standard', maximumFractionDigits: 1 }).format(count) + ' visualizzazioni';
  }

  function makeEl(tag, className, text) {
    const el = document.createElement(tag);
    if (className) el.className = className;
    if (text !== undefined) el.textContent = text;
    return el;
  }

  function renderCards(rawItems) {
    window.__lastResults = rawItems;
    ui.results.replaceChildren();
    ui.emptyState.hidden = rawItems.length > 0;
    if (rawItems.length === 0) return;

    const fragment = document.createDocumentFragment();
    rawItems.forEach((raw) => {
      const item = toVideo(raw);
      if (!item.videoId || item.type && !['video', 'shortVideo', 'published'].includes(item.type)) return;
      const card = makeEl('article', 'video-card');
      const thumb = makeEl('button', 'thumb-button');
      thumb.type = 'button';
      thumb.setAttribute('aria-label', `Riproduci ${item.title}`);
      const image = makeEl('img');
      image.src = item.thumbnail;
      image.alt = '';
      image.loading = 'lazy';
      image.referrerPolicy = 'no-referrer';
      image.addEventListener('error', () => {
        if (!image.dataset.fallback) {
          image.dataset.fallback = '1';
          image.src = `https://i.ytimg.com/vi/${encodeURIComponent(item.videoId)}/hqdefault.jpg`;
        }
      }, { once: true });
      const play = makeEl('span', 'play-mark');
      play.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 4.8c0-1.2 1.3-1.9 2.3-1.3l12 7.2a1.5 1.5 0 0 1 0 2.6l-12 7.2A1.5 1.5 0 0 1 7 19.2z"/></svg>';
      thumb.append(image, play);
      if (item.lengthSeconds) thumb.append(makeEl('span', 'duration', formatDuration(item.lengthSeconds)));
      thumb.addEventListener('click', () => playItem(item));

      const body = makeEl('div', 'card-body');
      const channel = makeEl('div', 'channel-dot', (item.author.trim()[0] || 'Y').toUpperCase());
      channel.setAttribute('aria-hidden', 'true');
      const text = makeEl('div', 'card-text');
      const title = makeEl('button', 'card-title card-title-button', item.title);
      title.type = 'button';
      title.addEventListener('click', () => playItem(item));
      const meta = makeEl('div', 'card-meta');
      meta.textContent = [item.author, formatViews(item.viewCount), item.publishedText].filter(Boolean).join(' · ');
      text.append(title, meta);
      body.append(channel, text);

      const actions = makeEl('div', 'card-actions');
      const fave = makeEl('button', `card-action${isFavorite(item.videoId) ? ' saved' : ''}`, isFavorite(item.videoId) ? '♥ Salvato' : '♡ Salva');
      fave.type = 'button';
      fave.addEventListener('click', () => toggleFavorite(item));
      const source = makeEl('button', 'card-action', 'Apri su YouTube ↗');
      source.type = 'button';
      source.addEventListener('click', () => openOriginal(item));
      actions.append(fave, source);
      card.append(thumb, body, actions);
      fragment.append(card);
    });
    ui.results.append(fragment);
    ui.emptyState.hidden = ui.results.children.length > 0;
  }

  function showSection(view, title, eyebrow) {
    currentView = view;
    ui.tabs.forEach((tab) => tab.classList.toggle('active', tab.dataset.view === view));
    ui.title.textContent = title;
    ui.eyebrow.textContent = eyebrow;
    setStatus('');
  }

  async function loadTrending() {
    if (isBusy) return;
    showSection('home', 'Video consigliati', 'PER TE');
    setBusy(true);
    setStatus('Carico i video popolari…');
    ui.results.replaceChildren();
    try {
      let items;
      try {
        items = await api('/api/v1/trending', { region: 'IT', hl: 'it' });
      } catch (_) {
        items = await api('/api/v1/popular', { hl: 'it' });
      }
      const videos = Array.isArray(items) ? items.map(toVideo).filter((item) => item.videoId) : [];
      renderCards(videos);
      setStatus(videos.length ? `Sorgente: ${new URL(instance).host}` : 'La sorgente non ha restituito video. Prova un’altra istanza.');
      if (!videos.length) setEmpty('Nessun risultato dalla sorgente', 'Apri le impostazioni e cambia istanza Invidious.');
    } catch (error) {
      renderCards([]);
      setStatus(error.message || 'Errore nel caricamento.', true);
      setEmpty('Sorgente non disponibile', 'Apri le impostazioni e prova un’altra istanza Invidious.');
    } finally {
      setBusy(false);
    }
  }

  async function searchVideos(query) {
    query = String(query || '').trim();
    if (!query || isBusy) return;
    const directId = parseVideoId(query);
    if (directId) {
      playItem({ videoId: directId, title: 'Video YouTube', author: 'YouTube', thumbnail: `https://i.ytimg.com/vi/${directId}/hqdefault.jpg`, lengthSeconds: 0, viewCount: 0, publishedText: '' });
      return;
    }
    lastQuery = query;
    showSection('search', `Risultati per “${query}”`, 'RICERCA');
    setBusy(true);
    ui.results.replaceChildren();
    setStatus(`Cerco “${query}”…`);
    try {
      const items = await api('/api/v1/search', { q: query, type: 'video', page: 1, hl: 'it' });
      const videos = Array.isArray(items) ? items.map(toVideo).filter((item) => item.videoId && item.type !== 'channel' && item.type !== 'playlist') : [];
      renderCards(videos);
      setStatus(videos.length ? `${videos.length} risultati · ${new URL(instance).host}` : 'Nessun risultato per questa ricerca.');
      if (!videos.length) setEmpty('Nessun video trovato', 'Prova un’altra parola o incolla direttamente un link YouTube.');
    } catch (error) {
      renderCards([]);
      setStatus(error.message || 'Ricerca non riuscita.', true);
      setEmpty('Ricerca non disponibile', 'Cambia l’istanza Invidious nelle impostazioni e riprova.');
    } finally {
      setBusy(false);
    }
  }

  async function loadSuggestions(query) {
    if (!query || query.length < 2) return;
    try {
      const result = await api('/api/v1/search/suggestions', { q: query, hl: 'it' });
      const suggestions = Array.isArray(result?.suggestions) ? result.suggestions : [];
      ui.suggestions.replaceChildren(...suggestions.slice(0, 8).map((text) => {
        const option = document.createElement('option');
        option.value = text;
        return option;
      }));
    } catch (_) { /* Suggestions are optional; don't disrupt search if unsupported. */ }
  }

  function parseVideoId(input) {
    const text = String(input || '').trim();
    if (/^[A-Za-z0-9_-]{11}$/.test(text)) return text;
    try {
      const url = new URL(text);
      if (url.hostname === 'youtu.be' || url.hostname === 'www.youtu.be') {
        const id = url.pathname.split('/').filter(Boolean)[0];
        return id && /^[A-Za-z0-9_-]{11}$/.test(id) ? id : null;
      }
      if (/(^|\.)youtube\.com$/.test(url.hostname) || /(^|\.)youtube-nocookie\.com$/.test(url.hostname)) {
        let id = url.searchParams.get('v');
        if (!id) id = url.pathname.match(/\/(?:shorts|embed|live)\/([A-Za-z0-9_-]{11})/)?.[1];
        return id && /^[A-Za-z0-9_-]{11}$/.test(id) ? id : null;
      }
    } catch (_) { /* A normal search query is not a URL. */ }
    return null;
  }

  function playItem(item) {
    const video = { ...item };
    if (!video.videoId || !/^[A-Za-z0-9_-]{11}$/.test(video.videoId)) {
      setStatus('Il link non contiene un ID video valido.', true);
      return;
    }
    saveHistory(video);
    setStatus(`Apro: ${video.title}`);
    if (window.AndroidBridge && typeof window.AndroidBridge.playVideo === 'function') {
      try {
        window.AndroidBridge.playVideo(video.videoId, video.title, video.author || 'YouTube', video.thumbnail || '');
      } catch (error) {
        setStatus('Non riesco a comunicare con il player Android. Riprova.', true);
      }
    } else {
      ui.webPlayerFrame.src = `https://www.youtube.com/embed/${encodeURIComponent(video.videoId)}?autoplay=1&playsinline=1&rel=0`;
      ui.webPlayerDialog.showModal();
      setStatus('Aperta l’anteprima YouTube nel browser. La riproduzione in background richiede l’app Android.');
    }
  }

  function openOriginal(item) {
    if (window.AndroidBridge && typeof window.AndroidBridge.openOriginal === 'function') {
      window.AndroidBridge.openOriginal(item.videoId);
      return;
    }
    window.open(`https://www.youtube.com/watch?v=${encodeURIComponent(item.videoId)}`, '_blank', 'noopener,noreferrer');
  }

  function setEmpty(title, copy) {
    ui.emptyTitle.textContent = title;
    ui.emptyCopy.textContent = copy;
    ui.emptyState.hidden = false;
  }

  function renderLocalView(view) {
    const items = view === 'favs' ? getFavorites() : getHistory();
    showSection(view,
      view === 'favs' ? 'I tuoi preferiti' : 'Riprendi da dove eri',
      view === 'favs' ? 'RACCOLTA PERSONALE' : 'VISTI DI RECENTE');
    renderCards(items);
    setStatus(items.length ? `${items.length} video salvati solo su questo dispositivo.` : '');
    if (!items.length) setEmpty(view === 'favs' ? 'Ancora nessun preferito' : 'La cronologia è vuota', view === 'favs' ? 'Tocca “♡ Salva” sotto un video per ritrovarlo qui.' : 'I video che apri compariranno qui, sul dispositivo.');
  }

  ui.searchForm.addEventListener('submit', (event) => {
    event.preventDefault();
    searchVideos(ui.searchInput.value);
    ui.searchInput.blur();
  });
  ui.searchInput.addEventListener('input', () => {
    clearTimeout(suggestionTimer);
    const query = ui.searchInput.value.trim();
    if (query.length >= 2) suggestionTimer = setTimeout(() => loadSuggestions(query), 300);
  });
  ui.tabs.forEach((tab) => tab.addEventListener('click', () => {
    const view = tab.dataset.view;
    if (view === 'home') loadTrending();
    else renderLocalView(view);
  }));
  ui.refresh.addEventListener('click', () => {
    if (currentView === 'home') loadTrending();
    else if (currentView === 'search') searchVideos(lastQuery);
    else renderLocalView(currentView);
  });
  $('#open-settings').addEventListener('click', () => {
    ui.instanceInput.value = instance;
    ui.settingsDialog.showModal();
  });
  $('#footer-settings').addEventListener('click', () => {
    ui.instanceInput.value = instance;
    ui.settingsDialog.showModal();
  });
  ui.resetInstance.addEventListener('click', () => { ui.instanceInput.value = DEFAULT_INSTANCE; });
  ui.settingsForm.addEventListener('submit', (event) => {
    event.preventDefault();
    const normalized = normalizeInstance(ui.instanceInput.value);
    if (!normalized) return;
    instance = normalized;
    localStorage.setItem(INSTANCE_KEY, instance);
    ui.settingsDialog.close();
    loadTrending();
  });
  ui.webPlayerDialog.addEventListener('close', () => { ui.webPlayerFrame.src = 'about:blank'; });

  window.onNativeStatus = (message) => setStatus(String(message || ''));
  window.onNativeError = (message) => setStatus(String(message || 'Riproduzione non riuscita.'), true);
  window.onNativeApiResponse = (requestId, jsonBody, sourceInstance) => {
    const pending = pendingApiRequests.get(String(requestId));
    if (!pending) return;
    clearTimeout(pending.timer);
    pendingApiRequests.delete(String(requestId));
    try {
      const parsed = JSON.parse(String(jsonBody));
      const workingInstance = normalizeInstance(sourceInstance || instance);
      if (workingInstance !== instance) {
        instance = workingInstance;
        localStorage.setItem(INSTANCE_KEY, instance);
        setStatus(`Sorgente non disponibile; uso ${new URL(instance).host}.`);
      }
      pending.resolve(parsed);
    } catch (_) {
      pending.reject(new Error('La sorgente API ha restituito una risposta non valida.'));
    }
  };
  window.onNativeApiError = (requestId, message) => {
    const pending = pendingApiRequests.get(String(requestId));
    if (!pending) return;
    clearTimeout(pending.timer);
    pendingApiRequests.delete(String(requestId));
    pending.reject(new Error(String(message || 'Nessuna istanza Invidious disponibile.')));
  };
  updateFavoriteCount();
  loadTrending();
})();
