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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.trion.whispertype.R
import me.trion.whispertype.util.Prefs
import me.trion.whispertype.voice.LocalAsrEngine
import me.trion.whispertype.voice.ModelCatalog
import me.trion.whispertype.voice.LanguagePolicy
import me.trion.whispertype.voice.ModelDownloader
import me.trion.whispertype.voice.ModelSpec

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var downloader: ModelDownloader
    private lateinit var modelStatus: TextView
    private lateinit var modelPicker: Spinner
    private lateinit var languagePicker: Spinner
    private lateinit var btnDownload: Button
    private lateinit var btnUse: Button
    private lateinit var btnImport: Button
    private lateinit var btnDelete: Button
    private lateinit var modelDescription: TextView
    private var updatingLanguagePicker = false
    private var modelChoices: List<ModelSpec> = emptyList()
    private var languageChoices: List<Pair<String, String>> = emptyList()
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
        modelDescription = findViewById(R.id.model_description)
        modelPicker = findViewById(R.id.model_picker)
        languagePicker = findViewById(R.id.language_picker)
        btnDownload = findViewById(R.id.btn_download)
        btnUse = findViewById(R.id.btn_use)
        btnImport = findViewById(R.id.btn_import)
        btnDelete = findViewById(R.id.btn_delete)
        progress = findViewById(R.id.model_progress)

        languagePicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                if (updatingLanguagePicker || position != languagePicker.selectedItemPosition) return
                val language = languageChoices.getOrNull(position)?.first ?: return
                val policy = selectedModel().languagePolicy as? LanguagePolicy.Selectable ?: return
                if (prefs.languageFor(selectedModel().id, policy) == language) return
                prefs.setLanguageFor(selectedModel().id, language)
                refreshUi()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
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
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_incognito).apply {
            isChecked = prefs.incognito
            setOnCheckedChangeListener { _, checked -> prefs.incognito = checked }
        }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_double_space).apply {
            isChecked = prefs.doubleSpacePeriod
            setOnCheckedChangeListener { _, checked -> prefs.doubleSpacePeriod = checked }
        }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_sentence_caps).apply {
            isChecked = prefs.sentenceCaps
            setOnCheckedChangeListener { _, checked -> prefs.sentenceCaps = checked }
        }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_enter_new_line).apply {
            isChecked = prefs.enterNewLine
            setOnCheckedChangeListener { _, checked -> prefs.enterNewLine = checked }
        }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_dictation_match_case).apply {
            isChecked = prefs.dictationMatchCase
            setOnCheckedChangeListener { _, checked -> prefs.dictationMatchCase = checked }
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

        refreshModelPicker(prefs.activeModelId)

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

    private fun selectedModel() =
        modelChoices.getOrNull(modelPicker.selectedItemPosition)
            ?: ModelCatalog.byId(ModelCatalog.DEFAULT_ID)!!

    private fun selectedId(): String = selectedModel().id

    private fun refreshModelPicker(preferredId: String? = null) {
        val selectedId = preferredId ?: modelChoices.getOrNull(modelPicker.selectedItemPosition)?.id
        val choices = ModelCatalog.entries + listOfNotNull(downloader.modelSpec(ModelCatalog.IMPORT_ID))
        if (choices != modelChoices) {
            modelChoices = choices
            modelPicker.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                choices.map { model ->
                    val title = if (model.id == ModelCatalog.IMPORT_ID) {
                        getString(R.string.imported_model_title, model.title)
                    } else model.title
                    if (model.approxSizeMb > 0) "$title (${model.approxSizeMb} MB)" else title
                },
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        val index = choices.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
            ?: choices.indexOfFirst { it.id == ModelCatalog.DEFAULT_ID }
        if (modelPicker.selectedItemPosition != index) modelPicker.setSelection(index, false)
    }

    private fun refreshLanguagePicker() {
        val policy = selectedModel().languagePolicy
        val selectable = policy as? LanguagePolicy.Selectable
        languageChoices = when (policy) {
            is LanguagePolicy.Selectable ->
                policy.codes
                    .distinct()
                    .map { code ->
                        code to when {
                            code.isEmpty() -> getString(R.string.language_auto_detect)
                            else -> ModelCatalog.languageOptions.firstOrNull { it.code == code }?.title ?: code
                        }
                    }
            is LanguagePolicy.Fixed -> listOf(policy.code to policy.label)
            is LanguagePolicy.Automatic -> listOf("" to policy.label)
        }
        if (languageChoices.isEmpty()) {
            languageChoices = listOf("" to getString(R.string.language_auto_detect))
        }
        val language = selectable?.let { prefs.languageFor(selectedModel().id, it) }
            ?: languageChoices.first().first
        val index = languageChoices.indexOfFirst { it.first == language }.coerceAtLeast(0)
        updatingLanguagePicker = true
        try {
            languagePicker.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                languageChoices.map { it.second },
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            languagePicker.isEnabled = selectable != null && !busy
            languagePicker.visibility = if (selectable != null) android.view.View.VISIBLE else android.view.View.GONE
            findViewById<android.view.View>(R.id.language_label).visibility = languagePicker.visibility
            findViewById<android.view.View>(R.id.language_help).visibility = languagePicker.visibility
            languagePicker.setSelection(index, false)
        } finally {
            updatingLanguagePicker = false
        }
    }

    private fun refreshUi() {
        refreshModelPicker()
        val selected = selectedModel()
        val active = prefs.activeModelId
        refreshLanguagePicker()
        val importInstalled = downloader.isInstalled(ModelCatalog.IMPORT_ID)

        val state = when {
            active == selected.id && downloader.isInstalled(selected.id) ->
                getString(R.string.model_status_active)
            downloader.isInstalled(selected.id) ->
                getString(R.string.model_status_installed)
            else -> getString(R.string.model_status_not_installed)
        }
        val imported = downloader.modelSpec(ModelCatalog.IMPORT_ID)
        val importState = when {
            active == ModelCatalog.IMPORT_ID && importInstalled ->
                getString(R.string.model_status_active)
            importInstalled -> getString(R.string.model_status_import_installed)
            else -> getString(R.string.model_status_not_installed)
        }
        modelDescription.text = buildString {
            if (selected.description.isNotBlank()) append(selected.description)
            if (selected.id != ModelCatalog.IMPORT_ID && active == ModelCatalog.IMPORT_ID && imported != null) {
                if (isNotEmpty()) append('\n')
                append(getString(R.string.active_import_description, imported.title))
                if (imported.description.isNotBlank()) append(": ").append(imported.description)
            }
        }
        modelStatus.text = if (selected.id == ModelCatalog.IMPORT_ID) {
            "${getString(R.string.imported_model_title, selected.title)}: $state"
        } else {
            "${selected.title}: $state\n${getString(R.string.model_status_import_slot)}: ${imported?.title ?: ""} $importState"
        }

        btnDownload.isEnabled = !busy && !downloader.isInstalled(selected.id)
        btnUse.isEnabled = !busy && downloader.isInstalled(selected.id) && active != selected.id
        btnImport.isEnabled = !busy
        btnDelete.isEnabled = !busy && downloader.isInstalled(selected.id)
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
                progress.visibility = ProgressBar.GONE
                when (result) {
                    is ModelDownloader.Result.Success -> {
                        prefs.activeModelId = id
                        withContext(Dispatchers.IO) {
                            LocalAsrEngine.releaseAll()
                        }
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
                busy = false
                refreshUi()
            }
        }
    }

    private fun useSelected() {
        val id = selectedId()
        prefs.activeModelId = id
        busy = true
        refreshUi()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                LocalAsrEngine.releaseAll()
            }
            busy = false
            refreshUi()
        }
    }

    private fun deleteSelected() {
        val selected = selectedId()
        if (!downloader.isInstalled(selected)) return
        val target = selected
        if (prefs.activeModelId == target) prefs.clearActiveModelId()
        busy = true
        refreshUi()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                LocalAsrEngine.releaseAll()
                downloader.delete(target)
            }
            busy = false
            refreshUi()
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
                    progress.isIndeterminate = total <= 0
                    if (total > 0) {
                        progress.max = 100
                        progress.progress = ((copied * 100) / total).toInt()
                    }
                }
            }.let { result ->
                progress.visibility = ProgressBar.GONE
                when (result) {
                    is ModelDownloader.Result.Success -> {
                        prefs.activeModelId = result.modelId
                        withContext(Dispatchers.IO) {
                            LocalAsrEngine.releaseAll()
                        }
                        refreshModelPicker(result.modelId)
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
                busy = false
                refreshUi()
            }
        }
    }
}
