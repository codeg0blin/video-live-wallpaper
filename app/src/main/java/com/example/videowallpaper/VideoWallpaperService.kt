package com.example.videowallpaper

import android.content.SharedPreferences
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.preference.PreferenceManager

/**
 * Renders the user's chosen video as a looping live wallpaper.
 *
 * Android creates a new Engine instance per surface that needs the wallpaper
 * (home screen, lock screen, or both — depending on OEM/launcher behavior),
 * so each Engine owns its own MediaPlayer to avoid cross-talk between them.
 */
class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    inner class VideoEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private var mediaPlayer: MediaPlayer? = null
        private var prefs: SharedPreferences? = null
        private var visible = false

        // Kept so the pref listener can trigger a re-prepare with the
        // correct surface without waiting for the next onSurfaceCreated.
        private var currentHolder: SurfaceHolder? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs = PreferenceManager.getDefaultSharedPreferences(this@VideoWallpaperService)
            prefs?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            currentHolder = holder
            preparePlayer(holder)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            val player = mediaPlayer ?: return
            // Route through applySpeed rather than calling start()/pause()
            // directly here. Having two independent call sites (this one and
            // the one inside preparePlayer's onPreparedListener) both touching
            // start()/pause() on the same MediaPlayer was racy — if a visibility
            // change and a prepare-completion landed close together, they could
            // stack an extra start() on top of one that was still resolving,
            // which some OEM MediaPlayer implementations (observed on
            // Adreno/TCL) turn into error -38. Funneling everything through
            // applySpeed keeps state transitions to one guarded path.
            val speed = prefs?.getFloat(PREF_PLAYBACK_SPEED, DEFAULT_SPEED) ?: DEFAULT_SPEED
            applySpeed(player, speed)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            currentHolder = holder
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            currentHolder = null
            releasePlayer()
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs?.unregisterOnSharedPreferenceChangeListener(this)
            releasePlayer()
        }

        /**
         * Called whenever any SharedPreference changes — including writes from
         * MainActivity when the user adjusts speed, crop mode, or picks a new video.
         * Speed is applied cheaply to the running player; everything else needs a
         * full re-prepare since MediaPlayer doesn't support hot-swapping video source
         * or scaling mode mid-playback.
         */
        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            val holder = currentHolder ?: return
            when (key) {
                PREF_PLAYBACK_SPEED -> {
                    // Speed can be applied to the running player without re-preparing.
                    val speed = sharedPreferences?.getFloat(PREF_PLAYBACK_SPEED, DEFAULT_SPEED)
                        ?: DEFAULT_SPEED
                    mediaPlayer?.let { applySpeed(it, speed) }
                }
                PREF_SCALING_MODE, PREF_VIDEO_URI -> {
                    // Scaling mode and video URI both require a full re-prepare.
                    preparePlayer(holder)
                }
            }
        }

        private fun preparePlayer(holder: SurfaceHolder) {
            releasePlayer()

            val uriString = prefs?.getString(PREF_VIDEO_URI, null)
            if (uriString.isNullOrBlank()) return
            val uri = try {
                Uri.parse(uriString)
            } catch (e: Exception) {
                Log.e(TAG, "Stored video URI was malformed", e)
                return
            }
            val speed = prefs?.getFloat(PREF_PLAYBACK_SPEED, DEFAULT_SPEED) ?: DEFAULT_SPEED
            val scalingMode = prefs?.getInt(PREF_SCALING_MODE, DEFAULT_SCALING_MODE) ?: DEFAULT_SCALING_MODE

            // Confirm we still hold read permission before touching MediaPlayer.
            // Permission can be revoked out from under us at any time (user
            // action in system settings, storage change, etc.), and without
            // this check a revoked URI fails silently deep inside setDataSource
            // with no way for the user to tell why their wallpaper went blank.
            val stillGranted = try {
                applicationContext.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check persisted URI permissions", e)
                false
            }
            if (!stillGranted) {
                Log.w(TAG, "Lost read permission for stored video URI — clearing selection")
                prefs?.edit()?.remove(PREF_VIDEO_URI)?.apply()
                return
            }

            try {
                val player = MediaPlayer()
                player.setDataSource(applicationContext, uri)
                player.setSurface(holder.surface)
                player.isLooping = true
                player.setVolume(0f, 0f) // wallpapers should be silent
                player.setVideoScalingMode(scalingMode)
                player.setOnPreparedListener {
                    applySpeed(it, speed)
                }
                player.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    // Not every MediaPlayer error means the file itself is bad —
                    // state-machine hiccups (e.g. what=-38, MEDIA_ERROR_UNSUPPORTED
                    // from an out-of-order start/pause call) are transient and the
                    // next preparePlayer() call will recover fine. Only clear the
                    // stored selection for errors that mean the source itself is
                    // unusable, so we don't wipe a perfectly good video over a
                    // one-off playback glitch.
                    if (what == MediaPlayer.MEDIA_ERROR_UNKNOWN || what == -38) {
                        Log.w(TAG, "Transient MediaPlayer error — leaving selection intact")
                    } else {
                        prefs?.edit()?.remove(PREF_VIDEO_URI)?.apply()
                    }
                    true
                }
                player.prepareAsync()
                mediaPlayer = player
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare video wallpaper", e)
                prefs?.edit()?.remove(PREF_VIDEO_URI)?.apply()
            }
        }

        /**
         * Single guarded entry point for all player state transitions
         * (start/pause) and speed changes. Called from both onPreparedListener
         * (right after prepare completes) and onVisibilityChanged (whenever the
         * wallpaper surface becomes visible/hidden). Must be safe to call
         * repeatedly and in any order — checking isPlaying before calling
         * start() avoids restarting an already-running player, which is what
         * previously caused error -38 on some OEM MediaPlayer implementations
         * (observed on Adreno/TCL) when two call sites raced.
         */
        private fun applySpeed(player: MediaPlayer, speed: Float) {
            try {
                if (visible) {
                    if (!player.isPlaying) player.start()
                    if (speed != 1.0f) {
                        player.playbackParams = PlaybackParams().setSpeed(speed)
                    }
                } else {
                    // PlaybackParams can only be set on a running player on some
                    // OEM implementations, so briefly start, apply, then pause
                    // if we're not actually meant to be visible yet.
                    if (speed != 1.0f && !player.isPlaying) {
                        player.start()
                        player.playbackParams = PlaybackParams().setSpeed(speed)
                    }
                    if (player.isPlaying) player.pause()
                }
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

