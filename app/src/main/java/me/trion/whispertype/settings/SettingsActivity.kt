package me.trion.whispertype.settings

import android.net.Uri
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
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
    private lateinit var modelPicker: Spinner
    private lateinit var btnDownload: Button
    private lateinit var btnUse: Button
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
        modelPicker = findViewById(R.id.model_picker)
        btnDownload = findViewById(R.id.btn_download)
        btnUse = findViewById(R.id.btn_use)
        btnImport = findViewById(R.id.btn_import)
        btnDelete = findViewById(R.id.btn_delete)
        progress = findViewById(R.id.model_progress)

        modelPicker.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            ModelCatalog.entries.map { "${it.title} (${it.approxSizeMb} MB)" }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        modelPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                refreshUi()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_haptic).apply {
            isChecked = prefs.haptic
            setOnCheckedChangeListener { _, checked -> prefs.haptic = checked }
        }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_auto_space).apply {
            isChecked = prefs.autoSpace
            setOnCheckedChangeListener { _, checked -> prefs.autoSpace = checked }
        }

        btnDownload.setOnClickListener { startDownload() }
        btnUse.setOnClickListener { useSelected() }
        btnImport.setOnClickListener {
            importLauncher.launch(
                arrayOf("application/zip", "application/x-zip-compressed", "*/*")
            )
        }
        btnDelete.setOnClickListener { deleteSelected() }

        // One-time upgrade from 1.3.x: drop the legacy RTranslator install.
        downloader.purgeLegacyInstall()
        prefs.migrate()

        // Default selection: base.en
        val defaultIdx =
            ModelCatalog.entries.indexOfFirst { it.id == ModelCatalog.DEFAULT_ID }.coerceAtLeast(0)
        modelPicker.setSelection(defaultIdx)

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

    private fun selectedId(): String = ModelCatalog.entries[modelPicker.selectedItemPosition].id

    private fun refreshUi() {
        val selected = ModelCatalog.entries[modelPicker.selectedItemPosition]
        val active = prefs.activeModelId
        val importInstalled = downloader.isInstalled(ModelCatalog.IMPORT_ID)

        val state = when {
            active == selected.id && downloader.isInstalled(selected.id) ->
                getString(R.string.model_status_active)
            downloader.isInstalled(selected.id) ->
                getString(R.string.model_status_installed)
            else -> getString(R.string.model_status_not_installed)
        }
        val importState = when {
            active == ModelCatalog.IMPORT_ID && importInstalled ->
                getString(R.string.model_status_active)
            importInstalled -> getString(R.string.model_status_installed)
            else -> getString(R.string.model_status_not_installed)
        }
        modelStatus.text =
            "${selected.title}: $state\n${getString(R.string.model_status_import_slot)}: $importState"

        btnDownload.isEnabled = !busy && !downloader.isInstalled(selected.id)
        btnUse.isEnabled = !busy && downloader.isInstalled(selected.id) && active != selected.id
        btnImport.isEnabled = !busy
        btnDelete.isEnabled =
            !busy && (downloader.isInstalled(selected.id) || importInstalled)
    }

    private fun startDownload() {
        val id = selectedId()
        busy = true
        refreshUi()
        progress.visibility = ProgressBar.VISIBLE
        progress.isIndeterminate = true

        lifecycleScope.launch {
            downloader.download(id) { downloaded, total ->
                runOnUiThread {
                    progress.isIndeterminate = total <= 0
                    if (total > 0) {
                        progress.max = 100
                        progress.progress = ((downloaded * 100) / total).toInt()
                    }
                }
            }.let { result ->
                busy = false
                progress.visibility = ProgressBar.GONE
                when (result) {
                    is ModelDownloader.Result.Success -> {
                        prefs.activeModelId = id
                        LocalAsrEngine.releaseAll()
                        Toast.makeText(
                            this@SettingsActivity,
                            R.string.toast_model_ready,
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

    private fun useSelected() {
        val id = selectedId()
        prefs.activeModelId = id
        LocalAsrEngine.releaseAll()
        refreshUi()
    }

    private fun deleteSelected() {
        val selected = selectedId()
        val target = when {
            downloader.isInstalled(selected) -> selected
            downloader.isInstalled(ModelCatalog.IMPORT_ID) -> ModelCatalog.IMPORT_ID
            else -> return
        }
        if (prefs.activeModelId == target) prefs.clearActiveModelId()
        LocalAsrEngine.releaseAll()
        downloader.delete(target)
        refreshUi()
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
                    progress.isIndeterminate = total <= 0
                    if (total > 0) {
                        progress.max = 100
                        progress.progress = ((copied * 100) / total).toInt()
                    }
                }
            }.let { result ->
                busy = false
                progress.visibility = ProgressBar.GONE
                when (result) {
                    is ModelDownloader.Result.Success -> {
                        prefs.activeModelId = result.modelId
                        LocalAsrEngine.releaseAll()
                        Toast.makeText(
                            this@SettingsActivity,
                            R.string.toast_model_ready,
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
}
