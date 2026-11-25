package com.zapret.byedpi.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.zapret.byedpi.R
import com.zapret.byedpi.core.AppLog
import com.zapret.byedpi.core.LogEntry
import com.zapret.byedpi.core.LogLevel
import com.zapret.byedpi.databinding.ActivityLogBinding
import com.zapret.byedpi.util.LogExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private val adapter = LogLineAdapter()

    private var allEntries: List<LogEntry> = emptyList()
    private var levelFilter: LogLevel? = null

    private val createLogFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                LogExporter.write(this@LogActivity, uri)
            }
            Toast.makeText(
                this@LogActivity,
                if (saved) R.string.log_export_success else R.string.log_export_error,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        binding.bottomNav.selectedItemId = R.id.nav_logs
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnDownload.setOnClickListener {
            createLogFileLauncher.launch(LogExporter.suggestFileName())
        }
        binding.btnCopy.setOnClickListener { copyVisible() }
        binding.btnClear.setOnClickListener { confirmClear() }

        binding.bottomNav.setOnItemSelectedListener { item ->
            BottomNavNavigator.navigate(this, R.id.nav_logs, item.itemId)
            true
        }

        val levels = listOf(
            getString(R.string.log_level_all),
            getString(R.string.log_level_debug),
            getString(R.string.log_level_info),
            getString(R.string.log_level_warn),
            getString(R.string.log_level_error)
        )
        binding.spLevel.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            levels
        )
        binding.spLevel.setOnItemSelectedListener(
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    levelFilter = when (position) {
                        1 -> LogLevel.DEBUG
                        2 -> LogLevel.INFO
                        3 -> LogLevel.WARN
                        4 -> LogLevel.ERROR
                        else -> null
                    }
                    render()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        )

        binding.etSearch.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            }
        )

        binding.rvLog.layoutManager = LinearLayoutManager(this)
        binding.rvLog.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppLog.entries.collect { entries ->
                    allEntries = entries
                    render()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.bottomNav.selectedItemId = R.id.nav_logs
    }

    private fun filteredEntries(): List<LogEntry> {
        val query = binding.etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        return allEntries.asSequence()
            .filter { levelFilter == null || it.level == levelFilter }
            .filter {
                query.isEmpty() ||
                    it.tag.lowercase().contains(query) ||
                    it.message.lowercase().contains(query)
            }
            .toList()
    }

    private fun render() {
        val filtered = filteredEntries()
        val wasAtBottom = !binding.rvLog.canScrollVertically(1)
        adapter.submit(filtered)
        binding.tvCount.text = getString(
            R.string.log_count,
            filtered.size,
            allEntries.size
        )
        if (wasAtBottom && filtered.isNotEmpty()) {
            binding.rvLog.scrollToPosition(filtered.size - 1)
        }
    }

    private fun copyVisible() {
        val text = filteredEntries().joinToString("\n") { it.toDisplayLine() }
        if (text.isBlank()) {
            Toast.makeText(this, R.string.log_empty_filter, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ZapretByeDPI log", text))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage(R.string.log_clear_confirm)
            .setPositiveButton(R.string.log_clear) { _, _ ->
                AppLog.clear()
                AppLog.i("LogActivity", "Журнал очищен")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
