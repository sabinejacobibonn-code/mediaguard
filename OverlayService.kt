package com.mediaguard.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.mediaguard.analysis.*
import com.mediaguard.ui.SelfCheckActivity
import kotlinx.coroutines.*

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "mediaguard_overlay"
        const val NOTIFICATION_ID = 1001
        const val ACTION_UPDATE_TEXT = "UPDATE_TEXT"
        const val EXTRA_TEXT = "text_content"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayAdded = false
    private var isMinimized = false
    // BUG B FIX: Flag damit startAnalysisLoop() nicht läuft wenn setupOverlay() fehlschlug
    private var overlayReady = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val textModule = TextAnalysisModule()
    private val audioModule = AudioAnalysisModule()
    private val visualModule = VisualAnalysisModule()

    private var sessionStartTime = System.currentTimeMillis()
    private var emotionalReaction = 30
    private var scrollSpeed = 20
    private var selfCheckScore = 0

    private var tvManipScore: TextView? = null
    private var tvVulnScore: TextView? = null
    private var tvAppTime: TextView? = null
    private var tvBotSuspect: TextView? = null
    private var tvReflection: TextView? = null
    private var tvStatus: TextView? = null
    private var progressManip: ProgressBar? = null
    private var progressVuln: ProgressBar? = null
    private var layoutExpanded: View? = null
    private var layoutMinimized: View? = null
    private var tvMiniBadge: TextView? = null

    private val reflectionQuestions = listOf(
        "Geht es dir besser als vorher?",
        "Warum bist du gerade hier?",
        "Was suchst du gerade?",
        "Brauchst du das wirklich?",
        "Wie fühlst du dich – ehrlich?",
        "Seit wann scrollst du schon?",
        "Wem nützt dieser Inhalt?",
        "Wäre eine Pause gut?"
    )
    // BUG E FIX: Long statt Int verhindert theoretischen overflow
    private var reflectionIndex = 0L

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // BUG P4 FIX: setOverlayRunning(true) erst NACH erfolgreichem Setup (in setupOverlay)
        setupOverlay()

        // BUG B FIX: Analyse nur starten wenn Overlay erfolgreich angelegt wurde
        if (overlayReady) {
            startAnalysisLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent kann null sein bei START_STICKY nach System-Kill — das ist korrekt abgesichert
        intent?.getStringExtra(EXTRA_TEXT)?.let { text ->
            if (text.isNotBlank() && overlayReady) {
                serviceScope.launch(Dispatchers.Default) {
                    val score = textModule.analyze(text).score
                    withContext(Dispatchers.Main) {
                        lastTextScore = score
                        updateManipulationDisplay()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // BUG F FIX: Status beim Beenden zurücksetzen
        setOverlayRunning(false)
        serviceScope.cancel()
        audioModule.stopMonitoring()
        visualModule.reset()
        removeOverlaySafely()
        super.onDestroy()
    }

    // BUG F FIX: Zentrale Methode für SharedPreferences-Status
    private fun setOverlayRunning(running: Boolean) {
        try {
            getSharedPreferences("mediaguard", MODE_PRIVATE)
                .edit().putBoolean("overlay_running", running).apply()
        } catch (_: Exception) {}
    }

    private fun removeOverlaySafely() {
        if (overlayAdded) {
            try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
            overlayAdded = false
        }
        overlayView = null
    }

    // ── Overlay Setup ─────────────────────────────────────────────────────────

    private fun setupOverlay() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val view = layoutInflater.inflate(com.mediaguard.R.layout.overlay_main, null)
            overlayView = view

            tvManipScore    = view.findViewById(com.mediaguard.R.id.tvManipScore)
            tvVulnScore     = view.findViewById(com.mediaguard.R.id.tvVulnScore)
            tvAppTime       = view.findViewById(com.mediaguard.R.id.tvAppTime)
            tvBotSuspect    = view.findViewById(com.mediaguard.R.id.tvBotSuspect)
            tvReflection    = view.findViewById(com.mediaguard.R.id.tvReflection)
            tvStatus        = view.findViewById(com.mediaguard.R.id.tvStatus)
            progressManip   = view.findViewById(com.mediaguard.R.id.progressManip)
            progressVuln    = view.findViewById(com.mediaguard.R.id.progressVuln)
            layoutExpanded  = view.findViewById(com.mediaguard.R.id.layoutExpanded)
            layoutMinimized = view.findViewById(com.mediaguard.R.id.layoutMinimized)
            tvMiniBadge     = view.findViewById(com.mediaguard.R.id.tvMiniBadge)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 16
                y = 200
            }

            setupDrag(view, params)

            view.findViewById<View>(com.mediaguard.R.id.btnToggle)
                ?.setOnClickListener { toggleMinimize() }
            view.findViewById<View>(com.mediaguard.R.id.btnSelfCheck)
                ?.setOnClickListener {
                    try {
                        startActivity(
                            Intent(this, SelfCheckActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {}
                }
            view.findViewById<View>(com.mediaguard.R.id.btnClose)
                ?.setOnClickListener { stop(this) }
            view.findViewById<View>(com.mediaguard.R.id.btnNextQuestion)
                ?.setOnClickListener { showNextReflectionQuestion() }

            try {
                windowManager?.addView(view, params)
                overlayAdded = true
                overlayReady = true
                setOverlayRunning(true)  // BUG P4 FIX: erst hier, wenn Overlay wirklich läuft
            } catch (e: WindowManager.BadTokenException) {
                overlayAdded = false
                overlayReady = false
                stopSelf()
            } catch (e: Exception) {
                overlayAdded = false
                overlayReady = false
                stopSelf()
            }

        } catch (e: Exception) {
            overlayReady = false
            stopSelf()
        }
    }

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (initialTouchX - event.rawX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    if (overlayAdded) {
                        try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleMinimize() {
        isMinimized = !isMinimized
        layoutExpanded?.visibility  = if (isMinimized) View.GONE else View.VISIBLE
        layoutMinimized?.visibility = if (isMinimized) View.VISIBLE else View.GONE
    }

    // ── Analyse-Schleife ──────────────────────────────────────────────────────

    private fun startAnalysisLoop() {
        audioModule.onResult = { result -> updateAudioResult(result) }
        audioModule.startMonitoring(serviceScope)

        // BUG C FIX: SharedPreferences alle 5s auslesen (SelfCheck-Werte abholen)
        serviceScope.launch {
            while (isActive) {
                readSelfCheckFromPrefs()
                updateSessionTime()
                if (tvReflection?.text.isNullOrBlank()) showNextReflectionQuestion()
                recalculateScores()
                delay(3000)
            }
        }

        serviceScope.launch {
            while (isActive) {
                delay(45000)
                showNextReflectionQuestion()
            }
        }
    }

    // BUG C FIX: Werte aus SharedPreferences lesen die SelfCheckActivity geschrieben hat
    private fun readSelfCheckFromPrefs() {
        try {
            val prefs = getSharedPreferences("mediaguard", MODE_PRIVATE)
            val emotional = prefs.getInt("emotional", 30)
            val energy    = prefs.getInt("energy", 50)
            // Hohes emotional + niedrige energy = hohe Vulnerabilität
            emotionalReaction = emotional
            selfCheckScore = ((emotional + (100 - energy)) / 2).coerceIn(0, 100)
        } catch (_: Exception) {}
    }

    // ── Score-Berechnungen ────────────────────────────────────────────────────

    private var lastTextScore = 0
    private var lastAudioScore = 0
    private var lastVisualScore = 0

    private fun updateAudioResult(result: AudioAnalysisModule.AudioAnalysisResult) {
        lastAudioScore = result.score
        val botSuspect = result.botScore > 60
        tvBotSuspect?.text = if (botSuspect) "🤖 Bot-Verdacht!" else "👤 Stimme: menschlich"
        tvBotSuspect?.setTextColor(
            if (botSuspect) 0xFFFF5722.toInt() else 0xFF4CAF50.toInt()
        )
        updateManipulationDisplay()
    }

    private fun recalculateScores() {
        updateManipulationDisplay()
        updateVulnerabilityDisplay()
    }

    private fun updateManipulationDisplay() {
        val result = ManipulationScoreCalculator.calculate(
            textScore = lastTextScore,
            audioScore = lastAudioScore,
            visualScore = lastVisualScore
        )
        tvManipScore?.text = "${result.score}/100 ${result.label}"
        tvManipScore?.setTextColor(result.color)
        progressManip?.progress = result.score
        progressManip?.progressTintList =
            android.content.res.ColorStateList.valueOf(result.color)
        tvMiniBadge?.text = "${result.score}"
        tvMiniBadge?.setBackgroundColor(result.color)
        tvStatus?.text = when {
            result.score > 75 -> "⚠️ Hohe Manipulation erkannt!"
            result.score > 50 -> "Vorsicht: Manipulationsmuster"
            result.score > 25 -> "Leichte Auffälligkeiten"
            else              -> "Inhalt unauffällig"
        }
    }

    private fun updateVulnerabilityDisplay() {
        val sessionMins = ((System.currentTimeMillis() - sessionStartTime) / 60000).toInt()
        val result = VulnerabilityScoreCalculator.calculate(
            sessionMinutes = sessionMins,
            emotionalReaction = emotionalReaction,
            scrollSpeedFactor = scrollSpeed,
            selfCheckScore = selfCheckScore
        )
        tvVulnScore?.text = "${result.score}/100 ${result.label}"
        tvVulnScore?.setTextColor(result.vulnColor())
        progressVuln?.progress = result.score
    }

    private fun VulnerabilityScoreCalculator.VulnerabilityResult.vulnColor(): Int = when (level) {
        VulnerabilityScoreCalculator.Level.RESILIENT  -> 0xFF4CAF50.toInt()
        VulnerabilityScoreCalculator.Level.AWARE      -> 0xFF8BC34A.toInt()
        VulnerabilityScoreCalculator.Level.VULNERABLE -> 0xFFFF9800.toInt()
        VulnerabilityScoreCalculator.Level.HIGH_RISK  -> 0xFFF44336.toInt()
    }

    private fun updateSessionTime() {
        val elapsed = System.currentTimeMillis() - sessionStartTime
        tvAppTime?.text = "⏱ ${elapsed / 60000}m ${(elapsed / 1000) % 60}s"
    }

    private fun showNextReflectionQuestion() {
        // BUG E FIX: Long verhindert theoretischen Int-Overflow
        tvReflection?.text =
            reflectionQuestions[(reflectionIndex % reflectionQuestions.size).toInt()]
        reflectionIndex++
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "MediaGuard Overlay", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "MediaGuard läuft im Hintergrund"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MediaGuard aktiv")
            .setContentText("Overlay läuft – antippe zum Verwalten")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
}
