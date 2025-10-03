package com.example.tuningcomparisondemo

import android.util.Log

class FrequencyCalculator(
    private var basePitchHz: Double = 440.0, // A4
    private var tuningMode: TuningMode = TuningMode.JUST,
    private var instrumentKey: InstrumentKey = InstrumentKey.C,
    private var scaleMode: ScaleMode = ScaleMode.MAJOR
) {
    private var keyOffset: Int = 0

    // 度数ベースの比率（degree: 0..11、トニックを0）
    // メジャー（C基準の度数）：0(1/1), 2(9/8), 4(5/4), 5(4/3), 7(3/2), 9(5/3), 11(15/8)
    private val justDegreeMajor = mapOf(
        0 to 1.0,
        1 to 16.0 / 15.0,
        2 to 9.0 / 8.0,
        3 to 6.0 / 5.0,
        4 to 5.0 / 4.0,
        5 to 4.0 / 3.0,
        6 to 45.0 / 32.0,
        7 to 3.0 / 2.0,
        8 to 8.0 / 5.0,
        9 to 5.0 / 3.0,
        10 to 9.0 / 5.0,
        11 to 15.0 / 8.0
    )

    // マイナー（A自然短音階の度数想定）：0(1/1), 2(9/8), 3(6/5), 5(4/3), 7(3/2), 8(8/5), 10(9/5)
    private val justDegreeMinor = mapOf(
        0 to 1.0,        // 1(A)
        1 to 16.0 / 15.0,// m2
        2 to 9.0 / 8.0,  // M2
        3 to 6.0 / 5.0,  // m3
        4 to 5.0 / 4.0,  // M3（文脈により変動するが暫定）
        5 to 4.0 / 3.0,  // P4
        6 to 45.0 / 32.0,// A4
        7 to 3.0 / 2.0,  // P5
        8 to 8.0 / 5.0,  // m6
        9 to 5.0 / 3.0,  // M6（文脈により変動するが暫定）
        10 to 9.0 / 5.0, // m7
        11 to 15.0 / 8.0 // M7
    )

    private fun degreeRatios(): Map<Int, Double> =
        if (scaleMode == ScaleMode.MAJOR) justDegreeMajor else justDegreeMinor

    fun setKeyOffset(offset: Int) { keyOffset = offset }
    fun getKeyOffset(): Int = keyOffset
    fun setScaleMode(mode: ScaleMode) { scaleMode = mode }
    fun getScaleMode(): ScaleMode = scaleMode
    fun setTuningMode(mode: TuningMode) { tuningMode = mode }
    fun setInstrumentKey(key: InstrumentKey) { instrumentKey = key }
    fun getInstrumentKey(): InstrumentKey = instrumentKey
    fun setBasePitch(hz: Double) { basePitchHz = hz }
    fun getBasePitch(): Double = basePitchHz

    // トニックのクロマ（0=C ... 11=B）
    fun getTonicChromatic(): Int {
        val baseChromatic = (keyOffset + instrumentKey.semitoneShift + 120) % 12
        return when (scaleMode) {
            ScaleMode.MAJOR -> baseChromatic
            ScaleMode.MINOR -> (baseChromatic + 9) % 12 // 長調主音から短3度下げ（-3半音 ≡ +9半音）
        }
    }
    // 12平均の周波数（等分平均は唯一解）
    private fun equalFrequency(noteIndex: Int, octave: Int): Double {
        val totalShift = keyOffset + instrumentKey.semitoneShift
        val transposedIndex = (noteIndex + totalShift + 120) % 12
        val semitoneFromA4 = (octave - 4) * 12 + (transposedIndex - 9)
        return basePitchHz * Math.pow(2.0, semitoneFromA4 / 12.0)
    }

    fun getFrequencyInEqual(noteIndex: Int, octave: Int): Double = equalFrequency(noteIndex, octave)

    // 記譜上の noteIndex を移調後クロマに変換
    fun getChromaticAfterTransposition(noteIndex: Int): Int {
        val totalShift = keyOffset + instrumentKey.semitoneShift
        return (noteIndex + totalShift + 120) % 12
    }

    // 実音クロマ値から等分平均周波数を取得
    fun getFrequencyInEqualByChromatic(chromatic: Int, octave: Int): Double {
        val semitoneFromA4 = (octave - 4) * 12 + (chromatic - 9)
        return basePitchHz * Math.pow(2.0, semitoneFromA4 / 12.0)
    }

    fun getFrequency(noteIndex: Int, octave: Int): Double {
        return when (tuningMode) {
            TuningMode.EQUAL -> equalFrequency(noteIndex, octave)
            TuningMode.JUST -> {
                // まず同じ noteIndex, octave の等分平均周波数を取得（オクターブを固定する基準）
                val equalNote = equalFrequency(noteIndex, octave)

                // トニック（クロマ）と当該音（クロマ）から度数を計算
                val totalShift = keyOffset + instrumentKey.semitoneShift
                val transposedIndex = (noteIndex + totalShift + 120) % 12
                val tonicChromatic = getTonicChromatic()
                val degree = (transposedIndex - tonicChromatic + 12) % 12

                // 純正律比率（度数）と12平均の同度数の比率を比較 → 補正係数で等分平均から微調整
                val ratios = degreeRatios()
                val justRatio = ratios[degree] ?: Math.pow(2.0, degree / 12.0) // 未定義度は等分平均にフォールバック
                val equalRatioForDegree = Math.pow(2.0, degree / 12.0)
                val correction = justRatio / equalRatioForDegree

                equalNote * correction
            }
        }
    }


    /**
     * 記譜上のC（Written C）基準で、楽器の移調を反映した等分平均の実音周波数
     * noteIndex: 記譜Cからの半音数 (0=C, 1=C#, ... 11=B)
     * octave: 記譜オクターブ（C4を基準に上下）
     */
    fun getEqualFrequencyFromWrittenC(noteIndex: Int, octave: Int): Double {
        // 記譜Cからの半音数を「実音クロマ」に移調
        val concertChromatic = (noteIndex + instrumentKey.semitoneShift + 120) % 12
        // A4のクロマは9（A）なので、そこからの半音差で周波数を算出
        val semitoneFromA4 = (octave - 4) * 12 + (concertChromatic - 9)
        return basePitchHz * Math.pow(2.0, semitoneFromA4 / 12.0)
    }

    /**
     * 記譜上のC（Written C）基準で、楽器の移調を反映した実音周波数（純正律／等分平均）
     */
    fun getFrequencyFromWrittenC(noteIndex: Int, octave: Int): Double {
        return when (tuningMode) {
            TuningMode.EQUAL -> getEqualFrequencyFromWrittenC(noteIndex, octave)
            TuningMode.JUST -> {
                // まず等分平均で実音周波数を取得
                val equalNote = getEqualFrequencyFromWrittenC(noteIndex, octave)

                // 純正律補正のための度数を「移調後の実音クロマ」基準で算出
                val concertChromatic = (noteIndex + instrumentKey.semitoneShift + 120) % 12

                // 記譜Cをトニックとみなす（記譜C=0 を移調後の実音に変換）
                val tonicChromaticConcert = (0 + instrumentKey.semitoneShift + 120) % 12

                val degree = (concertChromatic - tonicChromaticConcert + 12) % 12

                // 純正律比率を適用（未定義度は等分平均にフォールバック）
                val ratios = degreeRatios()
                val justRatio = ratios[degree] ?: Math.pow(2.0, degree / 12.0)
                val equalRatioForDegree = Math.pow(2.0, degree / 12.0)
                val correction = justRatio / equalRatioForDegree

                equalNote * correction
            }
        }
    }
    /**
     * 現在の調とスケールモードに基づいて、noteIndex の並びを返す
     * 例: 長調なら主音から全音・全音・半音…の並び、短調なら短三度からの並び
     * 戻り値は noteIndex のリスト（0〜11）
     */
    fun getScaleNoteIndices(): List<Int> {
        val tonicChromatic = getTonicChromatic() // 移調後の主音クロマ値
        return when (getScaleMode()) {
            ScaleMode.MAJOR -> {
                // 長調: 全音, 全音, 半音, 全音, 全音, 全音, 半音
                val intervals = listOf(0, 2, 4, 5, 7, 9, 11)
                intervals.map { (tonicChromatic + it) % 12 }
            }
            ScaleMode.MINOR -> {
                // 短調（自然短音階）: 全音, 半音, 全音, 全音, 半音, 全音, 全音
                val intervals = listOf(0, 2, 3, 5, 7, 8, 10)
                intervals.map { (tonicChromatic + it) % 12 }
            }
        }
    }

    fun getTonicNoteName(): String {
        // 主音のクロマ値（移調後）
        val tonicChromatic = getTonicChromatic()

        // クロマ値 → # 系音名
        val chromaticToSharpName = mapOf(
            0 to "C",
            1 to "C#",
            2 to "D",
            3 to "D#",
            4 to "E",
            5 to "F",
            6 to "F#",
            7 to "G",
            8 to "G#",
            9 to "A",
            10 to "A#",
            11 to "B"
        )

        return chromaticToSharpName[tonicChromatic] ?: "?"
    }
}