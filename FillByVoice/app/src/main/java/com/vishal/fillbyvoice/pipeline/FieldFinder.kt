package com.vishal.fillbyvoice.pipeline

import android.graphics.Rect
import android.util.Log
import com.vishal.fillbyvoice.llm.Gemma
import com.vishal.fillbyvoice.ocr.OcrLine
import com.vishal.fillbyvoice.voice.Language
import org.json.JSONArray
import org.json.JSONException

// One question the user must answer, ready for the voice loop.
data class Question(
    val id: String,
    val type: String,
    val askHi: String,
    val options: List<String>,
    val label: String, // the form's own wording, from OCR (also used as the English question)
    val box: Rect, // where the question sits on the photo
)

data class FoundQuestions(val questions: List<Question>, val tokensPerSecond: Double)

// What the phone says: the Hindi question, or the form's own label in English.
fun Question.ask(language: Language): String = when (language) {
    Language.HINDI -> askHi
    Language.ENGLISH -> cleanLabel(label) + "?"
}

private const val TAG = "FieldFinder"

// A 2B model stays accurate on short replies, so big pages are sent in chunks of about this many lines.
private const val CHUNK_LINES = 25

private val TYPES = listOf(
    "text", "number", "date", "mobile", "email", "pincode", "aadhaar", "pan", "ifsc", "choice",
)

// The runtime forces Gemma's reply into this shape, so the JSON is always valid.
private val SCHEMA = """
{"type":"array","items":{"type":"object","properties":{
"line":{"type":"integer"},
"label":{"type":"string"},
"id":{"type":"string"},
"type":{"type":"string","enum":[${TYPES.joinToString(",") { "\"$it\"" }}]},
"options":{"type":"array","items":{"type":"string"}},
"ask_hi":{"type":"string"}},
"required":["line","label","id","type","ask_hi"]}}
""".trimIndent()

// Few-shot prompt: English rules + one worked example with the traps seen on real bank forms.
private val PROMPT = """
You read numbered lines of text from a photo of a paper form and find the questions the person must answer.
Reply with ONLY a JSON array.

Rules:
1. Skip titles, headings, instructions, notes, placeholders like "Short answer text", office-use fields and junk.
2. Skip box hints made of spaced single letters, like "S A L U T A T I O N" or "D D M M Y Y Y Y",
   hints like "Maximum 40 characters", bank names and logo text.
3. type is one of: text, number, date, mobile, email, pincode, aadhaar, pan, ifsc, choice.
4. For tick boxes, use type "choice" on the question line and put the option words in "options".
   Option words like "Male" or "Married" are never questions on their own.
5. ask_hi is a FULL polite spoken question in simple Hindi, in Devanagari script. Never copy the label.
6. id is a short English key in snake_case.
7. label is the exact text of the question line, copied from the input.
8. Do not skip any real question.

Example input:
0: Gramin Bank - Account Opening Form
1: Please write in BLOCK letters
2: 1.Father's Name*:
3: S A L U T A T I O N
4: Village:
5: 3.Gender*
6: Male
7: Female
8: (Please tick one)
9: Date of Birth:
10: For office use only: Branch Code
Example output:
[{"line":2,"label":"1.Father's Name*:","id":"father_name","type":"text","ask_hi":"आपके पिता का नाम क्या है?"},{"line":4,"label":"Village:","id":"village","type":"text","ask_hi":"आपका गाँव कौन सा है?"},{"line":5,"label":"3.Gender*","id":"gender","type":"choice","options":["Male","Female"],"ask_hi":"आपका लिंग क्या है? पुरुष या महिला?"},{"line":9,"label":"Date of Birth:","id":"dob","type":"date","ask_hi":"आपकी जन्म तिथि क्या है?"}]

{note}Input:
{lines}
Output:
""".trimIndent()

// Pass 2 only sees leftovers, which are mostly junk. Without this note the model tends to invent questions.
private const val PASS2_NOTE =
    "Note: these lines are leftovers after a first reading. Most are NOT questions. Reply [] if none are.\n\n"

// OCR lines -> Gemma (one call per chunk of the page) -> question list.
// Each chunk retries once, then falls back to simple rules for that chunk only.
suspend fun findQuestions(lines: List<OcrLine>, onProgress: (done: Int, total: Int) -> Unit): FoundQuestions {
    val rows = readingOrder(lines.filter { isUseful(it.text) })
    val speeds = mutableListOf<Double>()
    val pass1 = chunks(rows)
    var done = 0
    var total = pass1.size

    // One Gemma call per chunk. If a chunk fails twice, its question-like lines are asked anyway.
    suspend fun read(chunks: List<List<OcrLine>>, note: String): List<Question> = chunks.flatMap { chunk ->
        onProgress(done++, total)
        askGemma(chunk, note)?.also { speeds += it.tokensPerSecond }?.questions
            ?: chunk.filter(::looksLikeQuestion).map(::askAnyway)
    }

    // Pass 1: the whole page. Pass 2: Gemma looks again, only at the lines pass 1 left unused.
    val first = cleanUp(read(pass1, note = ""))
    val pass2 = chunks(unused(rows, first))
    total += pass2.size
    val found = cleanUp(first + read(pass2, PASS2_NOTE))

    // Still unused but looks like a question ("4.Marital Status", "Business:"): ask it anyway.
    val leftover = unused(rows, found).flatten().filter(::looksLikeQuestion).map(::askAnyway)

    val position = rows.flatten().withIndex().associate { (i, line) -> line.box to i }
    val questions = (found + leftover).sortedBy { position[it.box] }
    return FoundQuestions(questions, speeds.average().takeIf { it.isFinite() } ?: 0.0)
}

private suspend fun askGemma(chunk: List<OcrLine>, note: String): FoundQuestions? {
    val numbered = chunk.withIndex().joinToString("\n") { (i, line) -> "$i: ${line.text}" }
    val prompt = PROMPT.replace("{note}", note).replace("{lines}", numbered)
    repeat(2) {
        val reply = Gemma.ask(prompt, SCHEMA)
        Log.d(TAG, "Chunk in:\n$numbered\nReply (${reply.tokensPerSecond.toInt()} tok/s):\n${reply.text}")
        try {
            return FoundQuestions(parse(reply.text, chunk), reply.tokensPerSecond)
        } catch (e: JSONException) {
            Log.w(TAG, "Bad JSON (${chunk.size} lines): ${e.message}\n${reply.text}")
        }
    }
    return null
}

// Groups lines into rows (same height on the page): rows top to bottom, left to right inside a row.
private fun readingOrder(lines: List<OcrLine>): List<List<OcrLine>> {
    val rows = mutableListOf<MutableList<OcrLine>>()
    for (line in lines.sortedBy { it.box.centerY() }) {
        val row = rows.lastOrNull()
        val first = row?.first()?.box
        if (first != null && line.box.centerY() - first.centerY() < first.height() / 2) {
            row += line
        } else {
            rows += mutableListOf(line)
        }
    }
    return rows.map { row -> row.sortedBy { it.box.left } }
}

// Whole rows per chunk, so a question and its options on the same row stay together.
private fun chunks(rows: List<List<OcrLine>>): List<List<OcrLine>> {
    val chunks = mutableListOf<MutableList<OcrLine>>()
    for (row in rows) {
        val last = chunks.lastOrNull()
        if (last != null && last.size + row.size <= CHUNK_LINES) last += row else chunks += row.toMutableList()
    }
    return chunks
}

private fun parse(reply: String, lines: List<OcrLine>): List<Question> {
    val json = JSONArray(reply.substring(reply.indexOf('[').coerceAtLeast(0)))
    return (0 until json.length()).mapNotNull { i ->
        val item = json.getJSONObject(i)
        val line = findLine(item.optString("label"), item.optInt("line", -1), lines) ?: return@mapNotNull null
        val options = item.optJSONArray("options")
        Question(
            id = item.optString("id").ifBlank { "field_$i" },
            type = item.optString("type").takeIf { it in TYPES } ?: "text",
            askHi = item.optString("ask_hi").ifBlank { line.text },
            options = options?.let { o -> (0 until o.length()).map { o.getString(it) } }.orEmpty(),
            label = line.text,
            box = line.box,
        )
    }
}

// Gemma sometimes points at the wrong line number, so Kotlin checks the number against the copied
// label and, if they disagree, uses the line whose text best matches it. No match at all: dropped.
private fun findLine(label: String, number: Int, lines: List<OcrLine>): OcrLine? {
    val wanted = words(label)
    if (wanted.isEmpty()) return lines.getOrNull(number)
    fun score(line: OcrLine) = words(line.text).count { it in wanted }.toDouble() / wanted.size
    lines.getOrNull(number)?.takeIf { score(it) >= 0.5 }?.let { return it }
    return lines.maxByOrNull(::score)?.takeIf { score(it) >= 0.5 }
}

private fun words(text: String): Set<String> = norm(text).split(" ").filter { it.length > 1 }.toSet()

// Safety net: one question per line, and an option word is never a question of its own.
private fun cleanUp(questions: List<Question>): List<Question> {
    val options = questions.flatMap { it.options }.map(::norm).toSet()
    return questions.distinctBy { it.box }.filterNot { norm(it.label) in options }
}

// Rows with only the lines that are not yet a question and not an option word.
private fun unused(rows: List<List<OcrLine>>, questions: List<Question>): List<List<OcrLine>> {
    val used = questions.map { it.box }.toSet()
    val options = questions.flatMap { it.options }.map(::norm).toSet()
    return rows.map { row -> row.filter { it.box !in used && norm(it.text) !in options } }.filter { it.isNotEmpty() }
}

// Drops crumbs ("A", "M"), box hints made of spaced letters ("T N", "M I D D L E N"),
// and screen junk when a form is photographed off a laptop (page counter "1 / 10", URLs).
private fun isUseful(text: String): Boolean = text.trim().let {
    it.length > 2 && !SPACED_LETTERS.matches(it) && !PAGE_COUNTER.matches(it) && !URL_LIKE.containsMatchIn(it)
}

// "4.Marital Status", "*10.Occupation Type", "Business:", "When were you born?"
private fun looksLikeQuestion(line: OcrLine): Boolean {
    val text = line.text.trim()
    return NUMBERED.containsMatchIn(text) || text.trimEnd('*', ' ').let { it.endsWith(':') || it.endsWith('?') }
}

// Asks the form's own label when Gemma did not pick the line. The user can always say "skip".
private fun askAnyway(line: OcrLine): Question {
    val label = cleanLabel(line.text)
    val id = label.lowercase().replace(NON_WORD, "_").trim('_')
    return Question(id, "text", "फ़ॉर्म में लिखा है: $label. इसका जवाब बताइए।", emptyList(), line.text, line.box)
}

// "*10.Occupation Type:" -> "Occupation Type"
private fun cleanLabel(text: String): String =
    text.trim().trimStart('*').replace(NUMBER_PREFIX, "").trimEnd('*', ':', '?', ' ')

// "Spouse*" and "spouse" compare equal.
private fun norm(text: String): String = text.lowercase().replace(NON_WORD, " ").trim()

private val SPACED_LETTERS = Regex("""^(\p{L}\s+)+\p{L}$""")
private val PAGE_COUNTER = Regex("""^\d+\s*/\s*\d+$""")
private val URL_LIKE = Regex("""https?:|www\.|\.pdf|\.com/|/docs/""", RegexOption.IGNORE_CASE)
private val NUMBERED = Regex("""^\*?\s*\d{1,2}\s*[.)]\s*\p{L}""")
private val NUMBER_PREFIX = Regex("""^\d{1,2}\s*[.)]\s*""")
private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
