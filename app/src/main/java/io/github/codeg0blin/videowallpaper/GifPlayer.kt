package io.github.codeg0blin.videowallpaper

import android.os.Handler

/**
 * Plays a decoded [GifDecoder] by scheduling one frame at a time on the
 * wallpaper's render thread ([handler]'s thread).
 *
 * All real work (decoding the next frame, calling [onFrame]) happens on that
 * one thread, which is also the thread that owns the EGL context, so
 * [onFrame] can safely upload and draw with OpenGL. The public methods may be
 * called from any thread: they only post work to [handler] (speed is a
 * volatile field).
 *
 * [onFrame] returns true to keep playing or false to stop for good (for
 * example when GL setup failed), so a broken frame path doesn't burn CPU
 * decoding frames that can never be shown.
 *
 * Single-frame GIFs draw once and never schedule another tick.
 */
class GifPlayer(
    private val handler: Handler,
    private val decoder: GifDecoder,
    private val onFrame: (GifDecoder) -> Boolean
) {

    @Volatile
    private var speed: Float = 1.0f

    // Only touched on the render thread.
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val frameDelayMs = decoder.advance()
            if (!onFrame(decoder)) {
                running = false
                return
            }
            if (decoder.frameCount > 1) {
                handler.postDelayed(this, scaledDelay(frameDelayMs))
            }
        }
    }

    /** Playback speed multiplier (1.0 = normal). Takes effect from the next frame. */
    fun setSpeed(newSpeed: Float) {
        speed = newSpeed.coerceIn(MIN_SPEED, MAX_SPEED)
    }

    /** Starts or resumes playback; shows a frame immediately. Safe to call repeatedly. */
    fun start() {
        handler.post {
            if (running) return@post
            running = true
            handler.removeCallbacks(tick)
            tick.run()
        }
    }

    /** Pauses playback (e.g. wallpaper not visible). The current frame stays in the texture. */
    fun pause() {
        handler.post {
            running = false
            handler.removeCallbacks(tick)
        }
    }

    /** Stops playback permanently. The decoder is dropped along with this object. */
    fun release() {
        pause()
    }

    private fun scaledDelay(frameDelayMs: Int): Long {
        val scaled = (frameDelayMs / speed).toLong()
        return scaled.coerceAtLeast(MIN_FRAME_INTERVAL_MS)
    }

    companion object {
        private const val MIN_SPEED = 0.1f
        private const val MAX_SPEED = 4.0f

        /** Floor on time between frames, so high speed on short delays can't spin the CPU. */
        private const val MIN_FRAME_INTERVAL_MS = 10L
    }
}
