package com.mediaguard.analysis

import android.graphics.Bitmap
import android.graphics.Color

/**
 * VisualAnalysisModule – Bug-bereinigt
 * Fixes: Division durch 0 bei w/h=0 (Bug 4), recycled Bitmap (Bug 5),
 *        Memory Leak previousFrameBitmap (Bug 6)
 */
class VisualAnalysisModule {

    data class VisualAnalysisResult(
        val score: Int,
        val redElementScore: Int,
        val textOverlayScore: Int,
        val distortionScore: Int,
        val fastCutScore: Int,
        val detectedPatterns: List<String>
    )

    private var previousFrameBitmap: Bitmap? = null
    private val frameChangeHistory = ArrayDeque<Float>()

    fun analyze(bitmap: Bitmap): VisualAnalysisResult {
        // BUG 5 FIX: recycled oder leere Bitmap sofort ablehnen
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return emptyResult()
        }

        val detected = mutableListOf<String>()

        val redScore = try {
            analyzeRedElements(bitmap)
        } catch (_: Exception) { 0 }
        when {
            redScore > 70 -> detected.add("🔴 Viele rote Warnelemente")
            redScore > 40 -> detected.add("🟠 Erhöhter Rotanteil")
        }

        // BUG 6 FIX: alten Frame recyclen bevor neue Referenz gesetzt wird
        val fastCutScore = try {
            val prev = previousFrameBitmap
            if (prev != null && !prev.isRecycled) {
                val diff = calculateFrameDifference(prev, bitmap)
                frameChangeHistory.addCapped(diff)
                when {
                    frameChangeHistory.safeAverage() > 0.6 -> {
                        detected.add("⚡ Sehr schnelle Schnittfolge"); 85
                    }
                    frameChangeHistory.safeAverage() > 0.4 -> {
                        detected.add("🎬 Schnelle Schnitte"); 55
                    }
                    frameChangeHistory.safeAverage() > 0.2 -> 25
                    else -> 10
                }
            } else 0
        } catch (_: Exception) { 0 }

        // BUG 6 FIX: alten Bitmap recyclen
        // BUG P1 FIX: Nie die volle Bitmap kopieren (OOM-Risiko) – nur 50x50 Thumbnail speichern
        val old = previousFrameBitmap
        previousFrameBitmap = try {
            Bitmap.createScaledBitmap(bitmap, 50, 50, false)
        } catch (_: Exception) { null }
        if (old != null && !old.isRecycled) {
            old.recycle()
        }

        val textOverlayScore = try {
            analyzeTextOverlayLikelihood(bitmap)
        } catch (_: Exception) { 0 }
        if (textOverlayScore > 60) detected.add("📝 Viele Text-Overlays / Ausrufezeichen")

        val distortionScore = try {
            analyzeDistortion(bitmap)
        } catch (_: Exception) { 0 }
        if (distortionScore > 50) detected.add("🔍 Mögliche Bildmanipulation")

        val total = (redScore * 0.35 + fastCutScore * 0.25 + textOverlayScore * 0.25 + distortionScore * 0.15)
            .toInt().coerceIn(0, 100)

        return VisualAnalysisResult(
            score = total,
            redElementScore = redScore,
            textOverlayScore = textOverlayScore,
            distortionScore = distortionScore,
            fastCutScore = fastCutScore,
            detectedPatterns = detected
        )
    }

    private fun analyzeRedElements(bitmap: Bitmap): Int {
        val sampleBitmap = Bitmap.createScaledBitmap(bitmap, 100, 100, false)
        var redPixels = 0
        try {
            for (x in 0 until 100) {
                for (y in 0 until 100) {
                    val pixel = sampleBitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    if (r > 180 && g < 80 && b < 80) redPixels++
                    else if (r > 200 && g in 100..149 && b < 50) redPixels++
                }
            }
        } finally {
            if (!sampleBitmap.isRecycled) sampleBitmap.recycle()
        }
        return ((redPixels.toFloat() / 10000f) * 300).toInt().coerceIn(0, 100)
    }

    private fun calculateFrameDifference(prev: Bitmap, current: Bitmap): Float {
        // BUG 4 FIX: w/h müssen > 0 sein, sonst Division durch 0
        val w = minOf(prev.width, current.width, 50).coerceAtLeast(1)
        val h = minOf(prev.height, current.height, 50).coerceAtLeast(1)

        val prevScaled = Bitmap.createScaledBitmap(prev, w, h, false)
        val currScaled = Bitmap.createScaledBitmap(current, w, h, false)

        var diffSum = 0L
        val maxDiff = w.toLong() * h.toLong() * 255L * 3L  // Long-Overflow verhindert

        try {
            for (x in 0 until w) {
                for (y in 0 until h) {
                    val p = prevScaled.getPixel(x, y)
                    val c = currScaled.getPixel(x, y)
                    diffSum += Math.abs(Color.red(p) - Color.red(c))
                    diffSum += Math.abs(Color.green(p) - Color.green(c))
                    diffSum += Math.abs(Color.blue(p) - Color.blue(c))
                }
            }
        } finally {
            if (!prevScaled.isRecycled) prevScaled.recycle()
            if (!currScaled.isRecycled) currScaled.recycle()
        }

        if (maxDiff == 0L) return 0f
        return (diffSum.toFloat() / maxDiff.toFloat()).coerceIn(0f, 1f)
    }

    private fun analyzeTextOverlayLikelihood(bitmap: Bitmap): Int {
        val sample = Bitmap.createScaledBitmap(bitmap, 80, 80, false)
        var highContrastPixels = 0
        try {
            for (x in 1 until 79) {
                for (y in 1 until 79) {
                    val center = sample.getPixel(x, y)
                    val right = sample.getPixel(x + 1, y)
                    if (Math.abs(getBrightness(center) - getBrightness(right)) > 150) {
                        highContrastPixels++
                    }
                }
            }
        } finally {
            if (!sample.isRecycled) sample.recycle()
        }
        return ((highContrastPixels.toFloat() / (78f * 78f)) * 200).toInt().coerceIn(0, 100)
    }

    private fun analyzeDistortion(bitmap: Bitmap): Int {
        val sample = Bitmap.createScaledBitmap(bitmap, 60, 60, false)
        val colorBuckets = IntArray(8)
        try {
            for (x in 0 until 60) {
                for (y in 0 until 60) {
                    val pixel = sample.getPixel(x, y)
                    colorBuckets[getApproxHue(pixel)]++
                }
            }
        } finally {
            if (!sample.isRecycled) sample.recycle()
        }
        val maxBucket = colorBuckets.max()
        val dominanceRatio = maxBucket.toFloat() / (60f * 60f)
        return when {
            dominanceRatio > 0.8f -> 70
            dominanceRatio > 0.65f -> 40
            else -> 10
        }
    }

    private fun getBrightness(pixel: Int): Int =
        (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114).toInt()

    private fun getApproxHue(pixel: Int): Int {
        val r = Color.red(pixel) / 32
        val g = Color.green(pixel) / 32
        val b = Color.blue(pixel) / 32
        return ((r * 9 + g * 3 + b) % 8).coerceIn(0, 7)
    }

    fun reset() {
        val old = previousFrameBitmap
        previousFrameBitmap = null
        if (old != null && !old.isRecycled) old.recycle()
        frameChangeHistory.clear()
    }

    private fun emptyResult() = VisualAnalysisResult(0, 0, 0, 0, 0, emptyList())
}

private fun ArrayDeque<Float>.addCapped(element: Float) {
    addLast(element)
    while (size > 20) removeFirst()
}

private fun ArrayDeque<Float>.safeAverage(): Double =
    if (isEmpty()) 0.0 else sumOf { it.toDouble() } / size
