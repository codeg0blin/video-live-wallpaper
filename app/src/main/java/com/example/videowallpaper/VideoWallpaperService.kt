package com.example.videowallpaper

import android.content.SharedPreferences
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.preference.PreferenceManager
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder

/**
 * Renders the user's chosen video as a looping live wallpaper.
 *
 * Android creates a new Engine instance per surface that needs the wallpaper
 * (home screen, lock screen, or both — depending on OEM/launcher behavior),
 * so each Engine owns its own MediaPlayer to avoid cross-talk between them.
 */
class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    inner class VideoEngine : Engine() {

        private var mediaPlayer: MediaPlayer? = null
        private var prefs: SharedPreferences? = null
        private var surfaceReady = false
        private var visible = false

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs = PreferenceManager.getDefaultSharedPreferences(this@VideoWallpaperService)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            preparePlayer(holder)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            val player = mediaPlayer
            if (player == null) return
            try {
                if (isVisible) {
                    player.start()
                } else {
                    player.pause()
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Player not in a valid state for visibility change", e)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            // Surface size changed (e.g. rotation) — scaling mode is re-applied on
            // every prepare, so nothing extra needed here.
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            surfaceReady = false
            releasePlayer()
        }

        override fun onDestroy() {
            super.onDestroy()
            releasePlayer()
        }

        private fun preparePlayer(holder: SurfaceHolder) {
            releasePlayer()

            val uriString = prefs?.getString(PREF_VIDEO_URI, null) ?: return
            val uri = Uri.parse(uriString)
            val speed = prefs?.getFloat(PREF_PLAYBACK_SPEED, DEFAULT_SPEED) ?: DEFAULT_SPEED
            val scalingMode = prefs?.getInt(PREF_SCALING_MODE, DEFAULT_SCALING_MODE) ?: DEFAULT_SCALING_MODE

            try {
                val player = MediaPlayer()
                player.setDataSource(applicationContext, uri)
                player.setSurface(holder.surface)
                player.isLooping = true
                player.setVolume(0f, 0f) // wallpapers should be silent
                player.setVideoScalingMode(scalingMode)
                player.setOnPreparedListener {
                    applySpeed(it, speed)
                    if (visible) {
                        it.start()
                    }
                }
                player.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    true
                }
                player.prepareAsync()
                mediaPlayer = player
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare video wallpaper", e)
            }
        }

        /**
         * Speed must be applied via start() on some OEM implementations — setting
         * PlaybackParams before the player has ever started can silently no-op or
         * throw on certain devices. Starting first, then applying speed, then
         * pausing again if not yet visible, is the safe order.
         */
        private fun applySpeed(player: MediaPlayer, speed: Float) {
            if (speed == 1.0f) return
            try {
                player.start()
                player.playbackParams = PlaybackParams().setSpeed(speed)
                if (!visible) player.pause()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not apply playback speed on this device", e)
            }
        }

        private fun releasePlayer() {
            mediaPlayer?.let {
                try {
                    if (it.isPlaying) it.stop()
                } catch (e: IllegalStateException) {
                    // already stopped/released — safe to ignore
                }
                it.release()
            }
            mediaPlayer = null
        }
    }

    companion object {
        private const val TAG = "VideoWallpaperService"
        const val PREF_VIDEO_URI = "selected_video_uri"
        const val PREF_PLAYBACK_SPEED = "playback_speed"
        const val PREF_SCALING_MODE = "scaling_mode"
        const val DEFAULT_SPEED = 1.0f
        const val DEFAULT_SCALING_MODE = MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
    }
}

