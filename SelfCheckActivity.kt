package com.mediaguard.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.mediaguard.R

class SelfCheckActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_self_check)

        // BUG Q1 FIX: Alle Views als nullable laden – NPE-sicher auch bei Layout-Fehlern
        val seekEmotional = findViewById<SeekBar?>(R.id.seekEmotional)
        val seekEnergy    = findViewById<SeekBar?>(R.id.seekEnergy)
        val tvEmotionalLabel = findViewById<TextView?>(R.id.tvEmotionalLabel)
        val tvEnergyLabel    = findViewById<TextView?>(R.id.tvEnergyLabel)
        val btnDone   = findViewById<Button?>(R.id.btnSelfCheckDone)
        val btnCancel = findViewById<Button?>(R.id.btnSelfCheckCancel)

        seekEmotional?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvEmotionalLabel?.text = getEmotionalLabel(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekEnergy?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvEnergyLabel?.text = getEnergyLabel(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnDone?.setOnClickListener {
            getSharedPreferences("mediaguard", MODE_PRIVATE).edit()
                .putInt("emotional", seekEmotional?.progress ?: 30)
                .putInt("energy",    seekEnergy?.progress    ?: 50)
                .apply()
            finish()
        }

        btnCancel?.setOnClickListener { finish() }
    }

    private fun getEmotionalLabel(progress: Int): String = when {
        progress < 20 -> "😌 Ruhig & entspannt"
        progress < 40 -> "🙂 Normal"
        progress < 60 -> "😟 Leicht besorgt"
        progress < 80 -> "😤 Aufgewühlt"
        else -> "😡 Sehr emotional"
    }

    private fun getEnergyLabel(progress: Int): String = when {
        progress < 20 -> "😴 Sehr müde"
        progress < 40 -> "😐 Müde"
        progress < 60 -> "🙂 Normal"
        progress < 80 -> "⚡ Wach"
        else -> "🔥 Sehr energiegeladen"
    }
}

class PermissionSetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
