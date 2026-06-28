package com.example.videowallpaper

import android.content.SharedPreferences
import android.media.MediaPlayer
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
            // Surface size changed (e.g. rotation) — video scaling is handled by
            // VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING, so nothing extra needed here.
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

            try {
                val player = MediaPlayer()
                player.setDataSource(applicationContext, uri)
                player.setSurface(holder.surface)
                player.isLooping = true
                player.setVolume(0f, 0f) // wallpapers should be silent
                player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                player.setOnPreparedListener {
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
    }
}
