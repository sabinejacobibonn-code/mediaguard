package com.mediaguard.analysis

import java.util.Calendar

/**
 * ManipulationScoreCalculator
 * Kombiniert Text-, Audio- und Visual-Scores.
 * Formel: 0.4*Text + 0.3*Audio + 0.3*Visuell
 */
object ManipulationScoreCalculator {

    data class ManipulationResult(
        val score: Int,          // 0–100
        val level: Level,
        val label: String,
        val color: Int,          // Android Color-Wert
        val allPatterns: List<String>
    )

    enum class Level { SAFE, LOW, MEDIUM, HIGH, CRITICAL }

    fun calculate(
        textScore: Int,
        audioScore: Int,
        visualScore: Int,
        textPatterns: List<String> = emptyList(),
        audioPatterns: List<String> = emptyList(),
        visualPatterns: List<String> = emptyList()
    ): ManipulationResult {

        val weighted = (textScore * 0.4 + audioScore * 0.3 + visualScore * 0.3)
            .toInt().coerceIn(0, 100)

        val level = when (weighted) {
            in 0..15   -> Level.SAFE
            in 16..35  -> Level.LOW
            in 36..55  -> Level.MEDIUM
            in 56..75  -> Level.HIGH
            else       -> Level.CRITICAL
        }

        val label = when (level) {
            Level.SAFE     -> "✅ Niedrig"
            Level.LOW      -> "🟡 Gering"
            Level.MEDIUM   -> "🟠 Mittel"
            Level.HIGH     -> "🔴 Hoch"
            Level.CRITICAL -> "🚨 Kritisch"
        }

        val color = when (level) {
            Level.SAFE     -> 0xFF4CAF50.toInt() // Grün
            Level.LOW      -> 0xFF8BC34A.toInt() // Hellgrün
            Level.MEDIUM   -> 0xFFFF9800.toInt() // Orange
            Level.HIGH     -> 0xFFF44336.toInt() // Rot
            Level.CRITICAL -> 0xFF9C27B0.toInt() // Lila (Alarm)
        }

        return ManipulationResult(
            score = weighted,
            level = level,
            label = label,
            color = color,
            allPatterns = textPatterns + audioPatterns + visualPatterns
        )
    }
}

/**
 * VulnerabilityScoreCalculator
 * Formel: 0.3*Zeit + 0.2*Emotion + 0.2*Scroll + 0.2*Tageszeit + 0.1*SelbstCheck
 */
object VulnerabilityScoreCalculator {

    data class VulnerabilityResult(
        val score: Int,         // 0–100
        val level: Level,
        val label: String,
        val factors: List<String>
    )

    enum class Level { RESILIENT, AWARE, VULNERABLE, HIGH_RISK }

    /**
     * @param sessionMinutes    Minuten in der App seit Start
     * @param emotionalReaction 0–100 (aus Selbst-Check: "Wie fühlst du dich?")
     * @param scrollSpeedFactor 0–100 (0=langsam, 100=sehr schnell/doomscrolling)
     * @param selfCheckScore    0–100 (Ergebnis des Selbst-Checks)
     */
    fun calculate(
        sessionMinutes: Int,
        emotionalReaction: Int,
        scrollSpeedFactor: Int,
        selfCheckScore: Int
    ): VulnerabilityResult {

        val factors = mutableListOf<String>()

        // Zeit-Score: >30min = erhöhtes Risiko
        val timeScore = when {
            sessionMinutes < 10  -> { 10 }
            sessionMinutes < 30  -> { factors.add("⏱️ ${sessionMinutes}min in App"); 30 }
            sessionMinutes < 60  -> { factors.add("⚠️ ${sessionMinutes}min – lange Session"); 60 }
            sessionMinutes < 120 -> { factors.add("🔴 ${sessionMinutes}min – sehr lang"); 80 }
            else                 -> { factors.add("🚨 ${sessionMinutes}min – Doomscrolling?"); 95 }
        }

        // Emotionaler Zustand
        if (emotionalReaction > 60) factors.add("💔 Erhöhte emotionale Reaktion")

        // Scroll-Verhalten
        val scrollScore = scrollSpeedFactor
        if (scrollSpeedFactor > 70) factors.add("📱 Schnelles Scrollen erkannt")

        // Tageszeit-Score
        val hourScore = getTimeOfDayVulnerabilityScore().also { h ->
            if (h > 60) factors.add("🌙 Spät nachts: erhöhte Vulnerabilität")
            else if (h > 40) factors.add("🌆 Abendstunden")
        }

        // Selbst-Check
        if (selfCheckScore > 50) factors.add("❓ Selbst-Check: Besorgniserregend")

        val total = (
            timeScore * 0.30 +
            emotionalReaction * 0.20 +
            scrollScore * 0.20 +
            hourScore * 0.20 +
            selfCheckScore * 0.10
        ).toInt().coerceIn(0, 100)

        val level = when (total) {
            in 0..25  -> Level.RESILIENT
            in 26..50 -> Level.AWARE
            in 51..75 -> Level.VULNERABLE
            else      -> Level.HIGH_RISK
        }

        val label = when (level) {
            Level.RESILIENT -> "💚 Gut geschützt"
            Level.AWARE     -> "💛 Aufmerksam sein"
            Level.VULNERABLE -> "🟠 Erhöhte Vulnerabilität"
            Level.HIGH_RISK -> "🔴 Hohes Risiko – Pause?"
        }

        return VulnerabilityResult(
            score = total,
            level = level,
            label = label,
            factors = factors
        )
    }

    private fun getTimeOfDayVulnerabilityScore(): Int {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 0..5   -> 90   // Tiefe Nacht: sehr hoch
            in 6..8   -> 30   // Morgen
            in 9..12  -> 10   // Vormittag
            in 13..17 -> 15   // Nachmittag
            in 18..20 -> 35   // Abend
            in 21..23 -> 65   // Spätabend
            else      -> 50
        }
    }
}
