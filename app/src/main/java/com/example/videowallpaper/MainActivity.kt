package com.example.videowallpaper

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
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.example.videowallpaper.databinding.ActivityMainBinding

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

    // Current preview MediaPlayer, captured so we can re-apply speed when the
    // slider moves without needing to re-prepare the whole VideoView.
    private var previewPlayer: MediaPlayer? = null
    private var currentSpeed: Float = VideoWallpaperService.DEFAULT_SPEED
    private var currentScalingMode: Int = VideoWallpaperService.DEFAULT_SCALING_MODE

    // Debounce handler — delays writing speed to prefs until 500ms after the
    // user lifts their finger, so rapid slider adjustments don't each trigger
    // a wallpaper service re-prepare.
    private val speedDebounceHandler = Handler(Looper.getMainLooper())
    private val speedDebounceRunnable = Runnable {
        prefs.edit().putFloat(VideoWallpaperService.PREF_PLAYBACK_SPEED, currentSpeed).apply()
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

        currentSpeed = prefs.getFloat(VideoWallpaperService.PREF_PLAYBACK_SPEED, VideoWallpaperService.DEFAULT_SPEED)
        currentScalingMode = prefs.getInt(VideoWallpaperService.PREF_SCALING_MODE, VideoWallpaperService.DEFAULT_SCALING_MODE)

        setupSpeedControl()
        setupCropControl()
        restoreSelection()

        binding.pickButton.setOnClickListener {
            pickVideoLauncher.launch(arrayOf("video/*"))
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
            prefs.edit().putInt(VideoWallpaperService.PREF_SCALING_MODE, currentScalingMode).apply()
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
        val saved = prefs.getString(VideoWallpaperService.PREF_VIDEO_URI, null)
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

        selectedVideoUri = uri
        prefs.edit().putString(VideoWallpaperService.PREF_VIDEO_URI, uri.toString()).apply()
        showPreview(uri)
    }

    private fun showPreview(uri: Uri) {
        binding.noVideoText.visibility = View.GONE
        binding.previewVideo.visibility = View.VISIBLE
        binding.previewVideo.setVideoURI(uri)
        binding.previewVideo.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            previewPlayer = mp
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
        selectedVideoUri?.let {
            if (!binding.previewVideo.isPlaying) {
                binding.previewVideo.start()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (binding.previewVideo.isPlaying) {
            binding.previewVideo.pause()
        }
        previewPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        speedDebounceHandler.removeCallbacks(speedDebounceRunnable)
    }
}
