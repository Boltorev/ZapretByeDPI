package com.zapret.byedpi.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zapret.byedpi.R
import com.zapret.byedpi.core.AppLog
import com.zapret.byedpi.core.DomainStore
import com.zapret.byedpi.core.VpnPhase
import com.zapret.byedpi.core.VpnRuntime
import com.zapret.byedpi.databinding.ActivityDomainsBinding
import com.zapret.byedpi.service.VpnController
import kotlinx.coroutines.launch

class DomainListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDomainsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDomainsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnClear.setOnClickListener {
            binding.etDomains.setText("")
            updateCount()
        }
        binding.btnExample.setOnClickListener {
            val current = binding.etDomains.text?.toString().orEmpty()
            val example = listOf(
                "youtube.com",
                "googlevideo.com",
                "discord.com",
                "discord.gg",
                "discord.media"
            )
            val existing = current.lineSequence()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            val add = example.filter { it !in existing }
            if (add.isNotEmpty()) {
                val prefix = if (current.isBlank()) "" else current.trimEnd() + "\n"
                binding.etDomains.setText(prefix + add.joinToString("\n"))
                updateCount()
            }
        }

        binding.etDomains.setText(DomainStore.loadText(this))
        updateCount()
        binding.etDomains.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateCount()
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            }
        )
    }

    private fun updateCount() {
        val count = binding.etDomains.text
            ?.lineSequence()
            ?.map { it.trim() }
            ?.count { it.isNotEmpty() && !it.startsWith("#") }
            ?: 0
        binding.tvCount.text = getString(R.string.domains_count, count)
    }

    private fun save() {
        val count = DomainStore.save(this, binding.etDomains.text?.toString().orEmpty())
        binding.etDomains.setText(DomainStore.loadText(this))
        updateCount()
        AppLog.i("Domains", "Сохранено доменов: $count")
        Toast.makeText(
            this,
            getString(R.string.domains_saved, count),
            Toast.LENGTH_SHORT
        ).show()

        if (VpnRuntime.state.value.phase == VpnPhase.RUNNING ||
            VpnRuntime.state.value.phase == VpnPhase.STARTING ||
            VpnRuntime.state.value.phase == VpnPhase.STOPPING
        ) {
            Toast.makeText(this, R.string.vpn_restarting, Toast.LENGTH_LONG).show()
            lifecycleScope.launch {
                VpnController.restart(this@DomainListActivity)
                finish()
            }
        } else {
            finish()
        }
    }
}
