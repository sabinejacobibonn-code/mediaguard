package com.mediaguard.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.mediaguard.R
import com.mediaguard.service.OverlayService

class MainActivity : AppCompatActivity() {

    // FIX R2: nullable statt lateinit – onResume() kann nie UninitializedPropertyAccessException werfen
    private var btnOverlay: Button? = null
    private var btnAccessibility: Button? = null
    private var btnStartStop: Button? = null
    private var tvPermStatus: TextView? = null
    private var switchEnabled: Switch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnOverlay       = findViewById(R.id.btnGrantOverlay)
        btnAccessibility = findViewById(R.id.btnGrantAccessibility)
        btnStartStop     = findViewById(R.id.btnStartStop)
        tvPermStatus     = findViewById(R.id.tvPermStatus)
        switchEnabled    = findViewById(R.id.switchEnabled)

        btnOverlay?.setOnClickListener { requestOverlayPermission() }
        btnAccessibility?.setOnClickListener { requestAccessibilityPermission() }

        btnStartStop?.setOnClickListener {
            if (switchEnabled?.isChecked == true) {
                OverlayService.start(this)
                Toast.makeText(this, "MediaGuard gestartet ✅", Toast.LENGTH_SHORT).show()
            } else {
                OverlayService.stop(this)
                Toast.makeText(this, "MediaGuard gestoppt", Toast.LENGTH_SHORT).show()
            }
        }

        switchEnabled?.setOnCheckedChangeListener { _, isChecked ->
            btnStartStop?.text = if (isChecked) "▶ Overlay starten" else "⏹ Overlay stoppen"
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityEnabled()

        val status = buildString {
            append(if (hasOverlay) "✅" else "❌")
            append(" Overlay-Berechtigung\n")
            append(if (hasAccessibility) "✅" else "❌")
            append(" Accessibility Service\n")
            if (hasOverlay && hasAccessibility) {
                append("\n🚀 Bereit! Starte das Overlay.")
            } else {
                append("\n⚠️ Bitte alle Berechtigungen erteilen.")
            }
        }

        tvPermStatus?.text = status
        btnStartStop?.isEnabled = hasOverlay
    }

    private fun requestOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Exception) {
            // Fallback: allgemeine Einstellungen öffnen
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun requestAccessibilityPermission() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "Aktiviere 'MediaGuard' unter Barrierefreiheit",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "$packageName/com.mediaguard.service.MediaGuardAccessibilityService"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(service)
    }
}
