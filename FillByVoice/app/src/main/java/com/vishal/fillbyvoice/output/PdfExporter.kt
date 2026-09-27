package com.vishal.fillbyvoice.output

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.provider.MediaStore
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.vishal.fillbyvoice.flow.Answer
import com.vishal.fillbyvoice.pipeline.cleanLabel
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

// A4 in PDF points (1/72 inch).
private const val PAGE_W = 595
private const val PAGE_H = 842
private const val MARGIN = 48f

private val DEVANAGARI = Regex("[ऀ-ॿ]")

// Part 12: one PDF. Page 1: the filled form (the scanned photo with the answers written in), when there is one.
// Then the answer sheet: each form label and its answer in big text, in page order, to copy onto the paper form.
// English answers in BLOCK letters (bank forms ask for them), Hindi answers as spoken, and a skipped question gets
// an empty line to fill by hand. Text goes through StaticLayout, which wraps long addresses and shapes Devanagari
// with the phone's own fonts.
fun writeAnswerSheet(context: Context, answers: List<Answer>, filled: Bitmap?): File {
    val now = LocalDateTime.now()
    val width = (PAGE_W - 2 * MARGIN).toInt()
    val title = paint(22f, bold = true)
    val small = paint(10f, color = Color.GRAY)
    val label = paint(11f, color = Color.DKGRAY)
    val value = paint(17f, bold = true)
    val rule = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }

    val doc = PdfDocument()
    // A photo taken sideways gets a sideways page, so the form fills it.
    filled?.let { form ->
        val (w, h) = if (form.width > form.height) PAGE_H to PAGE_W else PAGE_W to PAGE_H
        val first = doc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
        // A guide, not the form itself: the bank still wants its original paper, signed.
        first.canvas.drawText("Guide · भरा हुआ फ़ॉर्म · copy these answers onto your original form", 24f, 24f, label)
        val fit = min((w - 48f) / form.width, (h - 72f) / form.height)
        val left = (w - form.width * fit) / 2
        val where = RectF(left, 40f, left + form.width * fit, 40f + form.height * fit)
        first.canvas.drawBitmap(form, null, where, Paint(Paint.FILTER_BITMAP_FLAG))
        doc.finishPage(first)
    }
    var page: PdfDocument.Page? = null
    var y = 0f
    fun newPage() {
        page?.let(doc::finishPage)
        val number = doc.pages.size + 1
        page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, number).create()).apply {
            canvas.drawText("Fill by Voice · page $number · made on this phone, offline", MARGIN, PAGE_H - 24f, small)
        }
        y = MARGIN
    }
    fun draw(layout: StaticLayout) {
        val canvas = page!!.canvas
        canvas.save()
        canvas.translate(MARGIN, y)
        layout.draw(canvas)
        canvas.restore()
        y += layout.height
    }

    newPage()
    draw(layout("Answer sheet · उत्तर पत्र", title, width))
    val stamp = now.format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH))
    draw(layout("Filled by voice on $stamp. Copy each answer into the box with the same label on the form.", small, width))
    y += 16f
    answers.forEachIndexed { i, answer ->
        val labelText = layout("${i + 1}. ${cleanLabel(answer.question.label)}", label, width)
        val valueText = answer.value?.let { layout(written(it, answer.question.type), value, width) }
        if (y + labelText.height + (valueText?.height ?: 28) + 20f > PAGE_H - 56f) newPage()
        draw(labelText)
        y += 4f
        // Skipped: the empty space above the line is where the helper writes by hand.
        if (valueText != null) draw(valueText) else y += 28f
        y += 8f
        page!!.canvas.drawLine(MARGIN, y, MARGIN + width, y, rule)
        y += 12f
    }
    page?.let(doc::finishPage)

    val dir = File(context.cacheDir, "pdfs").apply { mkdirs() }
    val file = File(dir, "answer-sheet-${now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.pdf")
    file.outputStream().use(doc::writeTo)
    doc.close()
    return file
}

// Download: a copy in the phone's Downloads folder, where the Files app shows it. Since Android 10 an app needs no
// storage permission for files it adds there itself.
fun saveToDownloads(context: Context, file: File) {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Downloads did not take the file")
    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        ?: error("Could not write to Downloads")
}

// Share: WhatsApp, Gmail and the rest get a short-lived read link to the file (FileProvider). Our app itself sends
// nothing over the network.
fun sharePdf(context: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title))
}

// "Vishal Singh" -> "VISHAL SINGH". Emails stay as they are; Hindi has no capitals.
internal fun written(value: String, type: String): String =
    if (type == "email" || DEVANAGARI.containsMatchIn(value)) value else value.uppercase(Locale.ENGLISH)

private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, width).build()

private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = size
    this.color = color
    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
}
