package com.example.tuningcomparisondemo

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var textResult: TextView
    private lateinit var calculator: FrequencyCalculator
    private lateinit var toneGenerator: ToneGenerator
    private lateinit var switchChordType: Switch

    private val playingNotes = mutableMapOf<Int, Boolean>() // noteIndex (0..11) -> 再生中かどうか
    private val originalFreqMap = mutableMapOf<Int, Double>()
    private val noteButtons = mutableMapOf<Int, Button>() // noteIndex (0..11) -> Button

    private var currentOctave = 4
    private val minOctave = 2
    private val maxOctave = 6

    private lateinit var buttonOctaveUp: Button
    private lateinit var buttonOctaveDown: Button

    var uiUpdateEnabled = true

    private fun isPortrait(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }

    // シャープとフラットの両方を併記した音名を取得（Minorの場合は小文字に変換）
    private fun getNoteDisplayName(index: Int, isMinor: Boolean): String {
        val idx = (index % 12 + 12) % 12
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
        toneGenerator = ToneGenerator(sampleRate = 44100) { noteButtons }

        toneGenerator.setOnResumeCallback { noteIndex ->
            runOnUiThread {
                playingNotes[noteIndex] = true
                noteButtons[noteIndex]?.apply {
                    setBackgroundColor(getColor(R.color.note_playing))
                    updateButtonStyling(noteIndex, true)
                }
            }
        }

        val spinnerRoot = findViewById<Spinner>(R.id.spinnerChordRoot)
        val spinnerInst = findViewById<Spinner>(R.id.spinnerInstrument)
        spinnerInst.setSelection(1) // B♭管 はインデックス1
        switchChordType = findViewById<Switch>(R.id.switchChordType)
        val switchTuning = findViewById<Switch>(R.id.switchTuningMode)
        val spinnerPitch = findViewById<Spinner>(R.id.spinnerBasePitch)
        spinnerPitch.setSelection(4) // 442Hz
        val buttonStop = findViewById<Button>(R.id.buttonStop)
        textResult = findViewById(R.id.textResult)

        // コードルート選択イベント
        spinnerRoot.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val chordRoot = position // 0..11
                val basePitch = spinnerPitch.selectedItem.toString().removeSuffix(" Hz").toDouble()
                calculator.setBasePitch(basePitch)
                calculator.setChordType(if (switchChordType.isChecked) ChordType.MINOR else ChordType.MAJOR)
                calculator.setTuningMode(if (switchTuning.isChecked) TuningMode.EQUAL else TuningMode.JUST)
                calculator.setChordRoot(chordRoot)

                updateNoteButtons()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // 楽器選択イベント
        spinnerInst.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val instName = parent.getItemAtPosition(position).toString()
                val instKey = when (instName) {
                    "C管" -> InstrumentKey.C
                    "B♭管" -> InstrumentKey.Bb
                    "E♭管" -> InstrumentKey.Eb
                    "F管" -> InstrumentKey.F
                    "A管" -> InstrumentKey.A
                    else -> InstrumentKey.C
                }
                calculator.setInstrumentKey(instKey)
                updateNoteButtons()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // 基準ピッチ選択イベント
        spinnerPitch.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val pitchText = parent.getItemAtPosition(position).toString()
                val pitchHz = pitchText.replace(" Hz", "").toDoubleOrNull() ?: 440.0
                calculator.setBasePitch(pitchHz)
                updateNoteButtons()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Major / Minor 切り替えイベント
        switchChordType.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                toneGenerator.stopAll()
                delay(50)
                for ((noteIndex, _) in playingNotes) {
                    playingNotes[noteIndex] = false
                }
                calculator.setChordType(if (isChecked) ChordType.MINOR else ChordType.MAJOR)
                updateNoteButtons()
            }
        }

        // 純正律/平均律切り替えイベント
        switchTuning.setOnCheckedChangeListener { _, isChecked ->
            applyModeChangeWithFade {
                calculator.setTuningMode(if (isChecked) TuningMode.EQUAL else TuningMode.JUST)
            }
        }

        // 停止ボタン
        buttonStop.setOnClickListener {
            toneGenerator.stopAll()
            for ((noteIndex, _) in playingNotes) {
                playingNotes[noteIndex] = false
            }
            for ((noteIndex, button) in noteButtons) {
                updateButtonStyling(noteIndex, false)
            }
        }

        buttonOctaveUp = findViewById(R.id.buttonOctaveUp)
        buttonOctaveUp.setOnClickListener {
            if (currentOctave < maxOctave) {
                currentOctave++
                updateNoteButtons(skipStop = false)
                updateOctaveButtons()
            }
        }

        buttonOctaveDown = findViewById(R.id.buttonOctaveDown)
        buttonOctaveDown.setOnClickListener {
            if (currentOctave > minOctave) {
                currentOctave--
                updateNoteButtons(skipStop = false)
                updateOctaveButtons()
            }
        }

        setupNoteButtons()
    }

    private fun toggleNotePlayback(noteIndex: Int) {
        val button = noteButtons[noteIndex] ?: return
        val freq = button.tag as? Double ?: return
        val isPlaying = playingNotes[noteIndex] ?: false

        if (isPlaying) {
            toneGenerator.stopTone(noteIndex)
            playingNotes[noteIndex] = false
            updateButtonStyling(noteIndex, false)
        } else {
            toneGenerator.playTone(freq, noteIndex)
            playingNotes[noteIndex] = true
            updateButtonStyling(noteIndex, true)
        }
    }

    // 12音ボタンの初期生成 (縦：縦並び、横：鍵盤風2段（上段=#♭、下段=ナチュラル）)
    private fun setupNoteButtons(skipStop: Boolean = false) {
        if (isPortrait()) {
            val container = findViewById<LinearLayout>(R.id.noteButtonContainer)
            container?.removeAllViews()
            noteButtons.clear()

            val noteIndices = (0..11).toList().reversed()

            for (noteIndex in noteIndices) {
                val button = Button(this)
                button.setOnClickListener {
                    toggleNotePlayback(noteIndex)
                }

                val layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams.setMargins(0, 2, 0, 2)
                button.layoutParams = layoutParams
                button.textSize = 12f
                button.setPadding(8, 2, 8, 2)
                container?.addView(button)
                noteButtons[noteIndex] = button
            }
        } else {
            val upperContainer = findViewById<LinearLayout>(R.id.upperRowContainer)
            val lowerContainer = findViewById<LinearLayout>(R.id.lowerRowContainer)
            upperContainer?.removeAllViews()
            lowerContainer?.removeAllViews()
            noteButtons.clear()

            // 自然音（白鍵）: C(0), D(2), E(4), F(5), G(7), A(9), B(11)
            val naturalIndices = (0..11).filter { noteIndex ->
                val cc = calculator.getConcertChromatic(noteIndex)
                cc in listOf(0, 2, 4, 5, 7, 9, 11)
            }.sortedBy { calculator.getConcertChromatic(it) }

            // 変化音（黒鍵/#♭）: C#/Db(1), D#/Eb(3), F#/Gb(6), G#/Ab(8), A#/Bb(10)
            val accidentalIndices = (0..11).filter { noteIndex ->
                val cc = calculator.getConcertChromatic(noteIndex)
                cc in listOf(1, 3, 6, 8, 10)
            }.sortedBy { calculator.getConcertChromatic(it) }

            // 上段：# / ♭ (黒鍵)
            for (noteIndex in accidentalIndices) {
                val button = Button(this)
                button.setOnClickListener {
                    toggleNotePlayback(noteIndex)
                }

                val layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f
                )
                layoutParams.setMargins(2, 2, 2, 2)
                button.layoutParams = layoutParams
                button.textSize = 10f
                button.setPadding(2, 2, 2, 2)
                upperContainer?.addView(button)
                noteButtons[noteIndex] = button
            }

            // 下段：ナチュラル (白鍵)
            for (noteIndex in naturalIndices) {
                val button = Button(this)
                button.setOnClickListener {
                    toggleNotePlayback(noteIndex)
                }

                val layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f
                )
                layoutParams.setMargins(2, 2, 2, 2)
                button.layoutParams = layoutParams
                button.textSize = 10f
                button.setPadding(2, 2, 2, 2)
                lowerContainer?.addView(button)
                noteButtons[noteIndex] = button
            }
        }

        updateNoteButtons(skipStop)
    }

    // ボタンの表示更新 (12音分)
    private fun updateNoteButtons(skipStop: Boolean = false) {
        if (!uiUpdateEnabled) {
            for ((noteIndex, isPlaying) in playingNotes) {
                if (isPlaying) {
                    val freq = noteButtons[noteIndex]?.tag as? Double ?: continue
                    toneGenerator.playTone(freq, noteIndex)
                }
            }
            return
        }

        val octave = currentOctave
        val chordRoot = calculator.getChordRoot()
        val isMinor = (calculator.getChordType() == ChordType.MINOR)

        for (noteIndex in 0..11) {
            val button = noteButtons[noteIndex] ?: continue

            val writtenChromatic = (chordRoot + noteIndex + 120) % 12
            val concertChromatic = calculator.getConcertChromatic(noteIndex)
            val writtenName = getNoteDisplayName(writtenChromatic, isMinor)
            val actualName = getNoteDisplayName(concertChromatic, isMinor)

            val nameLabel = if (writtenName == actualName) writtenName else "$writtenName ($actualName)"

            val freq = calculator.getFrequency(noteIndex, octave)
            val centDiff = calculator.getCentDifference(noteIndex)

            val intervalLabel = getIntervalLabel(noteIndex)
            val prefix = if (noteIndex == 0) "★基準 " else ""

            // 横向きの場合はスペースの関係上、テキストをコンパクトに調整
            val buttonText = if (isPortrait()) {
                "$prefix$nameLabel [$intervalLabel] | ${"%.2f".format(freq)} Hz | ${"%.1f".format(centDiff)} cent"
            } else {
                "$prefix$nameLabel\n[$intervalLabel]\n${"%.1f".format(centDiff)}c"
            }

            button.text = buttonText
            button.tag = freq

            val isPlaying = playingNotes[noteIndex] ?: false
            updateButtonStyling(noteIndex, isPlaying)
        }

        if (!skipStop) {
            toneGenerator.stopAll()
        }

        for ((noteIndex, isPlaying) in playingNotes) {
            if (isPlaying) {
                val button = noteButtons[noteIndex] ?: continue
                val freq = button.tag as? Double ?: continue
                toneGenerator.playTone(freq, noteIndex)
            }
        }

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

    // ボタンの背景色・文字色のスタイリング更新
    private fun updateButtonStyling(noteIndex: Int, isPlaying: Boolean) {
        val button = noteButtons[noteIndex] ?: return
        val chordType = calculator.getChordType()

        val isRoot = (noteIndex == 0)
        val isThird = (chordType == ChordType.MAJOR && noteIndex == 4) || (chordType == ChordType.MINOR && noteIndex == 3)
        val isFifth = (noteIndex == 7)
        val isChordTone = isRoot || isThird || isFifth

        val bgColor = when {
            isPlaying -> getColor(R.color.note_playing)
            isRoot -> Color.parseColor("#3E2723")
            isChordTone -> Color.parseColor("#263238")
            else -> getColor(R.color.note_default)
        }
        button.setBackgroundColor(bgColor)

        val textColor = when {
            isPlaying -> Color.parseColor("#1A237E")
            isRoot -> Color.parseColor("#FFD700")
            isChordTone -> Color.parseColor("#80DEEA")
            else -> Color.parseColor("#F0F0F0")
        }
        button.setTextColor(textColor)
    }

    private fun updateOctaveButtons() {
        buttonOctaveUp.isEnabled = currentOctave < maxOctave
        buttonOctaveDown.isEnabled = currentOctave > minOctave
    }

    private fun applyModeChangeWithFade(applySetting: () -> Unit) {
        val activeNotes = playingNotes.filterValues { it }.keys.toList()

        originalFreqMap.clear()
        for (noteIndex in activeNotes) {
            val freq = noteButtons[noteIndex]?.tag as? Double ?: continue
            originalFreqMap[noteIndex] = freq
        }
        toneGenerator.setOriginalFreqMap(originalFreqMap)

        applySetting()

        for (noteIndex in activeNotes) {
            toneGenerator.stopTone(noteIndex, resumeAfter = true)
            playingNotes[noteIndex] = false
        }

        updateNoteButtons(skipStop = true)
    }
}
