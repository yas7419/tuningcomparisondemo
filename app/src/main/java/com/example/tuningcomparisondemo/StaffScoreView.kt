package com.example.tuningcomparisondemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat

class StaffScoreView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // タップイベント通知用インターフェース
    fun interface OnNoteClickListener {
        fun onNoteClick(index: Int, note: NoteItem)
    }

    var onNoteClickListener: OnNoteClickListener? = null

    // 各音符のタップ判定用 X 座標とクリック範囲幅
    private val noteXPositions = mutableListOf<Float>()
    private var noteSpacingWidth = 0f

    private var downX = 0f
    private var downY = 0f

    private var notes: List<NoteItem> = emptyList()
    private var clefType: ClefType = ClefType.TREBLE

    private var chordRoot: Int = 0
    private var chordType: ChordType = ChordType.MAJOR
    private var instrumentKey: InstrumentKey = InstrumentKey.C

    private val staffLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val playingTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val musicFontPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        try {
            typeface = ResourcesCompat.getFont(context, R.font.bravura)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setClef(clef: ClefType) {
        if (clefType != clef) {
            clefType = clef
            invalidate()
        }
    }

    fun setKeyConfiguration(root: Int, type: ChordType, instKey: InstrumentKey) {
        chordRoot = root
        chordType = type
        instrumentKey = instKey
        invalidate()
    }

    fun setNotes(newNotes: List<NoteItem>) {
        notes = newNotes
        invalidate()
    }

    fun getNotes(): List<NoteItem> = notes

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        if (viewWidth <= 0 || viewHeight <= 0) return

        val marginX = 40f
        val startX = marginX
        val endX = viewWidth - marginX

        val lineSpacing = (viewHeight / 12f).coerceAtLeast(16f)
        val staffTopY = (viewHeight - (lineSpacing * 4)) / 2f

        // 1. 五線（5本線）
        for (i in 0..4) {
            val y = staffTopY + (i * lineSpacing)
            canvas.drawLine(startX, y, endX, y, staffLinePaint)
        }

        // 小節線（左端）
        canvas.drawLine(
            startX,
            staffTopY,
            startX,
            staffTopY + (lineSpacing * 4),
            staffLinePaint
        )

        // 2. 音部記号
        val clefX = startX + 20f

        when (clefType) {
            ClefType.TREBLE -> {
                musicFontPaint.textSize = lineSpacing * 4.2f
                val gLineY = staffTopY + (lineSpacing * 3)
                val clefBaselineY = gLineY + (lineSpacing * 0.5f)
                canvas.drawText("\uE050", clefX, clefBaselineY, musicFontPaint)
            }

            ClefType.BASS -> {
                musicFontPaint.textSize = lineSpacing * 3.5f
                val fLineY = staffTopY + lineSpacing
                val clefBaselineY = fLineY + (lineSpacing * 1.0f)
                canvas.drawText("\uE062", clefX, clefBaselineY, musicFontPaint)
            }
        }

        // 3. 調号
        val keyInfo = KeySignatureHelper.getKeyInfo(chordRoot, chordType, instrumentKey)
        val keyCount = keyInfo.count
        val keySigWidth = if (keyCount != 0) Math.abs(keyCount) * (lineSpacing * 0.8f) + (lineSpacing * 1.0f) else lineSpacing * 1.5f

        drawKeySignature(canvas, startX + (lineSpacing * 3.5f), staffTopY, lineSpacing)

        // 4. 音符群（Notehead, Stem, Ledger lines, Accidentals, Labels）の描画
        val notesStartX = startX + (lineSpacing * 3.5f) + keySigWidth
        val availableWidth = endX - notesStartX - (lineSpacing * 1.0f)

        drawNotes(canvas, notesStartX, availableWidth, staffTopY, lineSpacing, keyCount)
    }

    private fun drawKeySignature(canvas: Canvas, startX: Float, staffTopY: Float, lineSpacing: Float) {
        val keyInfo = KeySignatureHelper.getKeyInfo(chordRoot, chordType, instrumentKey)
        val count = keyInfo.count

        if (count == 0) return

        val alteredSteps = KeySignatureHelper.getAlteredSteps(count)
        val isSharp = count > 0

        musicFontPaint.textSize = lineSpacing * 3.0f

        val glyph = if (isSharp) "\uED62" else "\uED60"

        val glyphOffset = if (isSharp) {
            lineSpacing * 1.2f
        } else {
            lineSpacing * 0.8f
        }

        val symbolSpacing = lineSpacing * 0.8f

        alteredSteps.forEachIndexed { index, step ->
            val x = startX + (index * symbolSpacing)
            val lineOffset = getClefStepOffset(step, isSharp)
            val firstLineY = staffTopY + (lineSpacing * 4)
            val y = firstLineY - (lineOffset * (lineSpacing / 2f)) + glyphOffset

            canvas.drawText(glyph, x, y, musicFontPaint)
        }
    }

    private fun drawNotes(canvas: Canvas, startX: Float, availableWidth: Float, staffTopY: Float, lineSpacing: Float, keyCount: Int) {
        if (notes.isEmpty()) return

        val noteCount = notes.size
        noteSpacingWidth = availableWidth / (noteCount.coerceAtLeast(1))
        val firstLineY = staffTopY + (lineSpacing * 4)

        // タップ判定用に X 座標記録用リストをクリア・更新
        noteXPositions.clear()

        val previousAlteredSteps = mutableMapOf<Int, String>()

        val isMinor = chordType == ChordType.MINOR
        val highlightedIndices = if (isMinor) {
            setOf(0, 3, 7) // 根音(0), 短3度(3), 完全5度(7)
        } else {
            setOf(0, 4, 7) // 根音(0), 長3度(4), 完全5度(7)
        }

        val highlightColor = Color.parseColor("#1976D2")

        notes.forEachIndexed { index, note ->
            val x = startX + (index * noteSpacingWidth) + (noteSpacingWidth / 2f)
            noteXPositions.add(x)

            val rawNoteIndex = note.noteIndex + chordRoot
            val absoluteNoteIndex = rawNoteIndex % 12

            val lineOffset = getNoteLineOffset(rawNoteIndex, clefType, keyCount)
            val noteY = firstLineY - (lineOffset * (lineSpacing / 2f))

            val isHighlighted = highlightedIndices.contains(note.noteIndex)

            // A. 音符ペイントの設定（再生中: 赤 > 強調度数: 青 > 通常: 黒）
            val currentPaint = Paint(musicFontPaint).apply {
                color = when {
                    note.isPlaying -> Color.RED
                    isHighlighted  -> highlightColor
                    else           -> Color.BLACK
                }
            }

            val currentStemPaint = Paint(stemPaint).apply {
                color = when {
                    note.isPlaying -> Color.RED
                    isHighlighted  -> highlightColor
                    else           -> Color.BLACK
                }
            }

            val currentTextPaint = Paint(textPaint).apply {
                color = when {
                    note.isPlaying -> Color.RED
                    isHighlighted  -> highlightColor
                    else           -> Color.BLACK
                }
                isFakeBoldText = isHighlighted || note.isPlaying
            }

            // B. 加線（Ledger Lines）
            if (lineOffset < 0) {
                var l = -2
                while (l >= lineOffset) {
                    val ly = firstLineY - (l * (lineSpacing / 2f))
                    canvas.drawLine(x - (lineSpacing * 0.8f), ly, x + (lineSpacing * 0.8f), ly, staffLinePaint)
                    l -= 2
                }
            } else if (lineOffset > 8) {
                var l = 10
                while (l <= lineOffset) {
                    val ly = firstLineY - (l * (lineSpacing / 2f))
                    canvas.drawLine(x - (lineSpacing * 0.8f), ly, x + (lineSpacing * 0.8f), ly, staffLinePaint)
                    l += 2
                }
            }

            // C. 臨時記号（Accidental）
            val accidentalGlyph = getAccidentalGlyph(absoluteNoteIndex, keyCount, previousAlteredSteps)
            if (accidentalGlyph != null) {
                currentPaint.textSize = lineSpacing * 2.8f
                val accidentalOffset = when (accidentalGlyph) {
                    "\uED62" -> lineSpacing * 1.0f
                    "\uED60" -> lineSpacing * 0.8f
                    else     -> lineSpacing * 0.9f
                }
                val accidentalX = x - (lineSpacing * 1.5f)
                val accidentalY = noteY + accidentalOffset
                canvas.drawText(accidentalGlyph, accidentalX, accidentalY, currentPaint)

                val step = getDiatonicStep(absoluteNoteIndex, keyCount < 0)
                previousAlteredSteps[step] = accidentalGlyph
            }

            // D. 符頭（Notehead: U+E0A4）
            currentPaint.textSize = lineSpacing * 3.8f
            val noteheadYOffset = lineSpacing * 0.0f
            val noteheadY = noteY + noteheadYOffset
            canvas.drawText("\uE0A4", x - (lineSpacing * 0.6f), noteheadY, currentPaint)

            // E. 符幹（Stem）
            val isStemUp = lineOffset < 4
            val stemLength = lineSpacing * 3.2f
            val stemX = if (isStemUp) x + (lineSpacing * 0.3f) else x - (lineSpacing * 0.35f)
            val stemStartY = noteY
            val stemEndY = if (isStemUp) noteY - stemLength else noteY + stemLength

            canvas.drawLine(stemX, stemStartY, stemX, stemEndY, currentStemPaint)

            // F. ラベル（度数・センタ差）
            val labelY = staffTopY + (lineSpacing * 4) + (lineSpacing * 2.2f)

            canvas.drawText(note.intervalLabel, x, labelY, currentTextPaint)

            val centText = String.format("%+.1f cent", note.centDiff)
            canvas.drawText(centText, x, labelY + 32f, currentTextPaint)
        }
    }

    private fun getAccidentalGlyph(
        absoluteNoteIndex: Int,
        keyCount: Int,
        previousAlteredSteps: Map<Int, String>
    ): String? {
        val alteredStepsBySignature = KeySignatureHelper.getAlteredSteps(keyCount)
        val isSharpKey = keyCount > 0
        val isFlatKey = keyCount < 0

        val step = getDiatonicStep(absoluteNoteIndex, isFlatKey)

        val isAccidentalNote = when (absoluteNoteIndex % 12) {
            1, 3, 6, 8, 10 -> true
            else -> false
        }

        if (isAccidentalNote) {
            val defaultGlyph = when (absoluteNoteIndex % 12) {
                1 -> if (isFlatKey) (if (alteredStepsBySignature.contains(1)) null else "\uED60") else (if (alteredStepsBySignature.contains(0)) null else "\uED62")
                3 -> if (isFlatKey) (if (alteredStepsBySignature.contains(2)) null else "\uED60") else (if (alteredStepsBySignature.contains(1)) null else "\uED62")
                6 -> if (isFlatKey) (if (alteredStepsBySignature.contains(4)) null else "\uED60") else (if (alteredStepsBySignature.contains(3)) null else "\uED62")
                8 -> if (isFlatKey) (if (alteredStepsBySignature.contains(5)) null else "\uED60") else (if (alteredStepsBySignature.contains(4)) null else "\uED62")
                10 -> if (isFlatKey) (if (alteredStepsBySignature.contains(6)) null else "\uED60") else (if (alteredStepsBySignature.contains(5)) null else "\uED62")
                else -> null
            }

            if (defaultGlyph == null && previousAlteredSteps[step] == "\uED61") {
                return if (isFlatKey) "\uED60" else "\uED62"
            }

            return defaultGlyph
        } else {
            val isAlteredBySignature = alteredStepsBySignature.contains(step)
            val previousGlyph = previousAlteredSteps[step]
            val isAlteredByPreviousNote = previousGlyph == "\uED60" || previousGlyph == "\uED62"

            if (isAlteredBySignature || isAlteredByPreviousNote) {
                return "\uED61"
            }

            return null
        }
    }

    private fun getDiatonicStep(noteIndex: Int, isFlatKey: Boolean): Int {
        return when (noteIndex % 12) {
            0  -> 0
            1  -> if (isFlatKey) 1 else 0
            2  -> 1
            3  -> if (isFlatKey) 2 else 1
            4  -> 2
            5  -> 3
            6  -> if (isFlatKey) 4 else 3
            7  -> 4
            8  -> if (isFlatKey) 5 else 4
            9  -> 5
            10 -> if (isFlatKey) 6 else 5
            11 -> 6
            else -> 0
        }
    }

    private fun getNoteLineOffset(noteIndex: Int, clef: ClefType, keyCount: Int): Int {
        val isFlatKey = keyCount < 0

        val step = when (noteIndex % 12) {
            0  -> -2
            1  -> if (isFlatKey) -1 else -2
            2  -> -1
            3  -> if (isFlatKey) 0 else -1
            4  -> 0
            5  -> 1
            6  -> if (isFlatKey) 2 else 1
            7  -> 2
            8  -> if (isFlatKey) 3 else 2
            9  -> 3
            10 -> if (isFlatKey) 4 else 3
            11 -> 4
            else -> -2
        }

        var octaveOffset = (noteIndex / 12) * 7

        val absoluteRootStep = getDiatonicStep(chordRoot % 12, isFlatKey) - 2

        if (step + octaveOffset < absoluteRootStep) {
            octaveOffset += 7
        }

        val totalStep = step + octaveOffset

        return if (clef == ClefType.TREBLE) {
            totalStep
        } else {
            val rootNoteInOctave = chordRoot % 12
            val bassShift = if (rootNoteInOctave in 0..4) 5 else -2
            totalStep + bassShift
        }
    }

    private fun getClefStepOffset(step: Int, isSharp: Boolean): Int {
        return when (clefType) {
            ClefType.TREBLE -> {
                if (isSharp) {
                    when (step) {
                        3 -> 8; 0 -> 5; 4 -> 9; 1 -> 6; 5 -> 3; 2 -> 7; 6 -> 4; else -> 0
                    }
                } else {
                    when (step) {
                        6 -> 4; 2 -> 7; 5 -> 3; 1 -> 6; 4 -> 2; 0 -> 5; 3 -> 1; else -> 0
                    }
                }
            }
            ClefType.BASS -> {
                if (isSharp) {
                    when (step) {
                        3 -> 6; 0 -> 3; 4 -> 7; 1 -> 4; 5 -> 1; 2 -> 5; 6 -> 2; else -> 0
                    }
                } else {
                    when (step) {
                        6 -> 2; 2 -> 5; 5 -> 1; 1 -> 4; 0 -> 0; 0 -> 3; 3 -> -1; else -> 0
                    }
                }
            }
        }
    }

    // --- タッチイベント処理 (onTouchEvent) ---
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val deltaX = Math.abs(event.x - downX)
                val deltaY = Math.abs(event.y - downY)

                if (deltaX < 20f && deltaY < 20f) {
                    val tappedIndex = findTappedNoteIndex(event.x)
                    if (tappedIndex != -1 && tappedIndex < notes.size) {
                        onNoteClickListener?.onNoteClick(tappedIndex, notes[tappedIndex])
                        return true
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findTappedNoteIndex(touchX: Float): Int {
        if (noteXPositions.isEmpty() || noteSpacingWidth <= 0f) return -1

        val halfWidth = noteSpacingWidth / 2f
        noteXPositions.forEachIndexed { index, centerX ->
            if (touchX >= (centerX - halfWidth) && touchX <= (centerX + halfWidth)) {
                return index
            }
        }
        return -1
    }
}