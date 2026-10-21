package com.albrandigames.tubelite

import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONObject

class PlaybackService : MediaSessionService() {
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        exoPlayer = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, exoPlayer!!).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serviceResult = super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_PLAY) {
            val singleUrl = intent.getStringExtra(EXTRA_URL)
            val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
            val audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL)
            val hasSingleStream = singleUrl?.startsWith("https://") == true
            val hasSeparateStreams = videoUrl?.startsWith("https://") == true &&
                audioUrl?.startsWith("https://") == true

            if (hasSingleStream || hasSeparateStreams) {
                val metadata = MediaMetadata.Builder()
                    .setTitle(intent.getStringExtra(EXTRA_TITLE) ?: "TubeGround")
                    .setArtist(intent.getStringExtra(EXTRA_ARTIST) ?: "YouTube")
                    .apply {
                        val artwork = intent.getStringExtra(EXTRA_ARTWORK)
                        if (!artwork.isNullOrBlank() && artwork.startsWith("https://")) {
                            setArtworkUri(Uri.parse(artwork))
                        }
                    }
                    .build()

                val source = if (hasSingleStream) {
                    createMediaSource(
                        url = singleUrl!!,
                        headersJson = intent.getStringExtra(EXTRA_HEADERS),
                        metadata = metadata
                    )
                } else {
                    // Most YouTube qualities are separate DASH video/audio streams. Merge them
                    // in ExoPlayer so playback can begin immediately, without downloading the
                    // complete video and running FFmpeg first.
                    val videoSource = createMediaSource(
                        url = videoUrl!!,
                        headersJson = intent.getStringExtra(EXTRA_VIDEO_HEADERS),
                        metadata = metadata
                    )
                    val audioSource = createMediaSource(
                        url = audioUrl!!,
                        headersJson = intent.getStringExtra(EXTRA_AUDIO_HEADERS),
                        metadata = null
                    )
                    MergingMediaSource(videoSource, audioSource)
                }

                exoPlayer?.apply {
                    setMediaSource(source)
                    prepare()
                    playWhenReady = true
                }
            }
        } else if (intent?.action == ACTION_STOP) {
            exoPlayer?.stop()
            stopSelf()
        }
        return serviceResult
    }

    private fun createMediaSource(
        url: String,
        headersJson: String?,
        metadata: MediaMetadata?
    ): MediaSource {
        val headers = parseHeaders(headersJson)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("TubeGround-Android/0.3")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val itemBuilder = MediaItem.Builder().setUri(url)
        if (metadata != null) itemBuilder.setMediaMetadata(metadata)
        return DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(itemBuilder.build())
    }

    private fun parseHeaders(rawJson: String?): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        runCatching {
            val json = JSONObject(rawJson ?: "{}")
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key)
                if (key.isNotBlank() && value.isNotBlank()) headers[key] = value
            }
        }
        return headers
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "com.albrandigames.tubelite.action.PLAY"
        const val ACTION_STOP = "com.albrandigames.tubelite.action.STOP"
        const val EXTRA_URL = "stream_url"
        const val EXTRA_HEADERS = "stream_headers"
        const val EXTRA_VIDEO_URL = "stream_video_url"
        const val EXTRA_AUDIO_URL = "stream_audio_url"
        const val EXTRA_VIDEO_HEADERS = "stream_video_headers"
        const val EXTRA_AUDIO_HEADERS = "stream_audio_headers"
        const val EXTRA_TITLE = "media_title"
        const val EXTRA_ARTIST = "media_artist"
        const val EXTRA_ARTWORK = "media_artwork"
    }
}
