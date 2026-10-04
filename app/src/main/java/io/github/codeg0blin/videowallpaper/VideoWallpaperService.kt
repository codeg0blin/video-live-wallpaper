package io.github.codeg0blin.videowallpaper

import android.content.SharedPreferences
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
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

        // Counts consecutive prepare attempts that ended in a "transient"
        // error in a row, so a genuinely broken source (not just a one-off
        // state-machine hiccup) can't retry forever and spin the CPU.
        private var consecutiveErrorRetries = 0

        // Tracks whether *we* believe the player is currently started, kept
        // independently of MediaPlayer.isPlaying(). On some OEM MediaPlayer
        // implementations (observed here) isPlaying() can lag behind the
        // actual native state transition by a short window, so relying on it
        // alone to guard against a duplicate start() call isn't reliable —
        // two onVisibilityChanged(true) calls landing close together (a known
        // quirk during unlock animations on some launchers) could both see
        // isPlaying()==false and both call start(), producing error -38.
        private var weBelieveStarted = false

        // Kept so the pref listener can trigger a re-prepare with the
        // correct surface without waiting for the next onSurfaceCreated.
        private var currentHolder: SurfaceHolder? = null

        // Owns the EGL/GL pipeline that draws video frames with real Fill/Fit
        // crop-or-letterbox math (see VideoFrameRenderer) — replaces the old
        // direct MediaPlayer-to-Surface path, since setVideoScalingMode() is
        // a no-op on a WallpaperService Engine's surface (issue #1).
        private var renderer: VideoFrameRenderer? = null

        // All renderer/EGL calls (start, drawFrame, release) must happen on
        // this single thread — EGL contexts are thread-confined. Created in
        // onSurfaceCreated, quit in onSurfaceDestroyed.
        private var renderThread: HandlerThread? = null
        private var renderHandler: Handler? = null

        // Draws exactly one frame using the current holder/player state.
        // Posted either by VideoFrameRenderer's onFrameAvailableListener
        // (normal case — a new decoded frame is ready) or directly by
        // startDrawLoopIfVisible() for a one-shot "redraw the last frame"
        // kick after a resize or becoming visible again, since a resize
        // alone doesn't produce a new video frame but does need a redraw
        // at the new viewport size.
        //
        // Deliberately NOT a fixed-rate timer: drawing unconditionally at
        // ~60fps regardless of whether a new frame had arrived wasted GPU
        // work on every redundant redraw of an unchanged frame, which adds
        // up for a continuously-running live wallpaper. Driving off actual
        // frame arrival means we draw only as often as the video itself
        // produces new frames.
        private val drawFrameRunnable = Runnable {
            if (!visible) return@Runnable
            val r = renderer ?: return@Runnable
            val holder = currentHolder ?: return@Runnable
            val player = mediaPlayer
            r.drawFrame(
                holder.surfaceFrame.width(),
                holder.surfaceFrame.height(),
                player?.let { if (weBelieveStarted || it.isPlaying) it.videoWidth else 0 } ?: 0,
                player?.let { if (weBelieveStarted || it.isPlaying) it.videoHeight else 0 } ?: 0
            )
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs = PreferenceManager.getDefaultSharedPreferences(this@VideoWallpaperService)
            prefs?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            currentHolder = holder
            consecutiveErrorRetries = 0

            val thread = HandlerThread("VideoFrameRenderer").also { it.start() }
            renderThread = thread
            val handler = Handler(thread.looper)
            renderHandler = handler
            handler.post {
                val r = VideoFrameRenderer()
                if (r.start(holder.surface)) {
                    renderer = r
                    applyScalingModePref()
                    // Draw exactly when a new frame arrives, rather than
                    // polling on a fixed timer. The listener itself may
                    // fire on an arbitrary thread (per SurfaceTexture's
                    // docs), so hop back onto our own render thread via
                    // renderHandler before touching the renderer/GL state.
                    r.onFrameAvailableListener = {
                        renderHandler?.post(drawFrameRunnable)
                    }
                } else {
                    Log.e(TAG, "VideoFrameRenderer failed to initialize — falling back to no crop/fit support")
                }
                // preparePlayer() reads renderer.getVideoInputSurface(), so
                // it must run after renderer is set (or confirmed failed)
                // above — not just "hopefully after", since this whole
                // block runs on the render thread while preparePlayer()
                // touches MediaPlayer, which needs to stay on the main
                // thread. Post back to main rather than calling directly.
                android.os.Handler(mainLooper).post {
                    preparePlayer(holder)
                }
            }
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) {
                startDrawLoopIfVisible()
            } else {
                stopDrawLoop()
            }
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
            // Resize alone doesn't produce a new video frame, but the
            // viewport dimensions changed, so geometry needs recomputing
            // (handled inside drawFrame's cache-invalidation check) and
            // the screen needs a redraw at the new size.
            startDrawLoopIfVisible()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            currentHolder = null
            releasePlayer()
            releaseRenderer()
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs?.unregisterOnSharedPreferenceChangeListener(this)
            releasePlayer()
            releaseRenderer()
        }

        /**
         * Stops the draw loop, releases the renderer's EGL/GL resources (on
         * the render thread, since EGL calls are thread-confined), then
         * quits the render thread itself. Safe to call even if nothing was
         * ever successfully initialized.
         */
        private fun releaseRenderer() {
            stopDrawLoop()
            val handler = renderHandler
            val r = renderer
            renderer = null
            if (handler != null && r != null) {
                handler.post { r.release() }
            }
            renderThread?.quitSafely()
            renderThread = null
            renderHandler = null
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
                PREF_SCALING_MODE -> {
                    // Unlike video URI, scaling mode doesn't need a full
                    // MediaPlayer re-prepare now that it's handled by the
                    // renderer's geometry rather than MediaPlayer itself —
                    // just update the renderer's mode on its own thread.
                    val handler = renderHandler
                    if (handler != null) {
                        handler.post { applyScalingModePref() }
                    }
                }
                PREF_VIDEO_URI -> {
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
                // Target the renderer's input Surface (backed by a
                // SurfaceTexture), not holder.surface directly. MediaPlayer
                // decodes into that texture; VideoFrameRenderer draws it to
                // holder.surface itself with real Fill/Fit crop/letterbox
                // math, since setVideoScalingMode() has no effect on a
                // WallpaperService Engine's surface (issue #1) — kept below
                // only as an intentional no-op fallback, see comment there.
                val inputSurface = renderer?.getVideoInputSurface()
                if (inputSurface != null) {
                    player.setSurface(inputSurface)
                } else {
                    // Renderer not ready yet (still initializing on the
                    // render thread, or failed to start) — fall back to the
                    // raw surface so video still plays, just without
                    // crop/fit support, rather than showing nothing.
                    Log.w(TAG, "Renderer not ready — falling back to raw surface (no crop/fit)")
                    player.setSurface(holder.surface)
                }
                player.isLooping = true
                player.setVolume(0f, 0f) // wallpapers should be silent
                player.setOnPreparedListener {
                    consecutiveErrorRetries = 0
                    applySpeed(it, speed)
                    startDrawLoopIfVisible()
                }
                player.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    // The player is unusable after any error — it's stuck in
                    // MEDIA_PLAYER_STATE_ERROR and further start()/pause() calls
                    // against it (e.g. from a later onVisibilityChanged) will just
                    // keep re-triggering the same error in a loop. Release it and
                    // clear the reference immediately so nothing else can touch
                    // this broken instance; only preparePlayer() (triggered here
                    // for the source-error case, or by the next onSurfaceCreated/
                    // pref change) creates a new, working one.
                    releasePlayer()
                    // Not every MediaPlayer error means the file itself is bad —
                    // state-machine hiccups (e.g. what=-38, MEDIA_ERROR_UNSUPPORTED
                    // from an out-of-order start/pause call) are transient and a
                    // fresh preparePlayer() call will recover fine. Only clear the
                    // stored selection for errors that mean the source itself is
                    // unusable, so we don't wipe a perfectly good video over a
                    // one-off playback glitch.
                    if (what == MediaPlayer.MEDIA_ERROR_UNKNOWN || what == -38) {
                        consecutiveErrorRetries++
                        if (consecutiveErrorRetries <= MAX_CONSECUTIVE_ERROR_RETRIES) {
                            Log.w(TAG, "Transient MediaPlayer error — retrying prepare " +
                                    "($consecutiveErrorRetries/$MAX_CONSECUTIVE_ERROR_RETRIES), selection kept")
                            currentHolder?.let { preparePlayer(it) }
                        } else {
                            Log.e(TAG, "Gave up after $MAX_CONSECUTIVE_ERROR_RETRIES consecutive " +
                                    "errors — leaving selection intact but not retrying further")
                        }
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
         * (start/pause) and speed changes. Called from onPreparedListener,
         * onVisibilityChanged, and onSharedPreferenceChanged (speed changes)
         * — must be safe to call repeatedly and in any order, since any two
         * of these can land close together. Guards against duplicate start()
         * calls using our own weBelieveStarted flag rather than
         * MediaPlayer.isPlaying(), since isPlaying() was observed to lag
         * behind the actual native state transition on this device closely
         * enough for two back-to-back calls to both see it as false.
         */
        private fun applySpeed(player: MediaPlayer, speed: Float) {
            try {
                if (visible) {
                    if (!weBelieveStarted) {
                        player.start()
                        weBelieveStarted = true
                    }
                    if (speed != 1.0f) {
                        player.playbackParams = PlaybackParams().setSpeed(speed)
                    }
                } else {
                    // PlaybackParams can only be set on a running player on some
                    // OEM implementations, so briefly start, apply, then pause
                    // if we're not actually meant to be visible yet.
                    if (speed != 1.0f && !weBelieveStarted) {
                        player.start()
                        weBelieveStarted = true
                        player.playbackParams = PlaybackParams().setSpeed(speed)
                    }
                    if (weBelieveStarted) {
                        player.pause()
                        weBelieveStarted = false
                    }
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not apply playback speed on this device", e)
            }
        }

        /**
         * Reads PREF_SCALING_MODE and applies it to the renderer, mapping
         * from MediaPlayer's int constants (what the pref/UI already use)
         * to our ScalingMode enum. Safe to call even if renderer isn't
         * ready yet — it's a no-op until the next time it's called after
         * the renderer exists.
         */
        private fun applyScalingModePref() {
            val r = renderer ?: return
            val modeInt = prefs?.getInt(PREF_SCALING_MODE, DEFAULT_SCALING_MODE) ?: DEFAULT_SCALING_MODE
            val mode = if (modeInt == MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) {
                VideoFrameRenderer.ScalingMode.FIT
            } else {
                // Default/VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING and
                // any unrecognized value both fall back to FILL, matching
                // DEFAULT_SCALING_MODE's existing crop-based default below.
                VideoFrameRenderer.ScalingMode.FILL
            }
            r.setScalingMode(mode)
        }

        /**
         * Posts a single redraw if we're visible. Normal frame updates are
         * driven by VideoFrameRenderer's onFrameAvailableListener now, not
         * this — this exists only for the cases where the screen needs a
         * redraw but a new video frame hasn't necessarily arrived: becoming
         * visible again (the last frame may be stale/never drawn at the
         * current size) and after preparePlayer's onPreparedListener fires
         * (first frame may already be available by then). Safe to call
         * repeatedly; each call just posts one more draw.
         */
        private fun startDrawLoopIfVisible() {
            if (!visible) return
            renderHandler?.post(drawFrameRunnable)
        }

        /** No-op now that drawing is event-driven rather than a loop —
         * kept so call sites (onVisibilityChanged) don't need to change,
         * and as a hook if a pending one-shot draw ever needs cancelling.
         */
        private fun stopDrawLoop() {
            renderHandler?.removeCallbacks(drawFrameRunnable)
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
            weBelieveStarted = false
        }
    }

    companion object {
        private const val TAG = "VideoWallpaperService"
        private const val MAX_CONSECUTIVE_ERROR_RETRIES = 3
        const val PREF_VIDEO_URI = "selected_video_uri"
        const val PREF_PLAYBACK_SPEED = "playback_speed"
        const val PREF_SCALING_MODE = "scaling_mode"
        const val DEFAULT_SPEED = 1.0f
        const val DEFAULT_SCALING_MODE = MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
    }
}