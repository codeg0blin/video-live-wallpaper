package com.example.videowallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.videowallpaper.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var selectedVideoUri: Uri? = null

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onVideoPicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        restoreSelection()

        binding.pickButton.setOnClickListener {
            pickVideoLauncher.launch(arrayOf("video/*"))
        }

        binding.setLockButton.setOnClickListener {
            launchLiveWallpaperPicker(WallpaperManager.FLAG_LOCK)
        }

        binding.setHomeButton.setOnClickListener {
            launchLiveWallpaperPicker(WallpaperManager.FLAG_SYSTEM)
        }
    }

    private fun restoreSelection() {
        val saved = prefs.getString(VideoWallpaperService.PREF_VIDEO_URI, null)
        if (saved != null) {
            val uri = Uri.parse(saved)
            // Confirm we still hold permission; if not, treat as no selection.
            val stillGranted = contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
            if (stillGranted) {
                selectedVideoUri = uri
                showPreview(uri)
            }
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
        binding.noVideoText.visibility = android.view.View.GONE
        binding.previewVideo.visibility = android.view.View.VISIBLE
        binding.previewVideo.setVideoURI(uri)
        binding.previewVideo.setOnPreparedListener {
            it.isLooping = true
            it.setVolume(0f, 0f)
            binding.previewVideo.start()
        }

        binding.setLockButton.isEnabled = true
        binding.setHomeButton.isEnabled = true
    }

    private fun launchLiveWallpaperPicker(which: Int) {
        if (selectedVideoUri == null) {
            Toast.makeText(this, getString(R.string.select_video_first), Toast.LENGTH_SHORT).show()
            return
        }

        // Android requires going through this system intent to activate a live
        // wallpaper — there is no API for an app to silently set itself as the
        // live wallpaper without user confirmation on the system preview screen.
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        intent.putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, VideoWallpaperService::class.java)
        )
        // Hint at which screen (Android 7.0+ launchers may honor this with a
        // Home/Lock/Both chooser on the system preview screen).
        intent.putExtra("which", which)

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
    }
}
