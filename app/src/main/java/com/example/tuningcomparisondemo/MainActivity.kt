package com.example.tuningcomparisondemo

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var textResult: TextView
    private lateinit var calculator: FrequencyCalculator
    private lateinit var toneGenerator: ToneGenerator
    private lateinit var switchChordType: Switch
    private lateinit var pianoKeyboardView: PianoKeyboardView

    private val playingNotes = mutableMapOf<Int, Boolean>() // noteIndex (0..11) -> 再生中かどうか
    private val originalFreqMap = mutableMapOf<Int, Double>()

    private var currentOctave = 4
    private val minOctave = 2
    private val maxOctave = 6

    private lateinit var buttonOctaveUp: Button
    private lateinit var buttonOctaveDown: Button

    private lateinit var spinnerRoot: Spinner
    private lateinit var spinnerInst: Spinner
    private lateinit var spinnerPitch: Spinner

    // シャープとフラットの両方を併記した音名を取得（Minorの場合は小文字に変換）
    private fun getNoteDisplayName(index: Int, isMinor: Boolean): String {
        val idx = ((index % 12) + 12) % 12
        val sharp = NoteNames.sharpNames[idx]
        val flat = NoteNames.flatNames[idx]

        val rawName = if (sharp == flat) {
            sharp
        } else {
            "$sharp/$flat"
        }

        return if (isMinor) rawName.lowercase() else rawName
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        calculator = FrequencyCalculator()
        toneGenerator = ToneGenerator(sampleRate = 44100) { emptyMap() }

        toneGenerator.setOnResumeCallback { noteIndex ->
            runOnUiThread {
                playingNotes[noteIndex] = true
                updateNoteButtons()
            }
        }

        pianoKeyboardView = findViewById(R.id.pianoKeyboardView)
        pianoKeyboardView.setOnKeyClickListener { noteIndex ->
            toggleNotePlayback(noteIndex)
        }

        spinnerRoot = findViewById(R.id.spinnerChordRoot)
        spinnerInst = findViewById(R.id.spinnerInstrument)
        spinnerPitch = findViewById(R.id.spinnerBasePitch)
        switchChordType = findViewById(R.id.switchChordType)
        val switchTuning = findViewById<Switch>(R.id.switchTuningMode)
        val switchSeventh = findViewById<Switch>(R.id.switchSeventhMode)
        val buttonStop = findViewById<Button>(R.id.buttonStop)
        textResult = findViewById(R.id.textResult)

        // 1. 根音選択イベント -> 再生停止
        spinnerRoot.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val chordRoot = position
                calculator.setChordRoot(chordRoot)
                stopAllPlayback()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // 2. 楽器選択イベント -> 再生停止
        spinnerInst.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val instKey = when (position) {
                    0 -> InstrumentKey.C
                    1 -> InstrumentKey.Bb
                    2 -> InstrumentKey.Eb
                    3 -> InstrumentKey.F
                    4 -> InstrumentKey.A
                    else -> InstrumentKey.C
                }
                calculator.setInstrumentKey(instKey)
                stopAllPlayback()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // 3. 基準ピッチ選択イベント -> 再生停止
        spinnerPitch.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val pitchText = parent.getItemAtPosition(position).toString()
                val pitchHz = pitchText.replace(" Hz", "").toDoubleOrNull() ?: 442.0
                calculator.setBasePitch(pitchHz)
                stopAllPlayback()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // 初期選択の設定
        spinnerInst.setSelection(1) // B♭管
        spinnerPitch.setSelection(4) // 442Hz

        // 4. 長調 / 短調 切り替えイベント -> 再生停止
        switchChordType.setOnCheckedChangeListener { _, isChecked ->
            calculator.setChordType(if (isChecked) ChordType.MINOR else ChordType.MAJOR)
            stopAllPlayback()
        }

        // 5. 純正律 / 平均律 切り替えイベント -> 再生継続（旧音を停止して新周波数で再生成）
        switchTuning.setOnCheckedChangeListener { _, isChecked ->
            applyModeChangeWithFade {
                calculator.setTuningMode(if (isChecked) TuningMode.EQUAL else TuningMode.JUST)
            }
        }

        // 6. 自然7度 (7/4) / クラシック系短7度 (9/5) 切り替えイベント
        switchSeventh.isChecked = (calculator.getSeventhMode() == SeventhMode.HARMONIC)
        switchSeventh.setOnCheckedChangeListener { _, isChecked ->
            applyModeChangeWithFade {
                calculator.setSeventhMode(if (isChecked) SeventhMode.HARMONIC else SeventhMode.CLASSIC)
            }
        }

        // 停止ボタン
        buttonStop.setOnClickListener {
            stopAllPlayback()
        }

        buttonOctaveUp = findViewById(R.id.buttonOctaveUp)
        buttonOctaveUp.setOnClickListener {
            if (currentOctave < maxOctave) {
                currentOctave++
                stopAllPlayback()
            }
        }

        buttonOctaveDown = findViewById(R.id.buttonOctaveDown)
        buttonOctaveDown.setOnClickListener {
            if (currentOctave > minOctave) {
                currentOctave--
                stopAllPlayback()
            }
        }

        updateNoteButtons()
    }

    /**
     * すべての音の再生を停止し、マップとUIを更新するヘルパー関数
     */
    private fun stopAllPlayback() {
        toneGenerator.stopAll()
        for (key in playingNotes.keys.toList()) {
            playingNotes[key] = false
        }
        updateNoteButtons()
    }

    private fun toggleNotePlayback(noteIndex: Int) {
        val octave = currentOctave
        val freq = calculator.getFrequency(noteIndex, octave)
        val isPlaying = playingNotes[noteIndex] ?: false

        if (isPlaying) {
            toneGenerator.stopTone(noteIndex)
            playingNotes[noteIndex] = false
        } else {
            toneGenerator.playTone(freq, noteIndex)
            playingNotes[noteIndex] = true
        }
        updateNoteButtons()
    }

    // ボタンの表示更新 (12音分) とカスタムViewへのデータ反映
    private fun updateNoteButtons() {
        val octave = currentOctave
        val chordRoot = calculator.getChordRoot()
        val isMinor = (calculator.getChordType() == ChordType.MINOR)

        val noteItems = mutableListOf<NoteItem>()

        for (noteIndex in 0..11) {
            val writtenChromatic = (chordRoot + noteIndex + 120) % 12
            val concertChromatic = calculator.getConcertChromatic(noteIndex)
            val writtenName = getNoteDisplayName(writtenChromatic, isMinor)
            val actualName = getNoteDisplayName(concertChromatic, isMinor)

            val nameLabel = if (writtenName == actualName) writtenName else "$writtenName ($actualName)"

            val freq = calculator.getFrequency(noteIndex, octave)
            val centDiff = calculator.getCentDifference(noteIndex)
            val intervalLabel = getIntervalLabel(noteIndex)

            val isPlaying = playingNotes[noteIndex] ?: false
            val isRoot = (noteIndex == 0)
            val chordType = calculator.getChordType()
            val isThird = (chordType == ChordType.MAJOR && noteIndex == 4) || (chordType == ChordType.MINOR && noteIndex == 3)
            val isFifth = (noteIndex == 7)
            val isChordTone = isRoot || isThird || isFifth

            val isSharpFlat = concertChromatic in listOf(1, 3, 6, 8, 10)

            noteItems.add(
                NoteItem(
                    noteIndex = noteIndex,
                    concertChromatic = concertChromatic,
                    isSharpFlat = isSharpFlat,
                    nameLabel = nameLabel,
                    intervalLabel = intervalLabel,
                    freq = freq,
                    centDiff = centDiff,
                    isPlaying = isPlaying,
                    isRoot = isRoot,
                    isChordTone = isChordTone
                )
            )
        }

        pianoKeyboardView.setNotes(noteItems)
        updateOctaveButtons()
    }

    private fun getIntervalLabel(noteIndex: Int): String {
        return when (noteIndex) {
            0 -> "根音"
            1 -> "短2度"
            2 -> "長2度"
            3 -> "短3度"
            4 -> "長3度"
            5 -> "完全4度"
            6 -> "増4度/減5度"
            7 -> "完全5度"
            8 -> "短6度"
            9 -> "長6度"
            10 -> "短7度"
            11 -> "長7度"
            else -> ""
        }
    }

    private fun updateOctaveButtons() {
        buttonOctaveUp.isEnabled = currentOctave < maxOctave
        buttonOctaveDown.isEnabled = currentOctave > minOctave
    }

    /**
     * 純正律 / 平均律 / 自然7度 切替時の処理：
     * 旧周波数の音を正しく停止してから音律設定を更新し、
     * 新しい周波数で発音を再開することで「うなり」を発生させずに切り替えます。
     */
    private fun applyModeChangeWithFade(applySetting: () -> Unit) {
        val activeNotes = playingNotes.filterValues { it }.keys.toList()

        if (activeNotes.isEmpty()) {
            applySetting()
            updateNoteButtons()
            return
        }

        // 1. 重複音（うなり）を防ぐため、旧音の波形を停止させる
        for (noteIndex in activeNotes) {
            toneGenerator.stopTone(noteIndex)
        }

        // 2. 音律設定の変更（純正律 ⇔ 平均律 ⇔ 自然7度）
        applySetting()

        // 3. 新しい周波数で再発音
        for (noteIndex in activeNotes) {
            val newFreq = calculator.getFrequency(noteIndex, currentOctave)
            toneGenerator.playTone(newFreq, noteIndex)
            playingNotes[noteIndex] = true
        }

        updateNoteButtons()
    }
}
