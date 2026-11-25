package com.zapret.byedpi.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.zapret.byedpi.R
import com.zapret.byedpi.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        binding.bottomNav.selectedItemId = R.id.nav_settings
        setContentView(binding.root)

        binding.btnApps.setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }
        binding.btnDomains.setOnClickListener {
            startActivity(Intent(this, DomainListActivity::class.java))
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            BottomNavNavigator.navigate(this, R.id.nav_settings, item.itemId)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        binding.bottomNav.selectedItemId = R.id.nav_settings
    }
}
