package com.example.tuningcomparisondemo

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.collections.listOf


class MainActivity : AppCompatActivity() {

    // UI表示用テキストビュー（結果表示）
    private lateinit var textResult: TextView

    // デバッグ用テキストビュー（現在未使用）

    private lateinit var textDebug: TextView

    // 周波数計算ロジックを担当するクラス
    private lateinit var calculator: FrequencyCalculator

    // 音声生成・再生を担当するクラス
    private lateinit var toneGenerator: ToneGenerator

    // 長調/短調切り替えスイッチ（UIコンポーネント）
    // クラスのプロパティとして宣言
    private lateinit var switchScale: Switch

    // noteIndex → 再生中かどうかの状態管理
    private val playingNotes = mutableMapOf<Int, Boolean>() // noteIndex → 再生中かどうか

    // モード切替前の周波数キャッシュ（フェード復帰用）
    private val originalFreqMap = mutableMapOf<Int, Double>()

    // 現在選択されているキーのインデックス（Spinnerの選択位置）
    private var selectedKeyIndex: Int = 0

    // noteIndex → Button のマッピング（UIボタン管理）
    private val noteButtons = mutableMapOf<Int, Button>() // noteIndex → Button

    // 現在のオクターブ
    private var currentOctave = 4

    // オクターブの最小値
    private val minOctave = 2

    // オクターブの最大値
    private val maxOctave = 6

    // オクターブ上下ボタン
    private lateinit var buttonOctaveUp: Button
    private lateinit var buttonOctaveDown: Button

    // ボタン配置モード（記譜C基準か主音基準か）
    private var currentButtonLayoutMode: ButtonLayoutMode = ButtonLayoutMode.INSTRUMENT_C_BOTTOM

    //true でUI更新停止
    var uiUpdateEnabled = true

    // trueでUI更新を有効、falseで停止（デバッグ用）
    private var isUpdatingKeyDisplay = false


    // 調性判定関数もここに
    private fun isFlatKey(keyOffset: Int): Boolean {

        return keyOffset >= 8
    }

    //キーオフセットから画面に表示するボタンのシフト量を導くテーブル
    private val keyOffsetToButtonOffset = mapOf(
        0 to 0, //#0
        1 to 1, //#7, ♭5
        2 to 1, //#2
        3 to 2, //♭3
        4 to 2, //#4
        5 to 3, //♭1
        6 to 3, //#6, ♭6
        7 to 4, //#1
        8 to 5, //♭4
        9 to 5, //#3, ♭3
        10 to 6,    //♭2
        11 to 6     //#5, ♭7
    )


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 周波数計算クラス初期化
        calculator = FrequencyCalculator()

        // 音声生成クラス初期化（noteButtonsを提供するラムダを渡す）
        toneGenerator = ToneGenerator(sampleRate = 44100) { noteButtons }

        // 再生復帰時のUI更新コールバック設定
        toneGenerator.setOnResumeCallback { noteIndex ->
            runOnUiThread {
                Log.d("ToneGen", "setOnResumeCallback noteIndex=$noteIndex")
                playingNotes[noteIndex] = true
                noteButtons[noteIndex]?.apply {
                    setBackgroundColor(getColor(R.color.note_playing))
                    setNoteTextColor(noteIndex, true)
                }
            }
        }

        // 各UIコンポーネントの取得と初期設定
        val spinnerKey = findViewById<Spinner>(R.id.spinnerKeySignature)
        val spinnerInst = findViewById<Spinner>(R.id.spinnerInstrument)
        spinnerInst.setSelection(1) // B♭管 は 2番目（インデックス1）
        switchScale = findViewById<Switch>(R.id.switchScaleMode)
        val switchTuning = findViewById<Switch>(R.id.switchTuningMode)
        val switchAlignment = findViewById<Switch>(R.id.switchAlignmentMode)
        val spinnerPitch = findViewById<Spinner>(R.id.spinnerBasePitch)
        spinnerPitch.setSelection(4) // 442Hz は 5番目（インデックス4）
        val buttonStop = findViewById<Button>(R.id.buttonStop)
        textResult = findViewById(R.id.textResult)
        //textDebug = findViewById(R.id.textDebug)
        val spinnerClef = findViewById<Spinner>(R.id.spinnerClef)

        // 調選択イベント（キー変更時の処理）
        spinnerKey.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val keyName = parent.getItemAtPosition(position).toString()
                val keyOffset = getKeySignatureOffset(keyName)
                val basePitch = spinnerPitch.selectedItem.toString().removeSuffix(" Hz").toDouble()
                selectedKeyIndex = spinnerKey.selectedItemPosition
                calculator.setBasePitch(basePitch)
                calculator.setScaleMode(if (switchScale.isChecked) ScaleMode.MINOR else ScaleMode.MAJOR)
                calculator.setTuningMode(if (switchTuning.isChecked) TuningMode.EQUAL else TuningMode.JUST)
                calculator.setKeyOffset(keyOffset)
                Log.d("DebugNoteButtons", "Call updateNoteButtons() from spinnerKey.onItemSelected")

                if(isPortrait()) {
                    updateNoteButtons()
                }else{
                    updateNoteViews()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // 楽器選択イベント（楽器変更時の処理）
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
                Log.d("DebugNoteButtons", "Call updateNoteButtons() from spinnerInst.onItemSelected")

                // 楽器設定処理
                updateKeyDisplayStaticList()

                if(isPortrait()) {
                    updateNoteButtons()
                }else{
                    updateNoteViews()
                }

            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // 基準ピッチ選択イベント
        spinnerPitch.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val pitchText = parent.getItemAtPosition(position).toString()
                val pitchHz = pitchText.replace(" Hz", "").toDoubleOrNull() ?: 440.0
                calculator.setBasePitch(pitchHz)
                Log.d("DebugNoteButtons", "Call updateNoteButtons() from spinnerPitch.onItemSelected")
                if(isPortrait()) {
                    updateNoteButtons()
                }else{
                    updateNoteViews()
                }

            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // 長調/短調切り替えイベント
        switchScale.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                toneGenerator.stopAll()
                delay(50)
                // 再生状態をすべて false に
                for ((noteIndex, _) in playingNotes) {
                    playingNotes[noteIndex] = false
                }
                calculator.setScaleMode(if (isChecked) ScaleMode.MINOR else ScaleMode.MAJOR)

                // 楽器設定処理
                updateKeyDisplayStaticList()

                if(isPortrait()) {
                    setupNoteButtons()
                }else{
                    updateNoteViews()
                }
            }
        }

        // 純正律/平均律切り替えイベント
        switchTuning.setOnCheckedChangeListener { _, isChecked ->
            applyModeChangeWithFade("Tuning") {
                calculator.setTuningMode(if (isChecked) TuningMode.EQUAL else TuningMode.JUST)
            }
        }

        // ボタン配置モード切り替えイベント
        switchAlignment.setOnCheckedChangeListener { _, isChecked ->
            applyModeChangeWithFade("Alignment") {
                Log.d("DebugNoteButtons", "記譜/主音=$isChecked")
                // Alignmentモード切替（UI側で管理）
                currentButtonLayoutMode = if (isChecked) {
                    ButtonLayoutMode.KEY_ROOT_BOTTOM
                } else {
                    ButtonLayoutMode.INSTRUMENT_C_BOTTOM
                }
            }
        }

        //再生停止ボタン
        buttonStop.setOnClickListener {
            // 主音クロマ値(実音Cを基準として半音でどの程度離れているかを示す値（0の場合は同じ音）)を取得
            val tonicChromatic = calculator.getTonicChromatic()

            // 再生停止
            toneGenerator.stopAll()

            // 再生状態をすべて false に
            for ((noteIndex, _) in playingNotes) {
                playingNotes[noteIndex] = false
            }

            // ボタンの色と文字色をリセット
            for ((noteIndex, button) in noteButtons) {
                button.setBackgroundColor(getColor(R.color.note_default))
                // 文字色設定はsetNoteTextColor()に集約
                setNoteTextColor(noteIndex, isPlaying = false)
            }
        }

        //オクターブ上げボタン
        buttonOctaveUp = findViewById(R.id.buttonOctaveUp)
        buttonOctaveUp.setOnClickListener {
            if (currentOctave < maxOctave) {
                currentOctave++
                Log.d("DebugNoteButtons", "Call updateNoteButtons() from buttonOctaveUp")
                if(isPortrait()) {
                    updateNoteButtons(skipStop = false, skipRearrange = true)
                }else {
                    updateNoteViews()
                }
                updateOctaveButtons()
            }
        }

        //オクターブ下げボタン
        buttonOctaveDown = findViewById(R.id.buttonOctaveDown)
        buttonOctaveDown.setOnClickListener {
            if (currentOctave > minOctave) {
                currentOctave--
                Log.d("DebugNoteButtons", "Call updateNoteButtons() from buttonOctaveDown")
                if(isPortrait()) {
                    updateNoteButtons(skipStop = false, skipRearrange = true)
                }else {
                    updateNoteViews()
                }
                updateOctaveButtons()
            }
        }

        // 例えばデバッグ用にボタンやメニューから切り替え
        val buttonDebugToggleUiUpdates = findViewById<Button>(R.id.debugToggleUiUpdatesButton)
        buttonDebugToggleUiUpdates.setOnClickListener {
            uiUpdateEnabled = !uiUpdateEnabled
            Log.d("DebugUI", "uiUpdateEnabled=$uiUpdateEnabled")
            Log.d("DebugNoteButtons", "Call updateNoteButtons() from buttonDebugToggleUiUpdates")

            if(isPortrait()) {
                updateNoteButtons()
            }else {
                updateNoteViews()
            }
        }
        if(isPortrait()) {
            setupNoteButtons()
        }else{
            updateNoteViews()
        }
    }

    //画面の向き取得
    private fun isPortrait(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }

    // キーシグネチャのオフセット(長調における主音からの半音数)を取得
    private fun getKeySignatureOffset(key: String): Int {
        return when {
            key.startsWith("♯0") -> 0
            key.startsWith("♯1") -> 7
            key.startsWith("♯2") -> 2
            key.startsWith("♯3") -> 9
            key.startsWith("♯4") -> 4
            key.startsWith("♯5") -> 11
            key.startsWith("♯6") -> 6
            key.startsWith("♯7") -> 1
            key.startsWith("♭1") -> 5
            key.startsWith("♭2") -> 10
            key.startsWith("♭3") -> 3
            key.startsWith("♭4") -> 8
            key.startsWith("♭5") -> 1
            key.startsWith("♭6") -> 6
            key.startsWith("♭7") -> 11
            else -> 0
        }
    }
    // 音名取得
    private fun getNoteName(index: Int): String {
        return if (isFlatKey(selectedKeyIndex)) NoteNames.flatNames[index] else NoteNames.sharpNames[index]
    }

    // 記譜音名と実音名のラベル生成
    private fun getNoteLabel(noteIndex: Int, octave: Int): String {

        val writtenShift = calculator.getKeyOffset()
        val totalShift = calculator.getKeyOffset() + calculator.getInstrumentKey().semitoneShift
        Log.d("DebugNoteButtons", "writtenShift=$writtenShift, semitoneShift=${calculator.getInstrumentKey().semitoneShift},totalShift=$totalShift")

        val writtenIndex = (noteIndex + writtenShift + 12) % 12
        val writtenOctave = octave + (noteIndex + writtenShift) / 12
        val writtenName = "${getNoteName(writtenIndex)}"

        val actualIndex = (noteIndex + totalShift + 12) % 12
        val actualOctave = octave + (noteIndex + totalShift) / 12
        val actualName = "${getNoteName(actualIndex)}"

        Log.d("getNoteLabel", "retuen=$writtenName($actualName)")
        return "$writtenName($actualName)"
    }

    /**
     * 記譜C基準のラベル取得
     * 例: C（C）, D（D）, E（E）...
     */
    fun getNoteLabelFromC(noteIndex: Int, octave: Int): String {
        // 記譜Cからの半音数をそのまま音名に変換
        val writtenIndex = (noteIndex + 12) % 12
        val writtenName = getNoteName(writtenIndex) // またはflatNames、キー設定に応じて
        val actualIndex = (noteIndex + calculator.getInstrumentKey().semitoneShift + 12) % 12
        val actualName = getNoteName(actualIndex)
        return "$writtenName（$actualName）"
    }

    // 純正律との差計算
    private fun getCentDifference(currentFreq: Double, justFreq: Double): Double {
        return 1200 * Math.log(currentFreq / justFreq) / Math.log(2.0)
    }

    private fun updateDisplay() {

    }

    // 再生/停止トグル
    private fun toggleNotePlayback(noteIndex: Int, octave: Int) {
        val button = noteButtons[noteIndex] ?: return
        val freq = button.tag as? Double ?: return
        val isPlaying = playingNotes[noteIndex] ?: false

        if (isPlaying) {
            toneGenerator.stopTone(noteIndex)
            playingNotes[noteIndex] = false
            button.setBackgroundColor(getColor(R.color.note_default))
            // 文字色設定はsetNoteTextColor()に集約
            setNoteTextColor(noteIndex, false)

        } else {
            toneGenerator.playTone(freq, noteIndex)
            playingNotes[noteIndex] = true
            button.setBackgroundColor(getColor(R.color.note_playing))
            // 文字色設定はsetNoteTextColor()に集約
            setNoteTextColor(noteIndex, true)
        }
    }

    // ボタン初期化
    private fun setupNoteButtons(skipStop: Boolean = false) {
        val container = findViewById<LinearLayout>(R.id.noteButtonContainer)
        // 既存のボタンをクリア
        container.removeAllViews()
        noteButtons.clear()

        // モードに応じた並び順（下から並べるため reversed()）
        val noteIndices: List<Int>

        noteIndices = listOf(0, 2, 4, 5, 7, 9, 11).reversed() // C D E F G A B

        for (noteIndex in noteIndices) {
            val button = Button(this)
            button.setOnClickListener {
                toggleNotePlayback(noteIndex, currentOctave)
            }

            val layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams.setMargins(0, 8, 0, 8)
            button.layoutParams = layoutParams

            button.setBackgroundColor(getColor(R.color.note_default))
            container.addView(button)
            noteButtons[noteIndex] = button
        }
        Log.d("DebugNoteButtons", "Call updateNoteButtons() from setupNoteButtons")
        updateNoteButtons(skipStop)
    }

    // ボタン更新
    private fun updateNoteButtons(skipStop: Boolean = false, skipRearrange: Boolean = false) {
        //***
        //クリックノイズ原因特定用
        Log.d("DebugUI", "updateNoteButtons start")
        if (!uiUpdateEnabled) {
            Log.d("DebugUI", "Skip UI update")
            // UI更新をスキップして音だけ再生
            for ((noteIndex, isPlaying) in playingNotes) {
                if (isPlaying) {
                    val freq = noteButtons[noteIndex]?.tag as? Double ?: continue
                    toneGenerator.playTone(freq, noteIndex)
                }
            }
            return
        }

        // --- 以下は通常のUI更新処理 ---
        val octave = currentOctave

        // 主音の実音クロマ値（移調後）を取得
        val tonicChromatic = calculator.getTonicChromatic()

        // 主音の等分平均周波数（移調後クロマで計算）
        val baseEqualFreq = calculator.getFrequencyInEqualByChromatic(tonicChromatic, octave)

        var tonicIndex : Int = 0

        for ((noteIndex, button) in noteButtons) {
            val label: String
            var freq: Double
            var equalFreq: Double
            val centDiff: Double

            // 記譜上の noteIndex を移調後クロマに変換
            val transposedChromatic = calculator.getChromaticAfterTransposition(noteIndex)

            //主音のnoteIndexを記憶
            if(tonicChromatic == transposedChromatic){
                tonicIndex = noteIndex
                Log.d("DebugNoteButtons", "tonicIndex=$tonicIndex")
            }

            // 純正律と平均律の周波数を取得（同じオクターブで計算）
            freq = calculator.getFrequency(noteIndex, octave)
            equalFreq = calculator.getFrequencyInEqual(noteIndex, octave)

            // 主音より低い実音は必ず1オクターブ上げる
            if (transposedChromatic != tonicChromatic && equalFreq < baseEqualFreq) {
                freq *= 2.0
                equalFreq *= 2.0
            }
            label = getNoteLabel(noteIndex, octave)
            Log.d("getNoteLabel", "noteIndex=$noteIndex, label=$label")
            Log.d("DebugNoteButtons", "noteIndex=$noteIndex, label=$label")

            centDiff = getCentDifference(freq, equalFreq)

            button.text = "$label \n${"%.2f".format(freq)} Hz\n${"%.1f".format(centDiff)} cent"
            Log.d("getNoteLabel","button.text=${button.text}")
            button.tag = freq

            Log.d("NoteDebug", "noteIndex=$noteIndex, label=$label, freq=$freq, centDiff=$centDiff")

            val isPlaying = playingNotes[noteIndex] ?: false
            // 文字色設定はsetNoteTextColor()に集約
            setNoteTextColor(noteIndex, isPlaying)

        }

        if(skipRearrange == false) {
            //どれだけずらせばいいか確認するための変数
            val scaleMode = calculator.getScaleMode()
            var buttonOffset = 0
            var tmpIndexList: List<Int>

            // 現状の並びを確認
            Log.d("DebugNoteButtons", "===========================================")

            // ボタンへの設定が終わったところで、INSTRUMENT_C_BOTTOM　の場合はここでボタンの順序を並び変える。
            if (currentButtonLayoutMode == ButtonLayoutMode.INSTRUMENT_C_BOTTOM) {
                Log.d("DebugNoteButtons", "===INSTRUMENT_C_BOTTOM===")
                val baseListOriginal = listOf(0, 2, 4, 5, 7, 9, 11) // C D E F G A B

                Log.d("DebugNoteButtons", "baseListOriginal=$baseListOriginal")

                // ボタンラベルから記譜部分を抽出してターゲットを探す
                val targetWritten = "C"
                val targetNoteIndex = baseListOriginal.firstOrNull { idx ->
                    val label = noteButtons[idx]?.text?.toString() ?: ""
                    label.startsWith(targetWritten)
                }

                if (targetNoteIndex != null) {
                    buttonOffset = baseListOriginal.indexOf(targetNoteIndex)
                    Log.d(
                        "DebugNoteButtons",
                        "targetWritten=$targetWritten targetNoteIndex=$targetNoteIndex buttonOffset=$buttonOffset"
                    )
                } else {
                    Log.w("DebugNoteButtons", "targetNoteIndex == null")
                }

                //baseListForDisplayにしたがってボタンを並べ替え
                val shiftedList = shiftLeft(baseListOriginal, buttonOffset ?: 0)
                Log.d("DebugNoteButtons", "shiftedList     =$shiftedList")
                val baseListForDisplay = shiftedList.toList().reversed()
                Log.d("DebugNoteButtons", "baseListReversed=$baseListForDisplay")
                //並べ替え実行
                reorderNoteButtons(baseListForDisplay)

            } else {
                Log.d("DebugNoteButtons", "===KEY_ROOT_BOTTOM===")
                if (scaleMode == ScaleMode.MINOR) {
                    Log.d("DebugNoteButtons", "===MINOR===")
                    buttonOffset = 2
                }
                Log.d("DebugNoteButtons", "buttonOffset=$buttonOffset")
                tmpIndexList = listOf(0, 2, 4, 5, 7, 9, 11).reversed()
                val shiftedList = shiftLeft(tmpIndexList, buttonOffset ?: 0)
                //並べ替え実行
                reorderNoteButtons(shiftedList)
            }
        }

        // 再生中の音を再生し直す
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

    //ボタンの並び順をシフトする関数
    fun <T> shiftLeft(list: List<T>, shift: Int): List<T> {
        if (list.isEmpty()) return list
        val actualShift = ((shift % list.size) + list.size) % list.size // 負数やサイズ超え対応
        return list.drop(actualShift) + list.take(actualShift)
    }

    //ボタンの並べ替えを行う関数
    private fun reorderNoteButtons(newOrder: List<Int>) {
        // newOrder: 並び替えたい noteIndex の順序
        val container = findViewById<LinearLayout>(R.id.noteButtonContainer)

        // 新しい順序で noteButtons を再構築
        val reordered = LinkedHashMap<Int, Button>()
        for (idx in newOrder) {
            noteButtons[idx]?.let { btn ->
                reordered[idx] = btn
            }
        }

        // noteButtons を入れ替え
        noteButtons.clear()
        noteButtons.putAll(reordered)

        // UI の並びも入れ替え
        container.removeAllViews()
        for ((_, btn) in noteButtons) {
            container.addView(btn)
        }
    }

    // オクターブボタン有効/無効更新
    private fun updateOctaveButtons() {
        buttonOctaveUp.isEnabled = currentOctave < maxOctave
        buttonOctaveDown.isEnabled = currentOctave > minOctave
    }

    // ボタン文字色設定
    private fun setNoteTextColor(noteIndex: Int, isPlaying: Boolean) {
        val button = noteButtons[noteIndex] ?: return
        val tonicChromatic = calculator.getTonicChromatic()
        val transposedChromatic = calculator.getChromaticAfterTransposition(noteIndex)
        Log.d("DebugNoteButtons", "noteIndex=$noteIndex, tonicChromatic=$tonicChromatic, transposedChromatic=$transposedChromatic")
        val color = when {
            isPlaying -> Color.parseColor("#1A237E") // 再生中：ダークネイビー
            transposedChromatic == tonicChromatic -> Color.parseColor("#FFD700") // 主音：ゴールド
            else -> Color.parseColor("#F0F0F0") // 通常：明るいグレー
        }

        button.setTextColor(color)
    }

    /**
     * 再生中の音をフェードアウトして新設定で復帰する共通処理
     *
     * @param modeName ログ用のモード名（"Tuning" や "Alignment"）
     * @param applySetting 設定変更処理（例: { calculator.setTuningMode(...) }）
     */
    private fun applyModeChangeWithFade(modeName: String, applySetting: () -> Unit) {
        val activeNotes = playingNotes.filterValues { it }.keys.toList()

        // 切替前の周波数をキャッシュ
        originalFreqMap.clear()
        for (noteIndex in activeNotes) {
            val freq = noteButtons[noteIndex]?.tag as? Double ?: continue
            originalFreqMap[noteIndex] = freq
        }
        toneGenerator.setOriginalFreqMap(originalFreqMap)

        // 設定変更
        applySetting()

        // 再生中の音をフェードアウト＋復帰予約
        for (noteIndex in activeNotes) {
            toneGenerator.stopTone(noteIndex, resumeAfter = true)
            playingNotes[noteIndex] = false
        }

        // UI更新
        Log.d("DebugNoteButtons", "Call updateNoteButtons() from applyModeChangeWithFade modeName=$modeName")

        if(isPortrait()){
            if(modeName == "Tuning"){
                updateNoteButtons(skipStop = true, skipRearrange = true)
            }else{
                updateNoteButtons(skipStop = true)
            }
        }else{
            if(modeName == "Tuning"){
                updateNoteViews()
            }else{
                updateNoteViews()
            }
        }

        Log.d("MainActivity", "applyModeChangeWithFade: $modeName 切り替え完了 activeNotes=$activeNotes")
    }

    // 楽器・長短調に応じたキーリスト表示更新
    private fun updateKeyDisplayStaticList() {
        val isMinor = switchScale.isChecked
        val instKey = calculator.getInstrumentKey()

        // 楽器＋長短調の組み合わせで string-array を選択
        val resId = when (instKey) {
            InstrumentKey.C -> if (isMinor) R.array.key_signatures_minor_c else R.array.key_signatures_major_c
            InstrumentKey.Bb -> if (isMinor) R.array.key_signatures_minor_bb else R.array.key_signatures_major_bb
            InstrumentKey.Eb -> if (isMinor) R.array.key_signatures_minor_eb else R.array.key_signatures_major_eb
            InstrumentKey.F -> if (isMinor) R.array.key_signatures_minor_f else R.array.key_signatures_major_f
            InstrumentKey.A -> if (isMinor) R.array.key_signatures_minor_a else R.array.key_signatures_major_a
            else -> if (isMinor) R.array.key_signatures_minor_c else R.array.key_signatures_major_c // デフォルトはC管
        }

        val spinnerKey = findViewById<Spinner>(R.id.spinnerKeySignature)
        val adapter = ArrayAdapter.createFromResource(
            this,
            resId,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerKey.adapter = adapter
    }

    private fun updateNoteViews() {

    }
}
