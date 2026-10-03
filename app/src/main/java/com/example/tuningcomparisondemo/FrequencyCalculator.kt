package com.example.tuningcomparisondemo

enum class ChordType { MAJOR, MINOR }

class FrequencyCalculator(
    private var basePitchHz: Double = 440.0, // A4
    private var tuningMode: TuningMode = TuningMode.JUST,
    private var seventhMode: SeventhMode = SeventhMode.HARMONIC, // デフォルトを自然7度に設定（お好みで変更可）
    private var instrumentKey: InstrumentKey = InstrumentKey.C,
    private var chordRoot: Int = 0, // 0 = C, 1 = C#, ... 11 = B
    private var chordType: ChordType = ChordType.MAJOR
) {

    // 7度モードの設定／取得
    fun setSeventhMode(mode: SeventhMode) { seventhMode = mode }
    fun getSeventhMode(): SeventhMode = seventhMode

    // 7度の比率を取得するヘルパー関数
    private fun getSeventhRatio(): Double {
        return if (seventhMode == SeventhMode.HARMONIC) {
            7.0 / 4.0 // 自然7度
        } else {
            9.0 / 5.0 // クラシック系短7度
        }
    }

    // 動的に比率マップを取得するように変更
    private fun getRatios(): Map<Int, Double> {
        val seventhRatio = getSeventhRatio()

        return if (chordType == ChordType.MAJOR) {
            mapOf(
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
                10 to seventhRatio, // ★切り替え対応
                11 to 15.0 / 8.0
            )
        } else {
            mapOf(
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
                10 to seventhRatio, // ★切り替え対応
                11 to 15.0 / 8.0
            )
        }
    }

    fun setChordRoot(root: Int) { chordRoot = (root % 12 + 12) % 12 }
    fun getChordRoot(): Int = chordRoot

    fun setChordType(type: ChordType) { chordType = type }
    fun getChordType(): ChordType = chordType

    fun setTuningMode(mode: TuningMode) { tuningMode = mode }
    fun getTuningMode(): TuningMode = tuningMode

    fun setInstrumentKey(key: InstrumentKey) { instrumentKey = key }
    fun getInstrumentKey(): InstrumentKey = instrumentKey

    fun setBasePitch(hz: Double) { basePitchHz = hz }
    fun getBasePitch(): Double = basePitchHz

    // 移調を考慮した実際のコンサートピッチ（実音）でのコードルートのクロマ値 (0..11)
    fun getConcertChordRoot(): Int {
        return (chordRoot + instrumentKey.semitoneShift + 120) % 12
    }

    // 指定した半音オフセット（0〜11）およびオクターブでの平均律周波数（実音）
    fun getEqualFrequency(noteIndex: Int, octave: Int): Double {
        val concertRoot = getConcertChordRoot()
        val totalChromatic = (concertRoot + noteIndex) % 12
        val octaveShift = (concertRoot + noteIndex) / 12
        val effectiveOctave = octave + octaveShift
        val semitoneFromA4 = (effectiveOctave - 4) * 12 + (totalChromatic - 9)
        return basePitchHz * Math.pow(2.0, semitoneFromA4 / 12.0)
    }

    // 指定した半音オフセット（0〜11）およびオクターブでの周波数（純正律または平均律）
    fun getFrequency(noteIndex: Int, octave: Int): Double {
        val equalFreq = getEqualFrequency(noteIndex, octave)
        if (tuningMode == TuningMode.EQUAL) {
            return equalFreq
        }
        val ratios = getRatios()
        val justRatio = ratios[noteIndex] ?: Math.pow(2.0, noteIndex / 12.0)
        val equalRatio = Math.pow(2.0, noteIndex / 12.0)
        return equalFreq * (justRatio / equalRatio)
    }

    // 平均律とのcent差を取得
    fun getCentDifference(noteIndex: Int): Double {
        if (tuningMode == TuningMode.EQUAL) return 0.0
        val ratios = getRatios()
        val justRatio = ratios[noteIndex] ?: Math.pow(2.0, noteIndex / 12.0)
        val equalRatio = Math.pow(2.0, noteIndex / 12.0)
        return 1200 * Math.log(justRatio / equalRatio) / Math.log(2.0)
    }

    // コードルートからの半音オフセット（0〜11）に対応する実音クロマ値を取得
    fun getConcertChromatic(noteIndex: Int): Int {
        val concertRoot = getConcertChordRoot()
        return (concertRoot + noteIndex) % 12
    }
}
