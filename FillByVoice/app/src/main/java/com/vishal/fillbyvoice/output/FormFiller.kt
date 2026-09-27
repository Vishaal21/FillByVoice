package com.vishal.fillbyvoice.output

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import com.vishal.fillbyvoice.flow.Answer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The filled form is kept at about this size: sharp enough to read and print, small enough for the PDF.
private const val MAX_SIDE = 2000f

// Blue pen ink, on a soft white patch so the answer stays readable over the form's printed boxes.
private const val INK = 0xFF1537A8.toInt()
private val PATCH = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }

// "Paper form in, filled form out": a copy of the scanned photo with each answer written next to its question.
// OCR already knows where every question's label sits (the red boxes), so the answer goes on the label's line,
// right after it; with no room there it shrinks a little, else it goes just under the label. Skipped: nothing.
fun fillForm(photo: Bitmap, answers: List<Answer>): Bitmap {
    val scale = min(1f, MAX_SIDE / max(photo.width, photo.height))
    val form = Bitmap.createScaledBitmap(photo, (photo.width * scale).roundToInt(), (photo.height * scale).roundToInt(), true)
        .copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(form)
    for (answer in answers) {
        val value = answer.value ?: continue
        val label = RectF(answer.question.box).apply { left *= scale; top *= scale; right *= scale; bottom *= scale }
        val h = label.height()
        val text = written(value, answer.question.type)
        val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            typeface = Typeface.DEFAULT_BOLD
            textSize = h * 1.1f
        }
        var x = label.right + h * 0.6f
        var baseline = label.bottom - h * 0.15f
        val room = form.width - x - h * 0.5f
        val width = ink.measureText(text)
        if (width > room) {
            if (room / width >= 0.7f) {
                ink.textSize *= room / width
            } else {
                x = label.left
                baseline = label.bottom + h * 1.3f
                val below = form.width - x - h * 0.5f
                if (ink.measureText(text) > below) ink.textSize *= below / ink.measureText(text)
            }
        }
        val metrics = ink.fontMetrics
        val pad = h * 0.15f
        canvas.drawRoundRect(
            x - pad, baseline + metrics.ascent - pad, x + ink.measureText(text) + pad, baseline + metrics.descent + pad,
            pad, pad, PATCH,
        )
        canvas.drawText(text, x, baseline, ink)
    }
    return form
}
