package com.example.tuningcomparisondemo

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
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
        switchChordType = findViewById(R.id.switchChordType)
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

                setupNoteButtons(skipStop = true)
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
                setupNoteButtons(skipStop = true)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // 基準ピッチ選択イベント
        spinnerPitch.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val pitchText = parent.getItemAtPosition(position).toString()
                val pitchHz = pitchText.replace(" Hz", "").toDoubleOrNull() ?: 440.0
                calculator.setBasePitch(pitchHz)
                setupNoteButtons(skipStop = true)
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
                setupNoteButtons(skipStop = true)
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
            for ((noteIndex, _) in noteButtons) {
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

    // 12音ボタンの初期生成 (縦：12行の順次配置[Rootが下、11が上]、ボタン56dp、空きスペースは半高28dp / 横：変更なし)
    private fun setupNoteButtons(skipStop: Boolean = false) {
        if (isPortrait()) {
            val container = findViewById<LinearLayout>(R.id.noteButtonContainer)
            container?.removeAllViews()
            noteButtons.clear()

            val fullHeightPx = (56 * resources.displayMetrics.density).toInt()
            val halfHeightPx = (28 * resources.displayMetrics.density).toInt()

            // 根音(0)が一番下、上に向かって11へ (11 downTo 0)
            for (noteIndex in 11 downTo 0) {
                val concertChromatic = calculator.getConcertChromatic(noteIndex)
                // 実音が # / ♭ (1, 3, 6, 8, 10) の場合は左側（黒鍵位置）、それ以外は右側（白鍵位置）
                val isSharpFlat = concertChromatic in listOf(1, 3, 6, 8, 10)

                val rowLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 2, 0, 2)
                    }
                    gravity = Gravity.CENTER_VERTICAL
                }

                val leftSlot = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val rightSlot = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                if (isSharpFlat) {
                    val button = Button(this).apply {
                        setOnClickListener { toggleNotePlayback(noteIndex) }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            fullHeightPx
                        )
                        textSize = 10f
                        setPadding(2, 2, 2, 2)
                    }
                    leftSlot.addView(button)
                    noteButtons[noteIndex] = button

                    val emptyView = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            halfHeightPx
                        )
                    }
                    rightSlot.addView(emptyView)
                } else {
                    val emptyView = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            halfHeightPx
                        )
                    }
                    leftSlot.addView(emptyView)

                    val button = Button(this).apply {
                        setOnClickListener { toggleNotePlayback(noteIndex) }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            fullHeightPx
                        )
                        textSize = 10f
                        setPadding(2, 2, 2, 2)
                    }
                    rightSlot.addView(button)
                    noteButtons[noteIndex] = button
                }

                rowLayout.addView(leftSlot)
                rowLayout.addView(rightSlot)
                container?.addView(rowLayout)
            }
        } else {
            val upperContainer = findViewById<LinearLayout>(R.id.upperRowContainer)
            val lowerContainer = findViewById<LinearLayout>(R.id.lowerRowContainer)
            upperContainer?.removeAllViews()
            lowerContainer?.removeAllViews()
            noteButtons.clear()

            // 根音(0)が一番左、右に向かって11へ (0..11)
            for (noteIndex in 0..11) {
                val concertChromatic = calculator.getConcertChromatic(noteIndex)
                val isBlackKey = concertChromatic in listOf(1, 3, 6, 8, 10)

                val upperWeight = if (isBlackKey) 1f else 0.5f
                val lowerWeight = if (isBlackKey) 0.5f else 1f

                val upperSlot = LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, upperWeight).apply {
                        setMargins(1, 0, 1, 0)
                    }
                    gravity = Gravity.CENTER
                }
                val lowerSlot = LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, lowerWeight).apply {
                        setMargins(1, 0, 1, 0)
                    }
                    gravity = Gravity.CENTER
                }

                if (isBlackKey) {
                    val button = Button(this).apply {
                        setOnClickListener { toggleNotePlayback(noteIndex) }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                        textSize = 9f
                        setPadding(1, 1, 1, 1)
                    }
                    upperSlot.addView(button)
                    noteButtons[noteIndex] = button
                }

                if (!isBlackKey) {
                    val button = Button(this).apply {
                        setOnClickListener { toggleNotePlayback(noteIndex) }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                        textSize = 9f
                        setPadding(1, 1, 1, 1)
                    }
                    lowerSlot.addView(button)
                    noteButtons[noteIndex] = button
                }

                upperContainer?.addView(upperSlot)
                lowerContainer?.addView(lowerSlot)
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

            val buttonText = if (isPortrait()) {
                "$prefix$nameLabel [$intervalLabel]\n${"%.2f".format(freq)} Hz | ${"%.1f".format(centDiff)} cent"
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
