package me.trion.whispertype.settings

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import me.trion.whispertype.R
import me.trion.whispertype.util.Prefs
import me.trion.whispertype.voice.LocalAsrEngine
import me.trion.whispertype.voice.ModelCatalog
import me.trion.whispertype.voice.ModelDownloader

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var downloader: ModelDownloader
    private lateinit var modelStatus: TextView
    private lateinit var btnDownload: Button
    private lateinit var btnImport: Button
    private lateinit var btnDelete: Button
    private lateinit var progress: ProgressBar
    private var busy = false

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startImport(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        prefs = Prefs(this)
        downloader = ModelDownloader(this)

        modelStatus = findViewById(R.id.model_status)
        btnDownload = findViewById(R.id.btn_download)
        btnImport = findViewById(R.id.btn_import)
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
        btnImport.setOnClickListener {
            importLauncher.launch(
                arrayOf("application/zip", "application/x-zip-compressed", "*/*")
            )
        }
        btnDelete.setOnClickListener {
            LocalAsrEngine.releaseAll()
            downloader.delete()
            prefs.clearModelSource()
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
        val imported = prefs.modelSource == Prefs.MODEL_SOURCE_IMPORT
        modelStatus.text = when {
            installed && imported -> getString(R.string.model_status_imported)
            installed -> getString(
                R.string.model_status_downloaded,
                String.format("%.0f", ModelCatalog.APPROX_SIZE_MB.toDouble()) + " MB"
            )
            else -> getString(R.string.model_status_not_installed)
        }
        btnDownload.isEnabled = !installed && !busy
        btnDownload.text = if (installed) "Downloaded" else "Download Whisper Small"
        btnImport.isEnabled = !busy
        btnDelete.isEnabled = installed && !busy
    }

    private fun startDownload() {
        busy = true
        refreshUi()
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
                busy = false
                progress.visibility = ProgressBar.GONE
                when (result) {
                    is ModelDownloader.Result.Success -> {
                        prefs.modelSource = Prefs.MODEL_SOURCE_DOWNLOAD
                        Toast.makeText(
                            this@SettingsActivity,
                            R.string.toast_model_ready,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is ModelDownloader.Result.AlreadyInstalled -> {
                        Toast.makeText(
                            this@SettingsActivity,
                            "Already installed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is ModelDownloader.Result.Error -> {
                        Toast.makeText(
                            this@SettingsActivity,
                            result.message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                refreshUi()
            }
        }
    }

    private fun startImport(uri: Uri) {
        busy = true
        refreshUi()
        modelStatus.text = getString(R.string.model_importing)
        progress.visibility = ProgressBar.VISIBLE
        progress.isIndeterminate = true

        lifecycleScope.launch {
            downloader.importFromUri(uri) { copied, total ->
                runOnUiThread {
                    progress.isIndeterminate = false
                    progress.max = 100
                    if (total > 0) {
                        progress.progress = ((copied * 100) / total).toInt()
                    }
                }
            }.let { result ->
                busy = false
                progress.visibility = ProgressBar.GONE
                when (result) {
                    is ModelDownloader.Result.Success -> {
                        prefs.modelSource = Prefs.MODEL_SOURCE_IMPORT
                        LocalAsrEngine.releaseAll()
                        Toast.makeText(
                            this@SettingsActivity,
                            R.string.toast_model_ready,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is ModelDownloader.Result.AlreadyInstalled -> {
                        // importFromUri never returns AlreadyInstalled; keep exhaustive
                    }
                    is ModelDownloader.Result.Error -> {
                        Toast.makeText(
                            this@SettingsActivity,
                            result.message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                refreshUi()
            }
        }
    }
}
