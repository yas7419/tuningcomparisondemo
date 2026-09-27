package com.example.tuningcomparisondemo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.widget.Button
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

class ToneGenerator(
    private val sampleRate: Int = 44100,
    private val noteButtonsProvider: () -> Map<Int, Button>
) {
    private var originalFreqMap: Map<Int, Double> = emptyMap()

    private val activeFreqs = CopyOnWriteArraySet<Double>()
    private val fadeOutMap = mutableMapOf<Double, Int>()
    private val fadeInMap = mutableMapOf<Double, Int>()
    private val phaseMap = mutableMapOf<Double, Double>()
    private val resumeQueue = mutableSetOf<Int>()

    private var audioTrack: AudioTrack? = null
    @Volatile private var isRunning = false

    private val fadeDurationMs = 20
    private val fadeSamples = sampleRate * fadeDurationMs / 1000
    private val noteIndexToFreq = mutableMapOf<Int, Double>()

    private var onResumeCallback: ((Int) -> Unit)? = null
    fun setOnResumeCallback(callback: (Int) -> Unit) {
        onResumeCallback = callback
    }

    fun start() {
        if (audioTrack != null) return

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes((sampleRate / 10) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        isRunning = true

        Thread {
            val bufferSize = sampleRate / 50
            val floatMixBuffer = FloatArray(bufferSize)
            val pcmBuffer = ShortArray(bufferSize)
            var silenceCounter = 0

            while (isRunning) {
                val currentActiveFreqs: Set<Double>
                val currentFadeOut: Map<Double, Int>
                val currentFadeIn: Map<Double, Int>
                val currentPhases: Map<Double, Double>
                val currentResumeQueue: Set<Int>
                var shouldStop = false

                synchronized(this) {
                    if (activeFreqs.isEmpty() && fadeOutMap.isEmpty() && fadeInMap.isEmpty() && resumeQueue.isEmpty()) {
                        silenceCounter += bufferSize
                        if (silenceCounter >= sampleRate / 5) {
                            shouldStop = true
                        }
                    } else {
                        silenceCounter = 0
                    }

                    currentActiveFreqs = activeFreqs.toSet()
                    currentFadeOut = fadeOutMap.toMap()
                    currentFadeIn = fadeInMap.toMap()
                    currentPhases = phaseMap.toMap()
                    currentResumeQueue = resumeQueue.toSet()
                }

                if (shouldStop) {
                    val silenceBuffer = ShortArray(bufferSize) { 0 }
                    repeat(3) { audioTrack?.write(silenceBuffer, 0, silenceBuffer.size) }
                    release()
                    break
                }

                for (i in floatMixBuffer.indices) {
                    floatMixBuffer[i] = 0f
                }

                val numActive = currentActiveFreqs.size
                val gainScale = if (numActive > 0) 0.3f / sqrt(numActive.toFloat()) else 0.3f

                val updatedPhases = mutableMapOf<Double, Double>()
                val finishedFadeOut = mutableSetOf<Double>()
                val finishedFadeIn = mutableSetOf<Double>()
                val updatedFadeOut = currentFadeOut.toMutableMap()
                val updatedFadeIn = currentFadeIn.toMutableMap()

                for (freq in currentActiveFreqs) {
                    val phaseStart = currentPhases[freq] ?: 0.0
                    val phaseInc = 2 * PI * freq / sampleRate
                    var phase = phaseStart

                    val fadeOutRemaining = currentFadeOut[freq] ?: -1
                    val fadeOutStep = if (fadeOutRemaining > 0) 1.0 / fadeSamples else 0.0
                    var fadeOutFactor = if (fadeOutRemaining > 0) fadeOutRemaining.toDouble() / fadeSamples else 1.0

                    val fadeInRemaining = currentFadeIn[freq] ?: -1
                    val fadeInStep = if (fadeInRemaining > 0) 1.0 / fadeSamples else 0.0
                    var fadeInFactor = if (fadeInRemaining > 0) (fadeSamples - fadeInRemaining).toDouble() / fadeSamples else 1.0

                    for (i in floatMixBuffer.indices) {
                        val factor = fadeOutFactor * fadeInFactor
                        val sample = (sin(phase) * factor).toFloat() * gainScale
                        floatMixBuffer[i] = floatMixBuffer[i] + sample
                        phase += phaseInc

                        if (fadeOutRemaining > 0) fadeOutFactor -= fadeOutStep
                        if (fadeInRemaining > 0) fadeInFactor += fadeInStep
                    }

                    updatedPhases[freq] = phase

                    if (fadeOutRemaining > 0) {
                        val newRemaining = fadeOutRemaining - bufferSize
                        if (newRemaining <= 0) {
                            finishedFadeOut.add(freq)
                            updatedFadeOut.remove(freq)
                        } else {
                            updatedFadeOut[freq] = newRemaining
                        }
                    }

                    if (fadeInRemaining > 0) {
                        val newRemaining = fadeInRemaining - bufferSize
                        if (newRemaining <= 0) {
                            finishedFadeIn.add(freq)
                            updatedFadeIn.remove(freq)
                        } else {
                            updatedFadeIn[freq] = newRemaining
                        }
                    }
                }

                for (i in floatMixBuffer.indices) {
                    val limited = tanh(floatMixBuffer[i].toDouble()).toFloat()
                    pcmBuffer[i] = (limited * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }

                synchronized(this) {
                    for ((freq, phase) in updatedPhases) {
                        phaseMap[freq] = phase
                    }
                    for (freq in finishedFadeOut) {
                        fadeOutMap.remove(freq)
                        activeFreqs.remove(freq)
                        phaseMap.remove(freq)
                    }
                    for ((freq, rem) in updatedFadeOut) {
                        if (rem > 0) fadeOutMap[freq] = rem
                    }
                    for (freq in finishedFadeIn) {
                        fadeInMap.remove(freq)
                    }
                    for ((freq, rem) in updatedFadeIn) {
                        if (rem > 0) fadeInMap[freq] = rem
                    }

                    if (finishedFadeOut.isNotEmpty()) {
                        val noteIndexList = currentResumeQueue.toList()
                        for (noteIndex in noteIndexList) {
                            resumeQueue.remove(noteIndex)
                            val newFreq = noteButtonsProvider()[noteIndex]?.tag as? Double ?: continue
                            fadeOutMap.remove(newFreq)
                            phaseMap[newFreq] = 0.0
                            fadeInMap[newFreq] = fadeSamples
                            activeFreqs.add(newFreq)
                            noteIndexToFreq[noteIndex] = newFreq
                            onResumeCallback?.invoke(noteIndex)
                        }
                    }
                }

                audioTrack?.write(pcmBuffer, 0, pcmBuffer.size)
            }
        }.start()
    }

    fun playTone(freq: Double, noteIndex: Int? = null) {
        synchronized(this) {
            if (audioTrack == null) start()
            fadeOutMap.remove(freq)
            phaseMap[freq] = 0.0
            fadeInMap[freq] = fadeSamples
            activeFreqs.add(freq)
            if (noteIndex != null) {
                noteIndexToFreq[noteIndex] = freq
            }
        }
    }

    fun stopTone(noteIndex: Int, resumeAfter: Boolean = false) {
        synchronized(this) {
            val currentFreq = noteIndexToFreq[noteIndex] ?: return
            fadeOutMap[currentFreq] = fadeSamples
            if (resumeAfter) {
                resumeQueue.add(noteIndex)
            }
        }
    }

    fun stopAll() {
        synchronized(this) {
            if (activeFreqs.isEmpty()) return
            for (freq in activeFreqs) {
                fadeOutMap[freq] = fadeSamples
            }
        }
    }

    fun release() {
        isRunning = false
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    fun setOriginalFreqMap(map: Map<Int, Double>) {
        originalFreqMap = map
    }
}
