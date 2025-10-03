package com.example.tuningcomparisondemo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import android.widget.Button
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.max

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
            val mixBuffer = ShortArray(bufferSize)
            var silenceCounter = 0 // サイレンス継続時間カウンタ

            while (isRunning) {
                // 終了条件緩和：0.2秒以上完全サイレンスなら停止
                if (activeFreqs.isEmpty() && fadeOutMap.isEmpty() && fadeInMap.isEmpty() && resumeQueue.isEmpty()) {
                    silenceCounter += bufferSize
                    if (silenceCounter >= sampleRate / 5) { // 0.2秒
                        val silenceBuffer = ShortArray(bufferSize) { 0 }
                        repeat(3) { audioTrack?.write(silenceBuffer, 0, silenceBuffer.size) }
                        release()
                        break
                    }
                } else {
                    silenceCounter = 0
                }

                for (i in mixBuffer.indices) mixBuffer[i] = 0

                for (freq in activeFreqs) {
                    val phaseStart = phaseMap[freq] ?: 0.0
                    val phaseInc = 2 * PI * freq / sampleRate
                    var phase = phaseStart

                    val fadeOutRemaining = fadeOutMap[freq] ?: -1
                    val fadeOutStep = if (fadeOutRemaining > 0) 1.0 / fadeSamples else 0.0
                    var fadeOutFactor = if (fadeOutRemaining > 0) fadeOutRemaining.toDouble() / fadeSamples else 1.0

                    val fadeInRemaining = fadeInMap[freq] ?: -1
                    val fadeInStep = if (fadeInRemaining > 0) 1.0 / fadeSamples else 0.0
                    var fadeInFactor = if (fadeInRemaining > 0) (fadeSamples - fadeInRemaining).toDouble() / fadeSamples else 1.0

                    
                    for (i in mixBuffer.indices) {
                        val factor = fadeOutFactor * fadeInFactor
                        val sample = sin(phase) * Short.MAX_VALUE * 0.3 * factor
                        val mixed = mixBuffer[i] + sample.toInt()
                        mixBuffer[i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        phase += phaseInc

                        if (fadeOutRemaining > 0) fadeOutFactor -= fadeOutStep
                        if (fadeInRemaining > 0) fadeInFactor += fadeInStep
                    }

                    phaseMap[freq] = phase

                    // フェードアウト終了判定
                    if (fadeOutRemaining > 0) {
                        val newRemaining = fadeOutRemaining - bufferSize
                        if (newRemaining <= 0) {
                            fadeOutMap.remove(freq)
                            activeFreqs.remove(freq)
                            phaseMap.remove(freq)

                            val noteIndexList = resumeQueue.toList()
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
                        } else {
                            fadeOutMap[freq] = newRemaining
                        }
                    }

                    // フェードイン終了判定
                    if (fadeInRemaining > 0) {
                        val newRemaining = fadeInRemaining - bufferSize
                        if (newRemaining <= 0) {
                            fadeInMap.remove(freq)
                        } else {
                            fadeInMap[freq] = newRemaining
                        }
                    }
                }

                audioTrack?.write(mixBuffer, 0, mixBuffer.size)
            }
        }.start()
    }

    fun playTone(freq: Double, noteIndex: Int? = null) {
        if (audioTrack == null) start()
        fadeOutMap.remove(freq)
        phaseMap[freq] = 0.0
        fadeInMap[freq] = fadeSamples
        activeFreqs.add(freq)
        if (noteIndex != null) {
            noteIndexToFreq[noteIndex] = freq
        }
    }

    fun stopTone(noteIndex: Int, resumeAfter: Boolean = false) {
        val currentFreq = noteIndexToFreq[noteIndex] ?: return
        fadeOutMap[currentFreq] = fadeSamples
        if (resumeAfter) {
            resumeQueue.add(noteIndex)
        }
    }

    fun stopAll() {
        if (activeFreqs.isEmpty()) return
        for (freq in activeFreqs) {
            fadeOutMap[freq] = fadeSamples
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

    fun getFadeDurationMs(): Int = fadeDurationMs
}