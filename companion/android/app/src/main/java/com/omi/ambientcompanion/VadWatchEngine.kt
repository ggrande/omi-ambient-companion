package com.omi.ambientcompanion

import kotlin.math.log10
import kotlin.math.sqrt

data class VadFrameResult(
    val speech: Boolean,
    val dbfs: Double,
    val zeroRatio: Double,
    val zeroCrossingHz: Double = 0.0,
    val voiceBandScore: Double = 0.0,
    val volumeTrendDb: Double = 0.0,
)

class VadWatchEngine(
    private val rmsSpeechDbfsThreshold: Double = -52.0,
    private val zeroRatioSilenceThreshold: Double = 0.98,
    private val speechFramesToTrigger: Int = 4,
    private val silenceFramesToEnd: Int = 80,
    private val preRollBytes: Int = 16_000 * 2 * 15,
) {
    private val preRoll = ArrayDeque<ByteArray>()
    private var preRollSize = 0
    private var speechFrames = 0
    private var silenceFrames = 0
    private var lastDbfs: Double? = null
    var activeSpeech: Boolean = false
        private set

    fun accept(bytes: ByteArray): VadFrameResult {
        addPreRoll(bytes)
        val raw = analyzePcm16(bytes)
        val priorDbfs = lastDbfs
        val trend = priorDbfs?.let { (raw.dbfs - it).coerceIn(-30.0, 30.0) } ?: 0.0
        lastDbfs = raw.dbfs
        val result = raw.copy(volumeTrendDb = trend)
        val enoughVolume = result.dbfs >= rmsSpeechDbfsThreshold
        val enoughSignal = result.zeroRatio < zeroRatioSilenceThreshold
        val frequencyReinforced = result.voiceBandScore >= 0.45
        val volumePatternReinforced = result.volumeTrendDb >= 2.0 || activeSpeech
        val looksLikeSpeech = enoughVolume && enoughSignal && (frequencyReinforced || volumePatternReinforced)
        if (looksLikeSpeech) {
            speechFrames += 1
            silenceFrames = 0
        } else {
            silenceFrames += 1
            speechFrames = 0
        }
        if (!activeSpeech && speechFrames >= speechFramesToTrigger) activeSpeech = true
        if (activeSpeech && silenceFrames >= silenceFramesToEnd) activeSpeech = false
        return result.copy(speech = looksLikeSpeech)
    }

    fun drainPreRoll(): List<ByteArray> {
        val copy = preRoll.toList()
        preRoll.clear()
        preRollSize = 0
        return copy
    }

    private fun addPreRoll(bytes: ByteArray) {
        preRoll.add(bytes.copyOf())
        preRollSize += bytes.size
        while (preRollSize > preRollBytes && preRoll.isNotEmpty()) {
            preRollSize -= preRoll.removeFirst().size
        }
    }

    companion object {
        fun analyzePcm16(bytes: ByteArray): VadFrameResult {
            if (bytes.size < 2) return VadFrameResult(false, -120.0, 1.0)
            var sumSquares = 0.0
            var zeroSamples = 0
            var zeroCrossings = 0
            var samples = 0
            var previousSign = 0
            var i = 0
            while (i + 1 < bytes.size) {
                val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort().toInt()
                if (sample == 0) zeroSamples++
                val sign = when {
                    sample > 0 -> 1
                    sample < 0 -> -1
                    else -> previousSign
                }
                if (previousSign != 0 && sign != 0 && sign != previousSign) zeroCrossings++
                if (sign != 0) previousSign = sign
                val normalized = sample / 32768.0
                sumSquares += normalized * normalized
                samples++
                i += 2
            }
            val rms = if (samples == 0) 0.0 else sqrt(sumSquares / samples)
            val dbfs = if (rms <= 0.0000001) -120.0 else 20.0 * log10(rms)
            val durationSeconds = samples.toDouble() / SAMPLE_RATE.toDouble()
            val zeroCrossingHz = if (durationSeconds <= 0.0) 0.0 else zeroCrossings / (2.0 * durationSeconds)
            val voiceBandScore = when {
                zeroCrossingHz in 85.0..450.0 -> 1.0
                zeroCrossingHz in 450.0..950.0 -> 0.65
                zeroCrossingHz in 60.0..1200.0 -> 0.35
                else -> 0.0
            }
            return VadFrameResult(
                speech = dbfs >= -52.0,
                dbfs = dbfs,
                zeroRatio = zeroSamples.toDouble() / samples.coerceAtLeast(1),
                zeroCrossingHz = zeroCrossingHz,
                voiceBandScore = voiceBandScore,
            )
        }

        private const val SAMPLE_RATE = 16_000
    }
}
