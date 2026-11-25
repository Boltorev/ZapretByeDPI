package com.zapret.byedpi.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zapret.byedpi.R
import com.zapret.byedpi.databinding.ActivityTelegramBinding
import com.zapret.byedpi.telegram.TelegramPhase
import com.zapret.byedpi.telegram.TelegramPrefs
import com.zapret.byedpi.telegram.TelegramProxyController
import com.zapret.byedpi.telegram.TelegramRuntime
import com.zapret.byedpi.telegram.TelegramState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class TelegramActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTelegramBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTelegramBinding.inflate(layoutInflater)
        binding.bottomNav.selectedItemId = R.id.nav_telegram
        setContentView(binding.root)

        binding.btnTelegramStartStop.setOnClickListener {
            when (TelegramRuntime.state.value.phase) {
                TelegramPhase.RUNNING, TelegramPhase.STARTING -> TelegramProxyController.stop(this)
                else -> TelegramProxyController.start(this)
            }
        }
        binding.btnTelegramApply.setOnClickListener {
            TelegramProxyController.apply(this)
        }
        binding.btnTelegramCopy.setOnClickListener {
            if (!TelegramProxyController.copyLink(this)) {
                Toast.makeText(this, R.string.telegram_not_running, Toast.LENGTH_SHORT).show()
            }
        }

        binding.swTelegramCf.isChecked = TelegramPrefs.cfEnabled()
        binding.swTelegramCf.setOnCheckedChangeListener { _, checked ->
            TelegramPrefs.setCfEnabled(checked)
            Toast.makeText(this, R.string.telegram_cf_changed, Toast.LENGTH_SHORT).show()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            BottomNavNavigator.navigate(this, R.id.nav_telegram, item.itemId)
            true
        }

        renderTelegram(TelegramRuntime.state.value)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TelegramRuntime.state.collect { renderTelegram(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.bottomNav.selectedItemId = R.id.nav_telegram
    }

    private fun renderTelegram(state: TelegramState) {
        val color = when (state.phase) {
            TelegramPhase.RUNNING -> R.color.state_on
            TelegramPhase.STARTING -> R.color.state_busy
            TelegramPhase.ERROR -> R.color.state_off
            TelegramPhase.STOPPED -> R.color.text_secondary
        }
        val status = when (state.phase) {
            TelegramPhase.RUNNING -> getString(R.string.telegram_status_running, state.port)
            TelegramPhase.STARTING -> getString(R.string.telegram_status_starting)
            TelegramPhase.ERROR -> getString(
                R.string.telegram_status_error,
                state.error ?: "unknown"
            )
            TelegramPhase.STOPPED -> getString(R.string.telegram_status_off)
        }
        binding.tvTelegramStatus.text = status
        binding.tvTelegramStatus.setTextColor(ContextCompat.getColor(this, color))

        val secret = state.secret
        if (secret.isNullOrBlank()) {
            binding.tvTelegramSecret.visibility = android.view.View.GONE
        } else {
            binding.tvTelegramSecret.visibility = android.view.View.VISIBLE
            binding.tvTelegramSecret.text = getString(R.string.telegram_secret, secret)
        }

        val active = state.phase == TelegramPhase.RUNNING ||
            state.phase == TelegramPhase.STARTING
        binding.btnTelegramStartStop.text = if (active) {
            getString(R.string.telegram_stop)
        } else {
            getString(R.string.telegram_start)
        }
        val ready = state.phase == TelegramPhase.RUNNING
        binding.btnTelegramApply.isEnabled = ready
        binding.btnTelegramCopy.isEnabled = ready
        binding.btnTelegramApply.alpha = if (ready) 1f else 0.5f
        binding.btnTelegramCopy.alpha = if (ready) 1f else 0.5f
    }
}
