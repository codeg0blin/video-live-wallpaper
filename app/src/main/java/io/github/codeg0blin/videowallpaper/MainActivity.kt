package io.github.codeg0blin.videowallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import io.github.codeg0blin.videowallpaper.R
import io.github.codeg0blin.videowallpaper.VideoWallpaperService
import io.github.codeg0blin.videowallpaper.databinding.ActivityMainBinding
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lets the user pick a video, preview it, and set it as a live wallpaper
 * via the system's live-wallpaper picker. Exposes playback speed and
 * crop-mode controls, both applied live to the preview and persisted for
 * [VideoWallpaperService] to read. Live wallpapers apply to both home and
 * lock screen simultaneously — Android does not support independent
 * live wallpapers per surface.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var selectedVideoUri: Uri? = null

    // In-app preview for GIFs (video keeps using the VideoView).
    private lateinit var gifPreview: GifPreview
    private var showingGif = false

    // Current preview MediaPlayer, captured so we can re-apply speed when the
    // slider moves without needing to re-prepare the whole VideoView.
    private var previewPlayer: MediaPlayer? = null
    private var currentSpeed: Float = VideoWallpaperService.Companion.DEFAULT_SPEED
    private var currentScalingMode: Int = VideoWallpaperService.Companion.DEFAULT_SCALING_MODE

    // Debounce handler — delays writing speed to prefs until 500ms after the
    // user lifts their finger, so rapid slider adjustments don't each trigger
    // a wallpaper service re-prepare.
    private val speedDebounceHandler = Handler(Looper.getMainLooper())
    private val speedDebounceRunnable = Runnable {
        prefs.edit().putFloat(VideoWallpaperService.Companion.PREF_PLAYBACK_SPEED, currentSpeed).apply()
    }

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onVideoPicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        gifPreview = GifPreview(binding.previewGif)
        binding.previewFrame.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                applyCropToVideoPreview()
            }
        }

        currentSpeed = prefs.getFloat(VideoWallpaperService.Companion.PREF_PLAYBACK_SPEED, VideoWallpaperService.Companion.DEFAULT_SPEED)
        currentScalingMode = prefs.getInt(VideoWallpaperService.Companion.PREF_SCALING_MODE, VideoWallpaperService.Companion.DEFAULT_SCALING_MODE)

        setupSpeedControl()
        setupCropControl()
        restoreSelection()

        binding.pickButton.setOnClickListener {
            pickVideoLauncher.launch(arrayOf("video/*", "image/gif"))
        }

        binding.setWallpaperButton.setOnClickListener {
            launchLiveWallpaperPicker()
        }
    }

    // --- Speed control -------------------------------------------------

    /**
     * SeekBar progress (0..35) maps to speed 0.25x..2.0x in 0.05x steps,
     * with progress 15 landing exactly on 1.0x (normal speed) so the default
     * thumb position reads naturally as "no change."
     */
    private fun progressToSpeed(progress: Int): Float = 0.25f + (progress * 0.05f)
    private fun speedToProgress(speed: Float): Int = (((speed - 0.25f) / 0.05f) + 0.5f).toInt()

    private fun setupSpeedControl() {
        binding.speedSeekBar.progress = speedToProgress(currentSpeed).coerceIn(0, 35)
        updateSpeedLabel(currentSpeed)

        binding.speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = progressToSpeed(progress)
                updateSpeedLabel(speed)
                if (fromUser) {
                    currentSpeed = speed
                    applySpeedToPreview(speed)
                    gifPreview.setSpeed(speed)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Cancel any pending debounced write when the user starts
                // dragging again before the delay has elapsed.
                speedDebounceHandler.removeCallbacks(speedDebounceRunnable)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Write to prefs 500ms after finger lifts — giving the user
                // time to make a second adjustment before the wallpaper
                // service does an expensive re-prepare.
                speedDebounceHandler.postDelayed(speedDebounceRunnable, 500L)
            }
        })
    }

    private fun updateSpeedLabel(speed: Float) {
        binding.speedValueText.text = String.format("%.2fx", speed)
    }

    private fun applySpeedToPreview(speed: Float) {
        val player = previewPlayer ?: return
        try {
            if (!player.isPlaying) player.start()
            player.playbackParams = PlaybackParams().setSpeed(speed)
        } catch (e: IllegalStateException) {
            // Player not in a state that accepts speed changes right now (e.g.
            // mid-teardown) — safe to ignore, the next prepare will apply it.
        }
    }

    // --- Crop / scaling mode control ------------------------------------

    private fun setupCropControl() {
        val initialCheckedId = if (currentScalingMode == MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) {
            binding.cropFitButton.id
        } else {
            binding.cropFillButton.id
        }
        binding.cropToggleGroup.check(initialCheckedId)

        binding.cropToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentScalingMode = if (checkedId == binding.cropFitButton.id) {
                MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT
            } else {
                MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }
            prefs.edit().putInt(VideoWallpaperService.Companion.PREF_SCALING_MODE, currentScalingMode).apply()
            applyCropToGifPreview()
            applyCropToVideoPreview()
            // Crop mode for VideoView itself is controlled by view scaleType,
            // which VideoView doesn't expose directly the way MediaPlayer does
            // for a raw Surface — the preview already fills its card via
            // layout_gravity=center, so this setting's visual effect is most
            // apparent once actually set as a wallpaper. We still persist it
            // here so the wallpaper service picks it up immediately.
        }
    }

    // --- Video selection -------------------------------------------------

    private fun restoreSelection() {
        val saved = prefs.getString(VideoWallpaperService.Companion.PREF_VIDEO_URI, null)
        if (saved.isNullOrBlank()) return

        val uri = try {
            Uri.parse(saved)
        } catch (e: Exception) {
            // Stored value was somehow malformed — treat as no selection
            // rather than crashing on launch.
            null
        } ?: return

        // Confirm we still hold permission; if not, treat as no selection.
        val stillGranted = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (stillGranted) {
            selectedVideoUri = uri
            showPreview(uri)
        }
    }

    private fun onVideoPicked(uri: Uri) {
        // Persist permission so the wallpaper service can still read this file
        // after the picker activity closes, and across device reboots.
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Toast.makeText(this, "Couldn't get permanent access to that file", Toast.LENGTH_LONG).show()
            return
        }

        if (GifFiles.looksLikeGif(contentResolver, uri)) {
            // Validate before committing, so a damaged or oversized GIF never
            // replaces a working selection.
            loadGifPreview(uri, persistSelection = true)
            return
        }

        selectedVideoUri = uri
        prefs.edit().putString(VideoWallpaperService.Companion.PREF_VIDEO_URI, uri.toString()).apply()
        showPreview(uri)
    }

    // --- Video preview crop ------------------------------------------------

    /**
     * Makes the video preview show the chosen crop mode the way the wallpaper
     * does. VideoView always fits the whole picture inside its own bounds, so
     * rather than replacing it, we size the view itself: for Fit it is the
     * largest size that fits the preview card, for Fill the smallest size
     * that covers it, and the card (the parent frame) clips whatever sticks
     * out. The size keeps the video's aspect ratio, so VideoView adds no
     * letterboxing of its own. Does nothing until the player knows the
     * video's size and the frame has been laid out.
     */
    private fun applyCropToVideoPreview() {
        val player = previewPlayer ?: return
        val frame = binding.previewFrame
        val videoW = try { player.videoWidth } catch (e: IllegalStateException) { 0 }
        val videoH = try { player.videoHeight } catch (e: IllegalStateException) { 0 }
        val frameW = frame.width
        val frameH = frame.height
        if (videoW <= 0 || videoH <= 0 || frameW <= 0 || frameH <= 0) return

        val scaleX = frameW.toFloat() / videoW
        val scaleY = frameH.toFloat() / videoH
        val scale = if (currentScalingMode == MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) {
            min(scaleX, scaleY)
        } else {
            max(scaleX, scaleY)
        }
        val width = (videoW * scale).roundToInt()
        val height = (videoH * scale).roundToInt()

        val params = binding.previewVideo.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.width != width || params.height != height || params.gravity != Gravity.CENTER) {
            params.width = width
            params.height = height
            params.gravity = Gravity.CENTER
            binding.previewVideo.layoutParams = params
        }
    }

    // --- GIF preview ------------------------------------------------------

    private fun applyCropToGifPreview() {
        binding.previewGif.scaleType =
            if (currentScalingMode == MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) {
                ImageView.ScaleType.FIT_CENTER
            } else {
                ImageView.ScaleType.CENTER_CROP
            }
    }

    /**
     * Loads [uri] as a GIF in the background. On success it becomes the
     * selection (saved to prefs only when [persistSelection] is true, i.e.
     * the user just picked it) and the preview switches to the GIF. On
     * failure the previous selection and preview are left alone.
     */
    private fun loadGifPreview(uri: Uri, persistSelection: Boolean) {
        gifPreview.load(contentResolver, uri) { ok ->
            if (!ok) {
                if (persistSelection) {
                    Toast.makeText(this, getString(R.string.error_gif_unusable), Toast.LENGTH_LONG).show()
                    // Give back the permission we took for a file we won't use,
                    // unless it is the file already selected.
                    if (uri != selectedVideoUri) {
                        try {
                            contentResolver.releasePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (e: SecurityException) {
                            // Nothing to release.
                        }
                    }
                } else {
                    // Restoring a saved selection that no longer loads: treat as no selection.
                    selectedVideoUri = null
                }
                return@load
            }

            selectedVideoUri = uri
            if (persistSelection) {
                prefs.edit().putString(VideoWallpaperService.Companion.PREF_VIDEO_URI, uri.toString()).apply()
            }
            showingGif = true

            binding.previewVideo.stopPlayback()
            binding.previewVideo.visibility = View.GONE
            previewPlayer = null
            binding.noVideoText.visibility = View.GONE
            binding.previewGif.visibility = View.VISIBLE
            applyCropToGifPreview()
            gifPreview.setSpeed(currentSpeed)
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                gifPreview.start()
            }
            binding.setWallpaperButton.isEnabled = true
        }
    }

    private fun showPreview(uri: Uri) {
        if (GifFiles.looksLikeGif(contentResolver, uri)) {
            loadGifPreview(uri, persistSelection = false)
        } else {
            showVideoPreview(uri)
        }
    }

    private fun showVideoPreview(uri: Uri) {
        showingGif = false
        gifPreview.stop()
        binding.previewGif.visibility = View.GONE
        binding.noVideoText.visibility = View.GONE
        binding.previewVideo.visibility = View.VISIBLE
        binding.previewVideo.setVideoURI(uri)
        binding.previewVideo.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            previewPlayer = mp
            applyCropToVideoPreview()
            binding.previewVideo.start()
            applySpeedToPreview(currentSpeed)
        }

        binding.setWallpaperButton.isEnabled = true
    }

    private fun launchLiveWallpaperPicker() {
        if (selectedVideoUri == null) {
            Toast.makeText(this, getString(R.string.select_video_first), Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        intent.putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, VideoWallpaperService::class.java)
        )

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, getString(R.string.error_live_wallpaper_unavailable), Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (showingGif) {
            gifPreview.start()
            return
        }
        selectedVideoUri?.let {
            if (!binding.previewVideo.isPlaying) {
                binding.previewVideo.start()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        gifPreview.pause()
        if (binding.previewVideo.isPlaying) {
            binding.previewVideo.pause()
        }
        // previewPlayer is deliberately kept: VideoView reuses the same
        // MediaPlayer when the activity resumes, and clearing it here left
        // the speed slider with nothing to control until a new video was
        // picked. If VideoView does release its player (surface destroyed),
        // its prepared listener runs again and replaces this reference, and
        // applySpeedToPreview already tolerates a stale player.
    }

    override fun onDestroy() {
        super.onDestroy()
        gifPreview.release()
        speedDebounceHandler.removeCallbacks(speedDebounceRunnable)
    }
}