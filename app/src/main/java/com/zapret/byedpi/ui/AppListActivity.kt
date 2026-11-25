package com.zapret.byedpi.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.zapret.byedpi.R
import com.zapret.byedpi.core.AppLog
import com.zapret.byedpi.core.AppMode
import com.zapret.byedpi.core.AppRules
import com.zapret.byedpi.core.VpnPhase
import com.zapret.byedpi.core.VpnRuntime
import com.zapret.byedpi.databinding.ActivityAppListBinding
import com.zapret.byedpi.service.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppListBinding
    private lateinit var adapter: AppListAdapter

    private var allApps: List<AppEntry> = emptyList()
    private val selected = AppRules.packages().toMutableSet()
    private var mode: AppMode = AppRules.mode()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = AppListAdapter { packageName, checked ->
            if (checked) selected += packageName else selected -= packageName
            updateCount()
        }
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }

        binding.rgMode.setOnCheckedChangeListener { _, checkedId ->
            mode = when (checkedId) {
                R.id.rbOnly -> AppMode.ONLY
                R.id.rbExcept -> AppMode.EXCEPT
                else -> AppMode.ALL
            }
            render()
        }

        when (mode) {
            AppMode.ONLY -> binding.rbOnly.isChecked = true
            AppMode.EXCEPT -> binding.rbExcept.isChecked = true
            AppMode.ALL -> binding.rbAll.isChecked = true
        }

        binding.etSearch.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            }
        )

        loadApps()
    }

    private fun loadApps() {
        binding.tvCount.text = getString(R.string.app_list_loading)
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = packageManager
                pm.getInstalledApplications(0)
                    .asSequence()
                    .filter { it.packageName != packageName }
                    .map {
                        AppEntry(
                            packageName = it.packageName,
                            label = pm.getApplicationLabel(it).toString()
                        )
                    }
                    .sortedBy { it.label.lowercase() }
                    .toList()
            }
            allApps = apps
            AppLog.i("AppList", "Загружено приложений: ${apps.size}")
            render()
        }
    }

    private fun render() {
        val query = binding.etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = if (query.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.label.lowercase().contains(query) ||
                    it.packageName.lowercase().contains(query)
            }
        }
        adapter.submit(filtered, selected, mode != AppMode.ALL)
        updateCount()
    }

    private fun updateCount() {
        binding.tvCount.text = getString(
            R.string.app_list_count,
            selected.size,
            allApps.size
        )
    }

    private fun save() {
        if (mode == AppMode.ONLY && selected.isEmpty()) {
            Toast.makeText(this, R.string.app_list_only_empty, Toast.LENGTH_LONG).show()
            return
        }
        AppRules.save(mode, selected)
        AppLog.i(
            "AppList",
            "Per-App сохранён: mode=$mode, пакетов=${selected.size}"
        )
        Toast.makeText(this, R.string.app_list_saved, Toast.LENGTH_SHORT).show()

        if (VpnRuntime.state.value.phase == VpnPhase.RUNNING ||
            VpnRuntime.state.value.phase == VpnPhase.STARTING ||
            VpnRuntime.state.value.phase == VpnPhase.STOPPING
        ) {
            Toast.makeText(this, R.string.vpn_restarting, Toast.LENGTH_LONG).show()
            lifecycleScope.launch {
                VpnController.restart(this@AppListActivity)
                finish()
            }
        } else {
            finish()
        }
    }
}
