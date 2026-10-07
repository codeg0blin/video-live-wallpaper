package io.github.codeg0blin.videowallpaper

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.widget.ImageView

/**
 * Plays a GIF inside an [ImageView] for the in-app preview.
 *
 * Reading, parsing and frame decoding all happen on a private background
 * thread (reusing [GifPlayer] for timing), and finished frames are handed to
 * the ImageView on the main thread. Two bitmaps alternate so a frame being
 * drawn is never the one being written.
 *
 * Call everything except the internals from the main thread. Crop mode is
 * applied by the caller through the ImageView's scaleType.
 */
class GifPreview(private val imageView: ImageView) {

    private val thread = HandlerThread("gif-preview").apply { start() }
    private val workHandler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Main thread only.
    private var player: GifPlayer? = null
    private var activeSession: Session? = null
    private var generation = 0
    private var speed = 1.0f
    private var released = false

    /**
     * Loads and parses [uri] in the background. [onResult] runs on the main
     * thread with true once the GIF is ready (not yet started — call
     * [start]), or false if it could not be read, decoded or fit in memory.
     * A failed load leaves whatever was playing before untouched. If another
     * [load] or [stop] happens first, this call's [onResult] never fires.
     */
    fun load(resolver: ContentResolver, uri: Uri, onResult: (Boolean) -> Unit) {
        if (released) return
        generation++
        val myGeneration = generation
        workHandler.post {
            val session: Session? = try {
                Session(GifDecoder.parse(GifFiles.readBytes(resolver, uri)))
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            mainHandler.post {
                if (released || myGeneration != generation) return@post
                if (session == null) {
                    onResult(false)
                    return@post
                }
                player?.release()
                activeSession = session
                player = GifPlayer(workHandler, session.decoder) { session.onFrame(it) }
                    .also { it.setSpeed(speed) }
                onResult(true)
            }
        }
    }

    fun start() {
        player?.start()
    }

    fun pause() {
        player?.pause()
    }

    fun setSpeed(newSpeed: Float) {
        speed = newSpeed
        player?.setSpeed(newSpeed)
    }

    /** Stops and discards the current GIF and cancels any load in flight. */
    fun stop() {
        generation++
        player?.release()
        player = null
        activeSession = null
    }

    /** Stops everything and shuts the background thread down. Not reusable afterwards. */
    fun release() {
        stop()
        released = true
        thread.quitSafely()
    }

    private inner class Session(val decoder: GifDecoder) {
        private val bitmaps = Array(2) {
            Bitmap.createBitmap(decoder.width, decoder.height, Bitmap.Config.ARGB_8888)
        }
        private var next = 0

        /** Runs on the background thread for every new frame. */
        fun onFrame(d: GifDecoder): Boolean {
            val bitmap = bitmaps[next]
            next = next xor 1
            bitmap.setPixels(d.pixels, 0, d.width, 0, 0, d.width, d.height)
            mainHandler.post {
                // Ignore frames from a session that has since been replaced.
                if (activeSession === this) imageView.setImageBitmap(bitmap)
            }
            return true
        }
    }
}
