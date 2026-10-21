package com.albrandigames.tubelite

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var playerView: PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = true
            controllerAutoShow = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        playerView = view
        root.addView(view)

        val errorMessage = TextView(this).apply {
            text = "Questo flusso non è riproducibile al momento."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
        }
        val openYouTube = Button(this).apply {
            text = "Apri su YouTube"
            setOnClickListener {
                val videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
                if (videoId.matches(Regex("^[A-Za-z0-9_-]{11}$"))) {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$videoId")))
                }
            }
        }
        val errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            addView(errorMessage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(openYouTube, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            visibility = View.GONE
        }
        root.addView(errorPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })
        setContentView(root)

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val controller = future.get()
                view.player = controller
                controller.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        errorMessage.text = "Il flusso non si è caricato. Potrebbe essere scaduto o non compatibile con questo dispositivo."
                        errorPanel.visibility = View.VISIBLE
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) errorPanel.visibility = View.GONE
                    }
                })
            } catch (e: Exception) {
                val message = TextView(this).apply {
                    text = "Non riesco ad avviare la riproduzione. Torna indietro e riprova."
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setPadding(32, 32, 32, 32)
                }
                root.addView(message)
            }
        }, ContextCompat.getMainExecutor(this))
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

    override fun onDestroy() {
        playerView?.player = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        playerView = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_VIDEO_ID = "video_id"

        fun start(context: Context, videoId: String) {
            context.startActivity(Intent(context, PlayerActivity::class.java).putExtra(EXTRA_VIDEO_ID, videoId))
        }
    }
}
