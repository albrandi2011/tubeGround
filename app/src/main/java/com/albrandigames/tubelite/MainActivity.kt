package com.albrandigames.tubelite

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.yausername.youtubedl_android.mapper.VideoFormat
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val engineExecutor = Executors.newSingleThreadExecutor()
    private val apiExecutor = Executors.newFixedThreadPool(3)
    private val allowedAppHost = "appassets.androidplatform.net"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()
        requestNotificationPermissionIfNeeded()

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        webView.setBackgroundColor(android.graphics.Color.rgb(15, 15, 15))
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.mediaPlaybackRequiresUserGesture = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                val uri = request?.url ?: return null
                return assetLoader.shouldInterceptRequest(uri)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // Android exposes the request as nullable in this callback on some SDK versions.
                // If absent, block navigation rather than dereferencing null or leaving the app origin.
                val uri = request?.url ?: return true
                if (uri.scheme == "https" && uri.host == allowedAppHost) return false
                // The browser UI is deliberately kept inside the bundled app; links are not
                // allowed to replace it with arbitrary remote pages.
                return true
            }
        }
        webView.addJavascriptInterface(NativeBridge(this, webView, engineExecutor, apiExecutor), "AndroidBridge")
        setContentView(webView)
        webView.loadUrl("https://$allowedAppHost/assets/www/index.html")
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4102)
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidBridge")
            webView.destroy()
        }
        engineExecutor.shutdown()
        apiExecutor.shutdownNow()
        super.onDestroy()
    }

    private class NativeBridge(
        private val activity: MainActivity,
        private val webView: WebView,
        private val executor: ExecutorService,
        private val apiExecutor: ExecutorService
    ) {
        companion object {
            // Current public instances listed by the Invidious project. The selected instance is
            // always tried first; these are fallbacks for downtime or API-level blocking.
            // Keep this list aligned with the Invidious project's current public-instance list.
            // Public instances can return 403 or disappear without notice, so try all listed
            // candidates rather than silently stopping after the first four.
            // Keep this fallback set aligned with the current canonical Invidious list.
            // Public instances are unstable; the list is deliberately short and easy to update.
            private val publicInstances = listOf(
                "https://invidious.nerdvpn.de",
                "https://inv.nadeko.net",
                "https://yt.chocolatemoo53.com",
                "https://invidious.tiekoetter.com",
                "https://invidious.f5.si"
            )
        }

        @JavascriptInterface
        fun apiGet(instanceUrl: String, pathAndQuery: String, requestId: String) {
            if (!requestId.matches(Regex("^api-[0-9]+-[0-9]+$"))) return
            if (!pathAndQuery.startsWith("/api/v1/") ||
                pathAndQuery.contains("\\") || pathAndQuery.contains("#") ||
                pathAndQuery.contains("\n") || pathAndQuery.contains("\r") ||
                pathAndQuery.substringBefore('?').split('/').any { it == "." || it == ".." }) {
                apiError(requestId, "Richiesta API non valida.")
                return
            }
            apiExecutor.execute {
                try {
                    val (body, workingInstance) = fetchInvidiousApi(instanceUrl, pathAndQuery)
                    apiResponse(requestId, body, workingInstance)
                } catch (error: Exception) {
                    apiError(requestId, error.message ?: "Nessuna istanza Invidious disponibile.")
                }
            }
        }

        private fun fetchInvidiousApi(instanceUrl: String, pathAndQuery: String): Pair<String, String> {
            val selected = normalizeApiBase(instanceUrl)
            val failures = mutableListOf<String>()
            try {
                return requestFromInstance(selected, pathAndQuery)
            } catch (error: Exception) {
                failures += error.message ?: "${Uri.parse(selected).host}: ${error.javaClass.simpleName}"
            }

            // After the user's preferred instance fails, race a small batch of official-list
            // candidates. This is much quicker than waiting for every timeout one after another.
            val candidates = publicInstances.filterNot {
                Uri.parse(it).host.equals(Uri.parse(selected).host, ignoreCase = true)
            }
            if (candidates.isEmpty()) {
                throw IOException("L'istanza selezionata non risponde. ${failures.joinToString("; ").take(300)}")
            }
            val pool = Executors.newFixedThreadPool(minOf(5, candidates.size))
            val completed = ExecutorCompletionService<Pair<String, String>>(pool)
            candidates.forEach { base -> completed.submit { requestFromInstance(base, pathAndQuery) } }
            try {
                repeat(candidates.size) {
                    try {
                        return completed.take().get()
                    } catch (error: Exception) {
                        var root: Throwable = error
                        while (root.cause != null) root = root.cause!!
                        failures += (root.message ?: root.javaClass.simpleName).take(100)
                    }
                }
            } finally {
                pool.shutdownNow()
            }
            val details = failures.distinct().joinToString("; ").take(380)
            throw IOException("Nessuna istanza Invidious ha risposto correttamente. Errori: $details. Le istanze pubbliche possono essere offline o limitare le richieste.")
        }

        private fun requestFromInstance(base: String, pathAndQuery: String): Pair<String, String> {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(base + pathAndQuery).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 1500
                    readTimeout = 3000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("User-Agent", "TubeGround-Android/0.3")
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw IOException("HTTP $status da ${Uri.parse(base).host}")
                }
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val first = body.trimStart().firstOrNull()
                if (first != '[' && first != '{') {
                    throw IOException("La sorgente ${Uri.parse(base).host} non ha restituito JSON valido.")
                }
                if (first == '{') {
                    val apiError = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    if (!apiError.isNullOrBlank()) {
                        throw IOException("${Uri.parse(base).host}: ${apiError.take(120)}")
                    }
                }
                return body to base
            } finally {
                connection?.disconnect()
            }
        }

        private fun normalizeApiBase(value: String): String {
            val uri = Uri.parse(value.trim())
            val host = uri.host?.lowercase() ?: throw IOException("Indirizzo dell'istanza non valido.")
            if (uri.scheme != "https" || host.isBlank() || uri.userInfo != null ||
                host == "localhost" || host.endsWith(".local") || !host.contains('.')) {
                throw IOException("L'istanza deve avere un dominio HTTPS valido.")
            }
            return "https://${uri.encodedAuthority}"
        }

        private fun apiResponse(requestId: String, jsonBody: String, source: String) {
            val idArg = JSONObject.quote(requestId)
            val bodyArg = JSONObject.quote(jsonBody)
            val sourceArg = JSONObject.quote(source)
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    webView.evaluateJavascript(
                        "window.onNativeApiResponse && window.onNativeApiResponse($idArg, $bodyArg, $sourceArg);", null
                    )
                }
            }
        }

        private fun apiError(requestId: String, message: String) {
            val idArg = JSONObject.quote(requestId)
            val messageArg = JSONObject.quote(message.take(400))
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    webView.evaluateJavascript(
                        "window.onNativeApiError && window.onNativeApiError($idArg, $messageArg);", null
                    )
                }
            }
        }

        @JavascriptInterface
        fun playVideo(videoId: String, fallbackTitle: String, fallbackAuthor: String, fallbackThumbnail: String) {
            if (!videoId.matches(Regex("^[A-Za-z0-9_-]{11}$"))) {
                callback("onNativeError", "ID del video non valido.")
                return
            }
            callback("onNativeStatus", "Sto risolvendo il flusso audio/video…")
            executor.execute {
                try {
                    // Initialize lazily: extracting the bundled Python runtime can take a moment.
                    YoutubeDL.init(activity.applicationContext)
                    // Resolve metadata and direct stream URLs only. Updates are queued after a
                    // successful resolution so the first frame is not delayed by a binary download.
                    val infoRequest = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
                    infoRequest.addOption("--no-playlist")
                    infoRequest.addOption("--no-warnings")
                    // yt-dlp can become stale when YouTube changes its player/API. If extraction
                    // fails, refresh the bundled engine once (with a cooldown) and retry before
                    // declaring this video unavailable.
                    val info = getInfoWithRefresh(infoRequest)
                    val title = info.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
                    val author = info.uploader?.takeIf { it.isNotBlank() } ?: fallbackAuthor
                    val thumbnail = info.thumbnail?.takeIf { it.startsWith("https://") } ?: fallbackThumbnail

                    val progressiveFormat = chooseProgressiveFormat(info)
                    var directUrl: String? = null
                    var directHeaders = "{}"
                    var directVideoUrl: String? = null
                    var directAudioUrl: String? = null
                    var directVideoHeaders = "{}"
                    var directAudioHeaders = "{}"

                    if (progressiveFormat?.url?.startsWith("https://") == true) {
                        directUrl = progressiveFormat.url
                        directHeaders = JSONObject(progressiveFormat.httpHeaders ?: emptyMap<String, String>()).toString()
                    } else {
                        val tracks = chooseSeparateFormats(info)
                            ?: throw IllegalStateException("Questo video non espone formati audio/video compatibili con il player.")
                        directVideoUrl = tracks.first.url
                            ?: throw IllegalStateException("URL della traccia video non disponibile.")
                        directAudioUrl = tracks.second.url
                            ?: throw IllegalStateException("URL della traccia audio non disponibile.")
                        directVideoHeaders = JSONObject(tracks.first.httpHeaders ?: emptyMap<String, String>()).toString()
                        directAudioHeaders = JSONObject(tracks.second.httpHeaders ?: emptyMap<String, String>()).toString()
                        callback("onNativeStatus", "Collego direttamente audio e video… non devo scaricare né costruire un file temporaneo.")
                    }

                    activity.runOnUiThread {
                        try {
                            val intent = Intent(activity, PlaybackService::class.java)
                                .setAction(PlaybackService.ACTION_PLAY)
                                .putExtra(PlaybackService.EXTRA_URL, directUrl)
                                .putExtra(PlaybackService.EXTRA_HEADERS, directHeaders)
                                .putExtra(PlaybackService.EXTRA_VIDEO_URL, directVideoUrl)
                                .putExtra(PlaybackService.EXTRA_AUDIO_URL, directAudioUrl)
                                .putExtra(PlaybackService.EXTRA_VIDEO_HEADERS, directVideoHeaders)
                                .putExtra(PlaybackService.EXTRA_AUDIO_HEADERS, directAudioHeaders)
                                .putExtra(PlaybackService.EXTRA_TITLE, title)
                                .putExtra(PlaybackService.EXTRA_ARTIST, author)
                                .putExtra(PlaybackService.EXTRA_ARTWORK, thumbnail)
                            ContextCompat.startForegroundService(activity, intent)
                            PlayerActivity.start(activity, videoId)
                            callback("onNativeStatus", "Flusso trovato; avvio il player…")
                            scheduleExtractorUpdate()
                        } catch (e: Exception) {
                            callback("onNativeError", "Impossibile avviare il player: ${safeMessage(e)}")
                        }
                    }
                } catch (e: Exception) {
                    callback("onNativeError", "Estrazione non riuscita: ${safeMessage(e)}")
                }
            }
        }

        private fun getInfoWithRefresh(request: YoutubeDLRequest): VideoInfo {
            try {
                return YoutubeDL.getInfo(request)
            } catch (firstError: Exception) {
                val preferences = activity.getSharedPreferences("extractor-update", Activity.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val lastFailureRefresh = preferences.getLong("last_failure_refresh", 0L)
                if (now - lastFailureRefresh < 60L * 60L * 1000L) {
                    throw firstError
                }

                callback("onNativeStatus", "L'estrattore YouTube potrebbe essere obsoleto. Lo aggiorno e riprovo…")
                try {
                    YoutubeDL.updateYoutubeDL(activity.applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
                    preferences.edit()
                        .putLong("last_failure_refresh", now)
                        .putLong("last_checked", now)
                        .apply()
                } catch (updateError: Exception) {
                    preferences.edit().putLong("last_failure_refresh", now).apply()
                    updateError.addSuppressed(firstError)
                    throw IOException(
                        "Non riesco ad aggiornare l'estrattore YouTube. Controlla la connessione e riprova.",
                        updateError
                    )
                }

                return try {
                    YoutubeDL.getInfo(request)
                } catch (retryError: Exception) {
                    retryError.addSuppressed(firstError)
                    throw IOException(
                        "YouTube non ha restituito uno stream dopo l'aggiornamento. Potrebbe essere un video non disponibile o un problema temporaneo di YouTube.",
                        retryError
                    )
                }
            }
        }

        private fun chooseProgressiveFormat(info: VideoInfo): VideoFormat? {
            val formats = info.formats.orEmpty().filter { format ->
                val formatUrl = format.url
                val videoCodec = format.vcodec
                val audioCodec = format.acodec
                !formatUrl.isNullOrBlank() && formatUrl.startsWith("https://") &&
                    hasCodec(videoCodec) && hasCodec(audioCodec)
            }
            if (formats.isEmpty()) return null

            // Start quickly on mobile: prefer progressive AVC/AAC and cap the normal choice at 720p.
            val mp4AvcAac = formats.filter {
                it.ext.equals("mp4", ignoreCase = true) &&
                    it.vcodec?.startsWith("avc1", ignoreCase = true) == true &&
                    it.acodec?.startsWith("mp4a", ignoreCase = true) == true
            }
            val candidates = if (mp4AvcAac.isNotEmpty()) mp4AvcAac else formats
            val targetPool = candidates.filter { it.height in 1..720 }
                .ifEmpty { candidates.filter { it.height in 1..1080 } }
                .ifEmpty { candidates }
            return targetPool.maxWithOrNull(compareBy<VideoFormat> { it.height }.thenBy { it.tbr })
        }

        private fun chooseSeparateFormats(info: VideoInfo): Pair<VideoFormat, VideoFormat>? {
            val formats = info.formats.orEmpty().filter { format ->
                // Copy the public nullable property to a local val so Kotlin can smart-cast it.
                val formatUrl = format.url
                !formatUrl.isNullOrBlank() && formatUrl.startsWith("https://")
            }
            val videoOnly = formats.filter { hasCodec(it.vcodec) && !hasCodec(it.acodec) }
            val audioOnly = formats.filter { !hasCodec(it.vcodec) && hasCodec(it.acodec) }
            if (videoOnly.isEmpty() || audioOnly.isEmpty()) return null

            val preferredVideo = videoOnly.filter {
                it.ext.equals("mp4", ignoreCase = true) &&
                    it.vcodec?.startsWith("avc1", ignoreCase = true) == true
            }.ifEmpty { videoOnly }
            val videoPool = preferredVideo.filter { it.height in 1..720 }
                .ifEmpty { preferredVideo.filter { it.height in 1..1080 } }
                .ifEmpty { preferredVideo }
            val video = videoPool.maxWithOrNull(compareBy<VideoFormat> { it.height }.thenBy { it.tbr })
                ?: return null

            val preferredAudio = audioOnly.filter {
                it.ext.equals("m4a", ignoreCase = true) &&
                    it.acodec?.startsWith("mp4a", ignoreCase = true) == true
            }.ifEmpty { audioOnly }
            // A moderate AAC stream starts faster on mobile without a noticeable voice/music penalty.
            val audio = preferredAudio.minByOrNull { format ->
                val bitrate = format.abr.takeIf { it > 0 } ?: format.tbr
                kotlin.math.abs(bitrate - 128)
            } ?: return null
            return video to audio
        }

        private fun hasCodec(codec: String?): Boolean =
            !codec.isNullOrBlank() && !codec.equals("none", ignoreCase = true)

        private fun scheduleExtractorUpdate() {
            // Queue the periodic update behind the current extraction so it never delays first playback.
            executor.execute {
                val preferences = activity.getSharedPreferences("extractor-update", Activity.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val lastCheck = preferences.getLong("last_checked", 0L)
                if (now - lastCheck < 24L * 60L * 60L * 1000L) return@execute
                try {
                    YoutubeDL.updateYoutubeDL(activity.applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
                } catch (_: Exception) {
                    // Best effort. Keep the working bundled binary if the network is unavailable.
                } finally {
                    preferences.edit().putLong("last_checked", now).apply()
                }
            }
        }

        @JavascriptInterface
        fun openOriginal(videoId: String) {
            if (!videoId.matches(Regex("^[A-Za-z0-9_-]{11}$"))) return
            activity.runOnUiThread {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")))
                } catch (_: Exception) {
                    Toast.makeText(activity, "Impossibile aprire YouTube", Toast.LENGTH_SHORT).show()
                }
            }
        }

        private fun safeMessage(error: Throwable): String {
            val raw = error.message?.replace(Regex("https?://\\S+"), "[URL]")?.take(180)
            return raw ?: error.javaClass.simpleName
        }

        private fun callback(functionName: String, message: String) {
            val encoded = JSONObject.quote(message)
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    webView.evaluateJavascript("window.$functionName && window.$functionName($encoded);", null)
                }
            }
        }
    }
}
