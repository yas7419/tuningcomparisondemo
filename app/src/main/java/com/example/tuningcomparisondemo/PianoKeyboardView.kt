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
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }

    fun setNotes(newNotes: List<NoteItem>) {
        notes = newNotes
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateRects(w, h)
    }

    private fun calculateRects(w: Int, h: Int) {
        keyRects.clear()
        if (notes.isEmpty()) return

        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        // 周波数順（低音から高音へ）にソート
        val sortedNotes = notes.sortedBy { it.freq }

        if (isPortrait) {
            // 縦画面：12音を下(低)から上(高)へ隙間なくスタック
            val rowHeight = h / 12f

            for (i in sortedNotes.indices) {
                val note = sortedNotes[i]
                // noteIndex 0 (低音) が一番下、11 が上
                val top = h - (i + 1) * rowHeight
                val bottom = h - i * rowHeight

                val rect = if (note.isSharpFlat) {
                    // 黒鍵：少しスリムにして左側に配置 (幅 65%)
                    RectF(0f, top + 1f, w * 0.65f, bottom - 1f)
                } else {
                    // 白鍵：全幅
                    RectF(0f, top + 1f, w.toFloat(), bottom - 1f)
                }
                keyRects[note.noteIndex] = rect
            }
        } else {
            // 横画面：12音を左(低)から右(高)へ隙間なく並べる
            val colWidth = w / 12f

            for (i in sortedNotes.indices) {
                val note = sortedNotes[i]
                val left = i * colWidth
                val right = (i + 1) * colWidth

                val rect = if (note.isSharpFlat) {
                    // 黒鍵：上側に配置 (高さ 65%)
                    RectF(left + 1f, 0f, right - 1f, h * 0.65f)
                } else {
                    // 白鍵：全高
                    RectF(left + 1f, 0f, right - 1f, h.toFloat())
                }
                keyRects[note.noteIndex] = rect
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (note in notes) {
            val rect = keyRects[note.noteIndex] ?: continue

            // 1. 色の決定（発音中は色を反転・ハイライト）
            val bgColor = when {
                note.isPlaying -> Color.parseColor("#FFF176") // 再生中（明るい黄色）
                note.isRoot -> Color.parseColor("#3E2723")    // 根音（ブラウン/ゴールド）
                note.isChordTone -> Color.parseColor("#263238") // 構成音（ダークブルーグレー）
                note.isSharpFlat -> Color.parseColor("#212121") // 黒鍵デフォルト
                else -> Color.parseColor("#E0E0E0")             // 白鍵デフォルト
            }
            bgPaint.color = bgColor
            canvas.drawRect(rect, bgPaint)
            canvas.drawRect(rect, strokePaint)

            // 2. テキスト色の決定
            val textColor = when {
                note.isPlaying -> Color.parseColor("#1A237E")
                note.isRoot -> Color.parseColor("#FFD700")
                note.isChordTone -> Color.parseColor("#80DEEA")
                note.isSharpFlat -> Color.parseColor("#FFFFFF")
                else -> Color.parseColor("#212121")
            }

            drawKeyText(canvas, note, rect, textColor)
        }
    }

    private fun drawKeyText(canvas: Canvas, note: NoteItem, rect: RectF, textColor: Int) {
        textPaint.color = textColor
        subTextPaint.color = textColor

        val cx = rect.centerX()
        val cy = rect.centerY()
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        if (isPortrait) {
            val prefix = if (note.isRoot) "★基準 " else ""
            val line1 = "$prefix${note.nameLabel} [${note.intervalLabel}]"
            val line2 = "${"%.2f".format(note.freq)} Hz"
            val line3 = "${"%.1f".format(note.centDiff)} cent"

            val yOffset = cy - 8f
            canvas.drawText(line1, cx, yOffset - 20f, textPaint)
            canvas.drawText(line2, cx, yOffset + 4f, subTextPaint)
            canvas.drawText(line3, cx, yOffset + 24f, subTextPaint)
        } else {
            val prefix = if (note.isRoot) "★ " else ""
            val line1 = "$prefix${note.nameLabel}"
            val line2 = "[${note.intervalLabel}]"
            val line3 = "${"%.1f".format(note.centDiff)}c"

            val yOffset = cy - 6f
            canvas.drawText(line1, cx, yOffset - 16f, textPaint)
            canvas.drawText(line2, cx, yOffset + 4f, subTextPaint)
            canvas.drawText(line3, cx, yOffset + 20f, subTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_POINTER_DOWN) {
            val x = event.x
            val y = event.y

            for ((noteIndex, rect) in keyRects) {
                if (rect.contains(x, y)) {
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
