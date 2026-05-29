package com.mediaguard.analysis

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*

/**
 * AudioAnalysisModule – Bug-bereinigt
 * Fixes: bufferSize ERROR_BAD_VALUE (Bug 2), stop() auf uninit AudioRecord (Bug 3),
 *        audioRecord.read() ANR-Risiko (Bug 11)
 */
class AudioAnalysisModule {

    private var audioRecord: AudioRecord? = null
    // BUG Q3 FIX: @Volatile damit isRecording auf allen CPU-Cores sofort sichtbar ist
    @Volatile private var isRecording = false
    private var analysisJob: Job? = null

    data class AudioAnalysisResult(
        val score: Int,
        val volumeScore: Int,
        val urgencyScore: Int,
        val botScore: Int,
        val currentVolume: Float,
        val detectedPatterns: List<String>
    )

    private val volumeHistory = ArrayDeque<Float>()
    private val zeroCrossingHistory = ArrayDeque<Float>()

    var onResult: ((AudioAnalysisResult) -> Unit)? = null

    fun startMonitoring(scope: CoroutineScope) {
        if (isRecording) return

        val sampleRate = 16000

        // BUG 2 FIX: getMinBufferSize kann ERROR_BAD_VALUE (-2) oder ERROR (-1) zurückgeben
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf == AudioRecord.ERROR || minBuf <= 0) {
            startSimulatedMode(scope)
            return
        }
        val bufferSize = minBuf * 2

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                startSimulatedMode(scope)
                return
            }

            audioRecord = record
            isRecording = true
            record.startRecording()

            // BUG S1 FIX: audioRecord.read() ist ein nativer blocking call –
            // withTimeoutOrNull() kann ihn NICHT abbrechen, da native Calls Coroutine-Cancellation ignorieren.
            // Lösung: READ_NON_BLOCKING Mode → liest verfügbare Samples sofort, blockiert nie.
            // Wenn keine Daten vorhanden: delay(50ms) und nächster Versuch.
            analysisJob = scope.launch(Dispatchers.Default) {
                val buffer = ShortArray(bufferSize / 2)
                while (isActive && isRecording) {
                    try {
                        val read = audioRecord?.read(buffer, 0, buffer.size,
                            AudioRecord.READ_NON_BLOCKING) ?: 0

                        if (read > 0) {
                            val result = processAudioBuffer(buffer, read)
                            withContext(Dispatchers.Main) {
                                onResult?.invoke(result)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Alle anderen Einzelfehler ruhig ignorieren
                    }
                    delay(50) // Kurze Pause – non-blocking read sofort wiederholen
                }
            }
        } catch (e: SecurityException) {
            startSimulatedMode(scope)
        } catch (e: Exception) {
            startSimulatedMode(scope)
        }
    }

    private fun startSimulatedMode(scope: CoroutineScope) {
        analysisJob = scope.launch {
            while (isActive) {
                val simResult = AudioAnalysisResult(
                    score = 25,
                    volumeScore = 20,
                    urgencyScore = 25,
                    botScore = 10,
                    currentVolume = 0.2f,
                    detectedPatterns = listOf("🎤 Mikrofon nicht verfügbar – Simulation")
                )
                withContext(Dispatchers.Main) {
                    onResult?.invoke(simResult)
                }
                delay(3000)
            }
        }
    }

    private fun processAudioBuffer(buffer: ShortArray, size: Int): AudioAnalysisResult {
        if (size <= 0) return emptyResult()
        val detected = mutableListOf<String>()

        var sum = 0.0
        for (i in 0 until size) {
            sum += buffer[i].toDouble() * buffer[i].toDouble()
        }
        val rms = Math.sqrt(sum / size)
        val normalizedVolume = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
        volumeHistory.addCapped(normalizedVolume)

        val avgVolume = volumeHistory.safeAverage().toFloat()
        val volumeSpike = avgVolume > 0f && normalizedVolume > avgVolume * 1.8f && normalizedVolume > 0.3f
        val volumeScore = when {
            normalizedVolume > 0.7f -> { detected.add("📢 Lautstärke-Spitze"); 80 }
            normalizedVolume > 0.5f -> { detected.add("🔊 Erhöhte Lautstärke"); 50 }
            volumeSpike -> { detected.add("⚡ Plötzliche Lautstärke"); 60 }
            else -> (normalizedVolume * 30).toInt()
        }

        var zeroCrossings = 0
        for (i in 1 until size) {
            if ((buffer[i] > 0 && buffer[i - 1] < 0) || (buffer[i] < 0 && buffer[i - 1] > 0)) {
                zeroCrossings++
            }
        }
        val zcr = zeroCrossings.toFloat() / size
        zeroCrossingHistory.addCapped(zcr)

        val urgencyScore = when {
            zcr > 0.15f -> { detected.add("⚡ Hohe Dringlichkeit erkannt"); 70 }
            zcr > 0.10f -> { detected.add("🏃 Schnelles Sprechtempo"); 45 }
            else -> (zcr * 300).toInt().coerceIn(0, 30)
        }

        val zcrVariance = if (zeroCrossingHistory.size > 5) {
            val mean = zeroCrossingHistory.safeAverage()
            val variances = zeroCrossingHistory.map { (it - mean) * (it - mean) }
            // BUG P2 FIX: Kotlin .average() auf leerem Iterable gibt NaN zurück → safeAverage nutzen
            if (variances.isEmpty()) 0.01 else variances.sumOf { it } / variances.size
        } else 0.01

        val botScore = when {
            zcrVariance < 0.0001 && normalizedVolume > 0.1f -> {
                detected.add("🤖 Monotone Stimme / Bot-Verdacht"); 75
            }
            zcrVariance < 0.0005 && normalizedVolume > 0.1f -> {
                detected.add("🎭 Gleichförmige Stimme"); 40
            }
            else -> 10
        }

        val total = (volumeScore * 0.4 + urgencyScore * 0.35 + botScore * 0.25)
            .toInt().coerceIn(0, 100)

        return AudioAnalysisResult(
            score = total,
            volumeScore = volumeScore,
            urgencyScore = urgencyScore,
            botScore = botScore,
            currentVolume = normalizedVolume,
            detectedPatterns = detected
        )
    }

    // BUG 3 FIX: state prüfen vor stop()
    fun stopMonitoring() {
        isRecording = false
        analysisJob?.cancel()
        analysisJob = null
        try {
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.stop()
            }
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        volumeHistory.clear()
        zeroCrossingHistory.clear()
    }

    private fun emptyResult() = AudioAnalysisResult(0, 0, 0, 0, 0f, emptyList())
}

private fun ArrayDeque<Float>.addCapped(element: Float) {
    addLast(element)
    while (size > 30) removeFirst()
}

private fun ArrayDeque<Float>.safeAverage(): Double =
    if (isEmpty()) 0.0 else sumOf { it.toDouble() } / size
