package com.whispertype.keyboard.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.whispertype.keyboard.R
import com.whispertype.keyboard.util.Prefs
import com.whispertype.keyboard.voice.AsrModel
import com.whispertype.keyboard.voice.LocalAsrEngine
import com.whispertype.keyboard.voice.ModelCatalog
import com.whispertype.keyboard.voice.ModelDownloader
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var downloader: ModelDownloader
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var downloadingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        prefs = Prefs(this)
        downloader = ModelDownloader(this)
        list = findViewById(R.id.model_list)
        status = findViewById(R.id.settings_status)

        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_haptic).apply {
            isChecked = prefs.haptic
            setOnCheckedChangeListener { _, checked -> prefs.haptic = checked }
        }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_auto_space).apply {
            isChecked = prefs.autoSpace
            setOnCheckedChangeListener { _, checked -> prefs.autoSpace = checked }
        }

        renderModels()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun renderModels() {
        list.removeAllViews()
        status.text = "Selected: ${ModelCatalog.byId(prefs.modelId).title}"
        val inflater = LayoutInflater.from(this)
        ModelCatalog.models.forEach { model ->
            val row = inflater.inflate(R.layout.item_model, list, false)
            bindModelRow(row, model)
            list.addView(row)
        }
    }

    private fun bindModelRow(row: View, model: AsrModel) {
        val title = row.findViewById<TextView>(R.id.model_title)
        val desc = row.findViewById<TextView>(R.id.model_desc)
        val state = row.findViewById<TextView>(R.id.model_state)
        val progress = row.findViewById<ProgressBar>(R.id.model_progress)
        val btnDownload = row.findViewById<Button>(R.id.btn_download)
        val btnSelect = row.findViewById<Button>(R.id.btn_select)
        val btnDelete = row.findViewById<Button>(R.id.btn_delete)

        title.text = if (model.recommended) "${model.title}  ★ Recommended" else model.title
        desc.text = "${model.description} (~${model.approxSizeMb} MB)"
        val installed = downloader.isInstalled(model)
        val selected = prefs.modelId == model.id
        state.text = when {
            selected && installed -> "Installed · Active"
            installed -> "Installed"
            downloadingId == model.id -> "Downloading…"
            else -> "Not downloaded"
        }
        progress.visibility = if (downloadingId == model.id) View.VISIBLE else View.GONE
        btnDownload.isEnabled = downloadingId == null && !installed
        btnDownload.text = if (installed) "Downloaded" else "Download"
        btnSelect.isEnabled = installed
        btnSelect.text = if (selected) "Selected" else "Use this model"
        btnDelete.isEnabled = installed && downloadingId == null

        btnDownload.setOnClickListener { startDownload(model) }
        btnSelect.setOnClickListener {
            prefs.modelId = model.id
            LocalAsrEngine(this).release()
            Toast.makeText(this, "Using ${model.title}", Toast.LENGTH_SHORT).show()
            renderModels()
        }
        btnDelete.setOnClickListener {
            downloader.delete(model)
            if (prefs.modelId == model.id) {
                val other = ModelCatalog.models.firstOrNull { downloader.isInstalled(it) }
                if (other != null) prefs.modelId = other.id
            }
            renderModels()
        }
    }

    private fun startDownload(model: AsrModel) {
        downloadingId = model.id
        renderModels()
        lifecycleScope.launch {
            val result = downloader.download(model) { downloaded, total ->
                runOnUiThread {
                    val row = findRow(model.id) ?: return@runOnUiThread
                    val progress = row.findViewById<ProgressBar>(R.id.model_progress)
                    val state = row.findViewById<TextView>(R.id.model_state)
                    progress.visibility = View.VISIBLE
                    if (total > 0) {
                        progress.isIndeterminate = false
                        progress.max = 100
                        progress.progress = ((downloaded * 100) / total).toInt()
                        state.text = "Downloading ${progress.progress}%"
                    } else {
                        progress.isIndeterminate = true
                        state.text = "Downloading ${downloaded / (1024 * 1024)} MB"
                    }
                }
            }
            downloadingId = null
            when (result) {
                is ModelDownloader.Result.Success, ModelDownloader.Result.AlreadyInstalled -> {
                    prefs.modelId = model.id
                    Toast.makeText(this@SettingsActivity, "Ready: ${model.title}", Toast.LENGTH_SHORT).show()
                }
                is ModelDownloader.Result.Error -> {
                    Toast.makeText(this@SettingsActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
            renderModels()
        }
    }

    private fun findRow(modelId: String): ViewGroup? {
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child.tag == modelId) return child as? ViewGroup
        }
        // tag not set — match by title index
        val idx = ModelCatalog.models.indexOfFirst { it.id == modelId }
        return if (idx in 0 until list.childCount) list.getChildAt(idx) as? ViewGroup else null
    }
}
