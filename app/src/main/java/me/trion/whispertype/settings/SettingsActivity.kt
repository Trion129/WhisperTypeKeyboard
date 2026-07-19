package me.trion.whispertype.settings

import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import me.trion.whispertype.R
import me.trion.whispertype.util.Prefs
import me.trion.whispertype.voice.LocalAsrEngine
import me.trion.whispertype.voice.ModelCatalog
import me.trion.whispertype.voice.ModelDownloader
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var downloader: ModelDownloader
    private lateinit var modelStatus: TextView
    private lateinit var btnDownload: Button
    private lateinit var btnDelete: Button
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        prefs = Prefs(this)
        downloader = ModelDownloader(this)

        modelStatus = findViewById(R.id.model_status)
        btnDownload = findViewById(R.id.btn_download)
        btnDelete = findViewById(R.id.btn_delete)
        progress = findViewById(R.id.model_progress)

        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_haptic).apply {
            isChecked = prefs.haptic
            setOnCheckedChangeListener { _, checked -> prefs.haptic = checked }
        }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_auto_space).apply {
            isChecked = prefs.autoSpace
            setOnCheckedChangeListener { _, checked -> prefs.autoSpace = checked }
        }

        btnDownload.setOnClickListener { startDownload() }
        btnDelete.setOnClickListener {
            LocalAsrEngine.releaseAll()
            downloader.delete()
            refreshUi()
        }

        refreshUi()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val installed = downloader.isInstalled()
        modelStatus.text = when {
            installed -> "Installed — ${String.format("%.0f", ModelCatalog.APPROX_SIZE_MB.toDouble())} MB"
            else -> "Not downloaded"
        }
        btnDownload.isEnabled = !installed
        btnDownload.text = if (installed) "Downloaded" else "Download Whisper Small"
        btnDelete.isEnabled = installed
    }

    private fun startDownload() {
        btnDownload.isEnabled = false
        btnDownload.text = "Downloading..."
        progress.visibility = ProgressBar.VISIBLE
        progress.isIndeterminate = true

        lifecycleScope.launch {
            downloader.download { downloaded, total ->
                runOnUiThread {
                    progress.isIndeterminate = false
                    progress.max = 100
                    if (total > 0) {
                        progress.progress = ((downloaded * 100) / total).toInt()
                        btnDownload.text = "Downloading ${progress.progress}%"
                    } else {
                        btnDownload.text = "Downloading ${downloaded / (1024 * 1024)} MB"
                    }
                }
            }.let { result ->
                progress.visibility = ProgressBar.GONE
                when (result) {
                    is ModelDownloader.Result.Success -> {
                        Toast.makeText(this@SettingsActivity, "Model ready!", Toast.LENGTH_SHORT).show()
                    }
                    is ModelDownloader.Result.AlreadyInstalled -> {
                        Toast.makeText(this@SettingsActivity, "Already installed", Toast.LENGTH_SHORT).show()
                    }
                    is ModelDownloader.Result.Error -> {
                        Toast.makeText(this@SettingsActivity, result.message, Toast.LENGTH_LONG).show()
                    }
                }
                refreshUi()
            }
        }
    }
}
