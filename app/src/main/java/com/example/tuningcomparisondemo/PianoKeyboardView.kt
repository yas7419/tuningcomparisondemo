package com.example.tuningcomparisondemo

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

data class NoteItem(
    val noteIndex: Int,
    val concertChromatic: Int,
    val isSharpFlat: Boolean,
    val nameLabel: String,
    val intervalLabel: String,
    val freq: Double,
    val centDiff: Double,
    val isPlaying: Boolean,
    val isRoot: Boolean,
    val isChordTone: Boolean
)

class PianoKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var notes: List<NoteItem> = emptyList()
    private val keyRects = mutableMapOf<Int, RectF>()
    private val blackKeyNoteIndices = mutableSetOf<Int>()
    private var onKeyClickListener: ((Int) -> Unit)? = null

    fun setOnKeyClickListener(listener: (Int) -> Unit) {
        onKeyClickListener = listener
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#333333")
    }

    // 文字サイズを大きく設定 (24f -> 28f, 18f -> 20f)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true // 太字にしてさらに視認性を向上
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    fun setNotes(newNotes: List<NoteItem>) {
        notes = newNotes
        if (width > 0 && height > 0) {
            calculateRects(width, height)
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateRects(w, h)
    }

    private fun calculateRects(w: Int, h: Int) {
        keyRects.clear()
        blackKeyNoteIndices.clear()
        if (notes.isEmpty()) return

        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        if (isPortrait) {
            val sortedNotes = notes.sortedBy { it.noteIndex }

            val naturalNotes = sortedNotes.filter { !it.isSharpFlat }
            val accidentalNotes = sortedNotes.filter { it.isSharpFlat }

            val whiteKeyCount = if (naturalNotes.isNotEmpty()) naturalNotes.size else 7

            // --- 最下段黒鍵の高さ調整ロジック ---
            // 1. まず1キーあたりの基準高さを計算
            val baseWhiteKeyHeight = h / whiteKeyCount.toFloat()
            val blackKeyHeight = baseWhiteKeyHeight * 0.65f

            // 2. 最下段（noteIndex最小）が黒鍵かどうかを判定
            val minNote = sortedNotes.minByOrNull { it.noteIndex }
            val startsWithBlackKey = minNote?.isSharpFlat == true

            // 3. 最下段が黒鍵の場合は、下部に黒鍵半音分のオフセット（余白）を設ける
            val bottomOffset = if (startsWithBlackKey) blackKeyHeight / 2f else 0f

            // 実際に白鍵7つ分を敷き詰める有効領域の高さ
            val availableHeight = h - bottomOffset
            val whiteKeyHeight = availableHeight / whiteKeyCount.toFloat()

            // STEP A: 白鍵（背景）の配置
            for (i in naturalNotes.indices) {
                val note = naturalNotes[i]
                val bottom = availableHeight - (i * whiteKeyHeight)
                val top = availableHeight - ((i + 1) * whiteKeyHeight)
                keyRects[note.noteIndex] = RectF(0f, top, w.toFloat(), bottom)
            }

            // STEP B: 黒鍵（前面・幅55%）の配置
            val blackKeyWidth = w * 0.55f

            for (note in accidentalNotes) {
                val lowerWhiteCount = naturalNotes.count { it.noteIndex < note.noteIndex }

                val boundaryY = when {
                    lowerWhiteCount == 0 -> availableHeight // 最下段が黒鍵の場合、有効領域の底面（余白の上）を境界にする
                    lowerWhiteCount >= whiteKeyCount -> 0f
                    else -> availableHeight - (lowerWhiteCount * whiteKeyHeight)
                }

                // 下部にオフセットが確保されているため、coerceAtMost(h) で切られても他の黒鍵と同じ高さ(blackKeyHeight)が保たれます
                val top = (boundaryY - blackKeyHeight / 2f).coerceAtLeast(0f)
                val bottom = (boundaryY + blackKeyHeight / 2f).coerceAtMost(h.toFloat())

                keyRects[note.noteIndex] = RectF(0f, top, blackKeyWidth, bottom)
                blackKeyNoteIndices.add(note.noteIndex)
            }
        } else {
            // --- 横画面 (Landscape) ---
            val colWidth = w / 12f
            val sortedNotes = notes.sortedBy { it.noteIndex }

            for (i in sortedNotes.indices) {
                val note = sortedNotes[i]
                val left = i * colWidth
                val right = (i + 1) * colWidth

                val rect = if (note.isSharpFlat) {
                    RectF(left + 1f, 0f, right - 1f, h * 0.65f)
                } else {
                    RectF(left + 1f, 0f, right - 1f, h.toFloat())
                }

                keyRects[note.noteIndex] = rect
                if (note.isSharpFlat) {
                    blackKeyNoteIndices.add(note.noteIndex)
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        if (isPortrait) {
            // 背景の白鍵を描画
            for (note in notes.filter { !it.isSharpFlat }) {
                drawKey(canvas, note)
            }
            // 前面の黒鍵を描画
            for (note in notes.filter { it.isSharpFlat }) {
                drawKey(canvas, note)
            }
        } else {
            for (note in notes.filter { !it.isSharpFlat }) {
                drawKey(canvas, note)
            }
            for (note in notes.filter { it.isSharpFlat }) {
                drawKey(canvas, note)
            }
        }
    }

    private fun drawKey(canvas: Canvas, note: NoteItem) {
        val rect = keyRects[note.noteIndex] ?: return

        val bgColor = when {
            note.isPlaying -> Color.parseColor("#FFF176")
            note.isRoot -> if (note.isSharpFlat) Color.parseColor("#4A2C00") else Color.parseColor("#8D6E63")
            note.isChordTone -> if (note.isSharpFlat) Color.parseColor("#1C313A") else Color.parseColor("#90A4AE")
            note.isSharpFlat -> Color.parseColor("#212121")
            else -> Color.parseColor("#E0E0E0")
        }
        bgPaint.color = bgColor
        canvas.drawRect(rect, bgPaint)
        canvas.drawRect(rect, strokePaint)

        val textColor = when {
            note.isPlaying -> Color.parseColor("#1A237E")
            note.isRoot -> Color.parseColor("#FFD700")
            note.isChordTone -> if (note.isSharpFlat) Color.parseColor("#80DEEA") else Color.parseColor("#004D40")
            note.isSharpFlat -> Color.parseColor("#FFFFFF")
            else -> Color.parseColor("#212121")
        }

        drawKeyText(canvas, note, rect, textColor)
    }

    private fun drawKeyText(canvas: Canvas, note: NoteItem, rect: RectF, textColor: Int) {
        textPaint.color = textColor
        subTextPaint.color = textColor

        val cy = rect.centerY()
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        if (isPortrait) {
            val prefix = if (note.isRoot) "★基準 " else ""
            val line1 = "$prefix${note.nameLabel} [${note.intervalLabel}]"
            val line2 = "${"%.2f".format(note.freq)} Hz  ${"%.1f".format(note.centDiff)} cent"

            // X位置: 白鍵は右側エリア（55%〜100%）の中央、黒鍵は黒鍵自体の領域（0%〜55%）の中央
            val cx = if (note.isSharpFlat) {
                rect.centerX()
            } else {
                val blackKeyRight = width * 0.55f
                blackKeyRight + (width - blackKeyRight) / 2f
            }

            // 文字拡大に伴い上下の描画オフセットを調整
            canvas.drawText(line1, cx, cy - 6f, textPaint)
            canvas.drawText(line2, cx, cy + 26f, subTextPaint)
        } else {
            val cx = rect.centerX()
            val prefix = if (note.isRoot) "★ " else ""
            val line1 = "$prefix${note.nameLabel}"
            val line2 = "[${note.intervalLabel}]"
            val line3 = "${"%.1f".format(note.centDiff)}c"

            val yOffset = cy - 8f
            canvas.drawText(line1, cx, yOffset - 20f, textPaint)
            canvas.drawText(line2, cx, yOffset + 6f, subTextPaint)
            canvas.drawText(line3, cx, yOffset + 28f, subTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_POINTER_DOWN) {
            val x = event.x
            val y = event.y

            for (noteIndex in blackKeyNoteIndices) {
                val rect = keyRects[noteIndex] ?: continue
                if (rect.contains(x, y)) {
                    onKeyClickListener?.invoke(noteIndex)
                    performClick()
                    return true
                }
            }

            for ((noteIndex, rect) in keyRects) {
                if (!blackKeyNoteIndices.contains(noteIndex) && rect.contains(x, y)) {
                    onKeyClickListener?.invoke(noteIndex)
                    performClick()
                    return true
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}