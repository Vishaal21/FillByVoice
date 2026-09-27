package com.vishal.fillbyvoice.pipeline

import android.graphics.Rect
import com.vishal.fillbyvoice.log.Log
import com.vishal.fillbyvoice.llm.Gemma
import com.vishal.fillbyvoice.ocr.OcrLine
import com.vishal.fillbyvoice.voice.Language
import org.json.JSONArray
import org.json.JSONException
import kotlin.math.abs

// One question the user must answer, ready for the voice loop.
data class Question(
    val id: String,
    val type: String,
    val askHi: String,
    val askEn: String,
    val options: List<String>,
    val label: String, // the form's own wording, from OCR (shown on the answer sheet)
    val box: Rect, // where the question sits on the photo
)

data class FoundQuestions(val questions: List<Question>, val tokensPerSecond: Double)

// What the phone says, in the chosen language.
fun Question.ask(language: Language): String = when (language) {
    Language.HINDI -> askHi
    Language.ENGLISH -> askEn
}

private const val TAG = "FieldFinder"

// A 2B model stays accurate on short replies, so big pages are sent in chunks of about this many lines.
private const val CHUNK_LINES = 25

private val TYPES = listOf(
    "text", "number", "date", "mobile", "email", "pincode", "aadhaar", "pan", "ifsc", "choice",
)

// With this schema the runtime forces valid JSON, but Gemma is about 2.5x slower (18 vs 46 tok/s).
// So the first try is free and only the retry is forced.
private val SCHEMA = """
{"type":"array","items":{"type":"object","properties":{
"line":{"type":"integer"},
"label":{"type":"string"},
"id":{"type":"string"},
"type":{"type":"string","enum":[${TYPES.joinToString(",") { "\"$it\"" }}]},
"options":{"type":"array","items":{"type":"string"}},
"ask_hi":{"type":"string"}},
"required":["line","label","id"]}}
""".trimIndent()

// System prompt: what the page is, the fields the catalogue knows (so Gemma only names them), and what is
// never a question. The field list is built from BANK_FIELDS, so the prompt and the catalogue always agree.
private val SYSTEM = """
You find the questions a customer must answer on an Indian bank form (account opening, KYC, nomination, deposit, withdrawal, NEFT / RTGS).
The user sends numbered lines of OCR text from a photo of the form, in reading order. OCR may misspell words.

Reply with ONLY a JSON array, on one line. One item per question line:
- A field from the FIELD LIST: {"line":N,"label":"<exact line text>","id":"<id from the list>"}
- Any other question: {"line":N,"label":"<exact line text>","id":"<short snake_case id>","type":"<type>","ask_hi":"<question in Hindi>"}
- Tick boxes: add "options" with the option words printed next to the question.

FIELD LIST (id: meaning):
${BANK_FIELDS.joinToString("\n") { "${it.id}: ${it.about}" }}

type is one of: text, number, date, mobile, email, pincode, aadhaar, pan, ifsc, choice.

Never a question:
- bank names, logos, form titles, form numbers, page numbers
- section headings such as PERSONAL DETAILS, APPLICANT DETAILS, COMMUNICATION ADDRESS, NOMINATION, DECLARATION
- instructions, notes and hints
- declarations and terms ("I/We hereby declare...", "I/We have read...", "I agree...")
- what the bank fills: Customer ID, CIF No., SOL ID, the Account Number box of a new account, anything for office use
- option words beside tick boxes: they go in "options" of their question

Rules:
1. label is the exact text of that input line.
2. A line followed by tick-box option words IS a question, even when it looks like a heading
   (TYPE OF ACCOUNT followed by SAVINGS ACCOUNT, CURRENT ACCOUNT).
3. The same field again, for example a second address: add _2 to the id (city_2).
4. In a nominee section, use the nominee_ ids.
5. ask_hi is a short, polite, complete question in simple Hindi (Devanagari). Never copy the label.
6. No question in the lines: reply [].
""".trimIndent()

// Example chat turns (input lines -> reply). They show the traps seen on real bank forms: bank name, form title,
// heading, Customer ID, option lines, a field not in the list, a nominee section, a declaration, and no questions at all.
private val EXAMPLES = listOf(
    """
0: GRAMIN BANK OF INDIA
1: SAVINGS ACCOUNT OPENING FORM
2: Customer ID
3: Account Number
4: TYPE OF ACCOUNT
5: SAVINGS ACCOUNT
6: CURRENT ACCOUNT
7: PERSONAL DETAILS
8: *Applicant's Full Name:
9: Father's / Husband's Name
10: *Date of Birth
11: Gender
12: Male
13: Female
14: Transgender
15: Occupation:
16: Salaried
17: Self Employed
18: Farmer
19: Name of Employer:
20: Voter ID No.
""".trim() to
        """[{"line":4,"label":"TYPE OF ACCOUNT","id":"account_type","options":["SAVINGS ACCOUNT","CURRENT ACCOUNT"]},{"line":8,"label":"*Applicant's Full Name:","id":"name"},{"line":9,"label":"Father's / Husband's Name","id":"father_name"},{"line":10,"label":"*Date of Birth","id":"dob"},{"line":11,"label":"Gender","id":"gender","options":["Male","Female","Transgender"]},{"line":15,"label":"Occupation:","id":"occupation","options":["Salaried","Self Employed","Farmer"]},{"line":19,"label":"Name of Employer:","id":"employer_name","type":"text","ask_hi":"आप जहाँ काम करते हैं, उस कंपनी या दुकान का नाम क्या है?"},{"line":20,"label":"Voter ID No.","id":"voter_id"}]""",
    """
0: COMMUNICATION ADDRESS
1: *Address:
2: Landmark
3: City / Town / Village:
4: District:
5: State:
6: PIN Code:
7: Mobile No.:
8: Do you want a debit card?
9: Yes
10: NOMINATION
11: Name of Nominee:
12: Relationship with Applicant:
13: Date of Birth (if minor):
14: I/We hereby declare that the details given above are true
15: Place:
16: Date:
""".trim() to
        """[{"line":1,"label":"*Address:","id":"address"},{"line":2,"label":"Landmark","id":"landmark"},{"line":3,"label":"City / Town / Village:","id":"city"},{"line":4,"label":"District:","id":"district"},{"line":5,"label":"State:","id":"state"},{"line":6,"label":"PIN Code:","id":"pincode"},{"line":7,"label":"Mobile No.:","id":"mobile"},{"line":8,"label":"Do you want a debit card?","id":"debit_card","type":"choice","options":["Yes","No"],"ask_hi":"क्या आपको डेबिट कार्ड चाहिए? हाँ या नहीं?"},{"line":11,"label":"Name of Nominee:","id":"nominee_name"},{"line":12,"label":"Relationship with Applicant:","id":"nominee_relation"},{"line":13,"label":"Date of Birth (if minor):","id":"nominee_dob"},{"line":15,"label":"Place:","id":"place"},{"line":16,"label":"Date:","id":"date"}]""",
    """
0: TERMS AND CONDITIONS
1: 1. The account will be opened after the KYC documents are verified.
2: 2. Charges apply as per the schedule of charges.
3: I agree to the terms and conditions of the bank.
""".trim() to "[]",
)

// OCR lines -> Gemma (one call per chunk of the page) -> question list.
// Each chunk retries once, then falls back to simple rules for that chunk only.
// Logs are Info, not debug: this phone drops debug lines (its log level is set to Info).
suspend fun findQuestions(lines: List<OcrLine>, onProgress: (done: Int, total: Int) -> Unit): FoundQuestions {
    val began = System.currentTimeMillis()
    val rows = readingOrder(joinWrapped(lines.filter(::isUseful)))
    val speeds = mutableListOf<Double>()
    val chunks = chunks(rows)
    Log.i(TAG, "Page: ${lines.size} OCR lines, ${rows.sumOf { it.size }} kept after noise and joins, ${chunks.size} chunks")

    // One Gemma call per chunk. If a chunk fails twice, its question-like lines are asked anyway.
    val found = cleanUp(chunks.flatMapIndexed { i, chunk ->
        onProgress(i, chunks.size)
        askGemma(chunk, "${i + 1}/${chunks.size}")?.also { speeds += it.tokensPerSecond }?.questions
            ?: chunk.filter(::worthAsking).map(::askAnyway)
                .also { Log.w(TAG, "Chunk ${i + 1}: no usable reply, its question-like lines are asked anyway") }
    })

    // Not picked by Gemma but worth asking: ask it anyway.
    // (A second Gemma pass over these leftovers was tried: it found nothing real and invented questions from junk.)
    val leftover = unused(rows, found).flatten().filter(::worthAsking).map(::askAnyway)

    val position = rows.flatten().withIndex().associate { (i, line) -> line.box to i }
    val all = cleanUp(withOptionsBelow((found + leftover).sortedBy { position[it.box] }, rows))
    val questions = withoutHeadings(all)
    all.filterNot { it in questions }.forEach { Log.i(TAG, "Drop heading \"${it.label}\": ${it.id} is asked below") }
    // One log line per question: Android cuts a single log entry at about 4 KB.
    Log.i(TAG, "Found ${questions.size} questions in ${seconds(began)} s:")
    questions.forEachIndexed { i, q ->
        Log.i(TAG, "${i + 1}. ${q.id} (${q.type}) \"${q.label}\" | ${q.askHi} | ${q.askEn}")
    }
    return FoundQuestions(questions, speeds.average().takeIf { it.isFinite() } ?: 0.0)
}

// Gemma gives the same reply to the same prompt, so a plain retry repeats the same broken JSON.
// The retry forces the JSON shape instead: slower, but always valid.
private suspend fun askGemma(chunk: List<OcrLine>, number: String): FoundQuestions? {
    val numbered = chunk.withIndex().joinToString("\n") { (i, line) -> "$i: ${line.text}" }
    repeat(2) { attempt ->
        val began = System.currentTimeMillis()
        val reply = Gemma.ask(numbered, SCHEMA.takeIf { attempt > 0 }, SYSTEM, EXAMPLES)
        val how = if (attempt > 0) "retry, forced JSON" else "free-form"
        Log.i(TAG, "Chunk $number in:\n$numbered")
        Log.i(TAG, "Chunk $number reply ($how, ${reply.tokensPerSecond.toInt()} tok/s, ${seconds(began)} s):\n${reply.text}")
        try {
            return FoundQuestions(parse(reply.text, chunk), reply.tokensPerSecond)
        } catch (e: JSONException) {
            Log.w(TAG, "Chunk $number: bad JSON: ${e.message}")
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

// A label printed over 2 or 3 lines ("*Date of" / "Incorporation/" / "Date of Birth:") becomes one line,
// so it is one question. Each new line is checked against the lowest line already joined, not the whole block.
private fun joinWrapped(lines: List<OcrLine>): List<OcrLine> {
    val joined = mutableListOf<OcrLine>()
    val lowest = mutableListOf<OcrLine>()
    for (line in lines.sortedBy { it.box.top }) {
        val i = lowest.indexOfLast { it.continuesInto(line) }
        if (i >= 0) {
            joined[i] = OcrLine("${joined[i].text} ${line.text}", Rect(joined[i].box).apply { union(line.box) })
            lowest[i] = line
        } else {
            joined += line
            lowest += line
        }
    }
    return joined
}

// The upper line stops mid-phrase ("Date of", "Incorporation/"), and the next one has the same text size,
// starts at the same left edge and sits directly below. A big logo or a finished sentence ("...below.)") never joins.
private fun OcrLine.continuesInto(next: OcrLine): Boolean {
    val h = next.box.height()
    return text.trimEnd().lastOrNull()?.let { it.isLetter() || it in "/,-" } == true &&
        !NUMBERED.containsMatchIn(next.text) && !next.text.trimStart().startsWith('*') &&
        abs(box.height() - h) < h / 2 && abs(box.left - next.box.left) < h &&
        next.box.top - box.bottom in -h / 2..h / 2
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
    val text = reply.substring(reply.indexOf('[').coerceAtLeast(0))
    // Gemma's slips in free-form JSON are all in the line number: {"line10,"label"...} (':' missing, every
    // ICICI page 1 run) or {"2223,"label"...} (key missing). The first is repaired, any other broken number is
    // dropped: the label check below still finds the line. This saves the 25 s forced-JSON retry.
    val repaired = BROKEN_LINE.replace(MISSING_COLON.replace(text, "\"line\":$1,"), "{\"label\"")
    if (repaired != text) Log.i(TAG, "  repaired broken line numbers in the reply")
    val json = JSONArray(repaired)
    val printed = lines.map { squash(it.text) }
    return (0 until json.length()).mapNotNull { i ->
        val item = json.getJSONObject(i)
        val id = item.optString("id").ifBlank { "field_$i" }
        val line = findLine(item.optString("label"), item.optInt("line", -1), lines)
        if (line == null) {
            Log.i(TAG, "  drop $id: no OCR line matches \"${item.optString("label")}\"")
            return@mapNotNull null
        }
        // An option word is never its own question: "Gender*" was pointed at the line "Third Gender" and gender was
        // asked twice (09:15 and 09:33 runs, SBI).
        if (isOptionWord(line.text)) {
            Log.i(TAG, "  drop $id: \"${line.text}\" is an option word")
            return@mapNotNull null
        }
        // Every option must be printed on the page, else the choice was invented ("Yes" / "No" on a name field).
        // Tiny words like "No" were already dropped as crumbs, so they are not checked.
        val given = item.optJSONArray("options")?.let { o -> (0 until o.length()).map { o.getString(it) } }.orEmpty()
        // One option is not a choice: Gemma kept "CURRENT ACCOUNT" and listed the other account types as lines.
        val options = given
            .takeIf { it.all { option -> option.length <= 2 || printed.any { line -> squash(option) in line } } }.orEmpty()
            .takeIf { it.size >= 2 }.orEmpty()
            .map(::tidyOption)
        if (options.size < given.size) Log.i(TAG, "  $id: options dropped (not all printed, or only one): $given")

        // 1. A known bank field whose label agrees: the catalogue decides the wording and the type.
        bankField(id, line.text)?.let { return@mapNotNull fieldQuestion(it, id, options, line).logged(id, "catalogue") }
        // 2. Any other question: Gemma's own Hindi, with Kotlin checks. A tick-box question Gemma found without
        // Hindi ("Same as communication address. Yes / No") gets a plain wording; the phraser words it later.
        val askHi = item.optString("ask_hi").takeIf { DEVANAGARI.containsMatchIn(it) }
            ?: withOptions("फ़ॉर्म में लिखा है: ${cleanLabel(line.text)}।", options.map(::hindiOption), "या")
                .takeIf { item.optString("type") == "choice" && options.size >= 2 }
        if (askHi != null) {
            // Printed options make it a choice even when Gemma left out the type ("Occupation Type", 09:33 run).
            val type = if (options.size >= 2) "choice" else checkType(item.optString("type"), line.text, options)
            val askEn = withOptions(cleanLabel(line.text) + "?", options, "or")
            return@mapNotNull Question(id, type, asQuestion(askHi, type), askEn, options, line.text, line.box)
                .logged(id, "Gemma's Hindi")
        }
        // 3. No Hindi and a wrong id ("MAB", or "name" on a "First Name" line): only the label can still save it.
        bankFieldForLabel(line.text)?.let { fieldQuestion(it, it.id, options, line).logged(id, "catalogue, by label") }
            // 4. A field Gemma found that the catalogue does not know, with no Hindi ("Application Type",
            // "Relationship with Guardian", SBI 09:33): kept when its line reads like a real label; the phraser words it.
            ?: line.takeIf { looksLikeLabel(it.text) }?.let(::askAnyway)?.logged(id, "the form's own label")
            ?: null.also { Log.i(TAG, "  drop $id \"${line.text}\": not a catalogue field, no Hindi question") }
    }
}

// One log line per kept question, saying which rule made it.
private fun Question.logged(gemmaId: String, how: String) =
    also { Log.i(TAG, "  keep $gemmaId \"$label\" -> $id ($type), wording from $how") }

private fun seconds(since: Long) = (System.currentTimeMillis() - since) / 100 / 10.0

// Catalogue wording. The form's printed options win over the catalogue's default ones, and any options make it a choice.
private fun fieldQuestion(field: BankField, id: String, printedOptions: List<String>, line: OcrLine): Question {
    val options = printedOptions.ifEmpty { field.options }
    return Question(
        id = field.id + (COPY_SUFFIX.find(id)?.value ?: ""), // "city_2" stays the second city
        type = if (options.isEmpty()) field.type else "choice",
        askHi = withOptions(field.hi, options.map(::hindiOption), "या"),
        askEn = withOptions(field.en, options, "or"),
        options = options,
        label = line.text,
        box = line.box,
    )
}

// Code decides: a strict type needs its keyword in the form's label, else the answer check would reject
// real answers (an Importer Code typed as "pan"). A choice needs its options.
private fun checkType(type: String, label: String, options: List<String>): String = when {
    type !in TYPES -> "text"
    type == "choice" && options.isEmpty() -> "text"
    TYPE_WORDS[type]?.containsMatchIn(label) == false -> "text"
    else -> type
}

// "राज्य:" or "शहर?" -> "राज्य क्या है?", so the phone asks a real question instead of reading out a word.
// Choice questions keep Gemma's wording (they read out the options), and so does a polite request ending in "।".
private fun asQuestion(hindi: String, type: String): String {
    val text = hindi.trim().trim('*', ':', ' ')
    if (type == "choice" || text.endsWith('।') || QUESTION_WORD.containsMatchIn(text)) return text
    return text.trimEnd('?', ' ') + " क्या है?"
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
// Compared without spaces, because OCR splits words ("NON-I KIT" is the option "NON-IKIT").
private fun cleanUp(questions: List<Question>): List<Question> {
    val options = questions.flatMap { it.options }.map(::squash).toSet()
    val unique = questions.distinctBy { it.box }
    if (unique.size < questions.size) Log.i(TAG, "Drop ${questions.size - unique.size} repeats of the same line")
    return unique.filterNot { q ->
        (squash(q.label) in options).also { if (it) Log.i(TAG, "Drop \"${q.label}\": an option of another question") }
    }
}

// The same field twice: a heading and the blank under it were both asked as the address ("COMMUNICATION ADDRESS"
// then "*Particulars :" at 07:26 and 07:49, "REGISTERED ADDRESS ..." and "*Particulars" at 07:41 with a question
// between; Gemma sometimes numbers them apart, address_2 then address). A heading (no ":", mostly capitals) goes
// when the same kind of field comes later. A real blank is never dropped, so two address blocks both stay.
internal fun withoutHeadings(questions: List<Question>): List<Question> = questions.filterIndexed { i, q ->
    !(isHeading(q.label) && questions.drop(i + 1).any { kind(it.id) == kind(q.id) })
}

private fun isHeading(label: String) = ':' !in label && label.count(Char::isUpperCase) > label.count(Char::isLowerCase)

private fun kind(id: String) = id.replace(COPY_SUFFIX, "")

// A question with its tick-box options printed on the row below (TYPE OF ACCOUNT, then CURRENT ACCOUNT, EEFC,
// SPECIAL SAVING ACCOUNT, OTHERS on one row). Gemma often lists those as lines of their own instead of options,
// so code reads them off the page: the next row, when it holds 2+ short lines that are not questions.
// A label ending in ":" ("Branch :", "Product Name :") is a blank to write in, never a tick-box heading
// (07:19 run: "Branch:" got the declaration below it as options).
private fun withOptionsBelow(questions: List<Question>, rows: List<List<OcrLine>>): List<Question> {
    val used = questions.map { it.box }.toSet()
    return questions.map { q ->
        if (q.options.isNotEmpty() || q.type != "text" || q.label.trimEnd('*', ' ').endsWith(':') || !mayBeChoice(q.id)) {
            return@map q
        }
        val row = rows.indexOfFirst { r -> r.any { it.box == q.box } }
        if (row < 0) return@map q
        // On the label's own line, to its right ("Account type [ ] Normal [ ] Small [ ] Minor [ ] Staff", SBI 09:33),
        // else on the row below. A field label there ("PF NO.") is not an option.
        val beside = rows[row].filter { it.box.left > q.box.right }
        val found = listOf(beside, rows.getOrNull(row + 1).orEmpty())
            .map { r -> r.filterNot { LABEL_END.containsMatchIn(it.text) } }
            .firstOrNull { r -> r.size >= 2 && r.all { it.box !in used && it.text.trim().split(Regex("\\s+")).size <= 6 } }
            ?: return@map q
        val options = found.map { tidyOption(it.text) }
        Log.i(TAG, "Options for \"${q.label}\" read from the page: $options")
        q.copy(
            type = "choice",
            options = options,
            askHi = withOptions(q.askHi, options.map(::hindiOption), "या"),
            askEn = withOptions(q.askEn, options, "or"),
        )
    }
}

// Rows with only the lines that are not yet a question and not an option word.
private fun unused(rows: List<List<OcrLine>>, questions: List<Question>): List<List<OcrLine>> {
    val used = questions.map { it.box }.toSet()
    val options = questions.flatMap { it.options }.map(::squash).toSet()
    return rows.map { row -> row.filter { it.box !in used && squash(it.text) !in options } }.filter { it.isNotEmpty() }
}

// Drops crumbs ("A", "M"), box hints made of spaced letters ("T N", "M I D D L E N"), hints in brackets
// ("(Maximum 40 characters)"), sideways margin text (its tall box would pull half the page into one row),
// bank form boilerplate that is never a question ("FIELDS WITH * ARE MANDATORY", "use BLACK INK"),
// and screen junk when a form is photographed off a laptop (page counter "1 / 10", zoom "100%", URLs).
private fun isUseful(line: OcrLine): Boolean = line.text.trim().let {
    it.length > 2 && line.box.width() > line.box.height() && !SPACED_LETTERS.matches(it) &&
        !IN_BRACKETS.matches(it) && !NUMBERS_ONLY.matches(it) && !URL_LIKE.containsMatchIn(it) &&
        !BOILERPLATE.containsMatchIn(it)
}

// "4.Marital Status", "*10.Occupation Type", "Business:", "When were you born?"
// A long numbered line is a terms-and-conditions clause ("1. The bank may close the account..."), not a question.
private fun looksLikeQuestion(line: OcrLine): Boolean {
    val text = line.text.trim()
    val words = text.split(Regex("\\s+")).size
    return (NUMBERED.containsMatchIn(text) || endsLikeQuestion(text)) && (words <= 8 || text.endsWith('?'))
}

// A missed line is still asked if it looks like a question, or if it is a short line naming a known bank field
// even without ":" ("TYPE OF ACCOUNT", "Joint Applicant", "Mobile").
private fun worthAsking(line: OcrLine): Boolean = !isOptionWord(line.text) &&
    (looksLikeQuestion(line) || (line.text.trim().split(Regex("\\s+")).size <= 6 && bankFieldForLabel(line.text) != null))

// "Application Type", "Relationship with Guardian": 2 to 5 words starting with a capital, not a heading in capitals
// ("PSB", "CUSTOMER INFORMATION SHEET"), not a sentence piece ("name and code no."), not an option word, not a
// section ("A. Personal Details") and not a box the bank fills ("Customer ID", "CKYC No.").
// A label ends on a word, not mid-phrase: "Across Nation wvith Convenience &" is the PSB logo (09:47 run).
internal fun looksLikeLabel(text: String): Boolean {
    val label = cleanLabel(text)
    return label.split(Regex("\\s+")).size in 2..5 && label.first().isUpperCase() && !isHeading(label) &&
        (label.last().isLetterOrDigit() || label.last() in ").") && !isOptionWord(text) &&
        !NOT_ASKED.containsMatchIn(label)
}

// "OTHERS:", "Yes", "CURRENT ACCOUNT": a tick-box word printed on its own is never a question.
private fun isOptionWord(text: String): Boolean = cleanLabel(text).let { hindiOption(it) != it }

private fun endsLikeQuestion(text: String): Boolean =
    text.trim().trimEnd('*', ' ').let { it.endsWith(':') || it.endsWith('?') }

// A line Gemma did not pick. A safe catalogue field ("*PAN OF APPLICANT:" is the PAN) gets its checked wording;
// anything else asks the form's own label, with the type from keywords. The user can always say "skip".
private fun askAnyway(line: OcrLine): Question {
    bankFieldForLabel(line.text)?.let {
        Log.i(TAG, "Ask anyway \"${line.text}\" -> ${it.id}, catalogue wording")
        return fieldQuestion(it, it.id, emptyList(), line)
    }
    val label = cleanLabel(line.text)
    val id = label.lowercase().replace(NON_WORD, "_").trim('_')
    val type = TYPE_WORDS.entries.firstOrNull { it.value.containsMatchIn(label) }?.key ?: "text"
    Log.i(TAG, "Ask anyway \"${line.text}\" -> $id ($type), the form's own label")
    return Question(id, type, "फ़ॉर्म में लिखा है: $label. इसका जवाब बताइए।", "$label?", emptyList(), line.text, line.box)
}

// "*10.Occupation Type:" -> "Occupation Type"
internal fun cleanLabel(text: String): String =
    text.trim().trimStart('*').replace(NUMBER_PREFIX, "").trimEnd('*', ':', '?', ' ')

// "Spouse*" and "spouse" compare equal.
private fun norm(text: String): String = text.lowercase().replace(NON_WORD, " ").trim()

// OCR drops spaces ("CURRENTACCOUNT"), so options are compared with no spaces at all.
internal fun squash(text: String): String = norm(text).replace(" ", "")

// Word starts (\b) so "Candidate" is not a date and "Shipping" is not a PIN.
private val TYPE_WORDS = mapOf(
    "date" to """\bdate|birth|\bborn|\bdob\b""",
    "mobile" to """mobile|\bcell|(?<!tele)phone""",
    "email" to """e-?mail""",
    "pincode" to """\bpin\b|pincode|postal""",
    "aadhaar" to """aadha|\buid\b""",
    "pan" to """\bpan\b|permanent account""",
    "ifsc" to "ifsc",
).mapValues { Regex(it.value, RegexOption.IGNORE_CASE) }

private val DEVANAGARI = Regex("[ऀ-ॿ]")
private val QUESTION_WORD = Regex("क्या|कौन|कब|कहाँ|कहां|कितन|कैसे|किस|बताइए|बताएं")
private val SPACED_LETTERS = Regex("""^(\p{L}\s+)+\p{L}$""")
private val IN_BRACKETS = Regex("""^\(.*\)$""")
private val NUMBERS_ONLY = Regex("""^\d+\s*(/\s*\d+)?\W*$""")
private val BOILERPLATE = Regex(
    "mandatory|capital letters|block letters|black ink|please tick|strike off|whichever is not applicable|" +
        "overleaf|office use|bank use|to be filled by|signature|thumb impression|affix|photograph|" +
        "branch manager|authori[sz]ed signatory|verified by|branch code|form 49a|form 60",
    RegexOption.IGNORE_CASE,
)
private val URL_LIKE = Regex("""https?:|www\.|\.pdf|\.com/|/docs/""", RegexOption.IGNORE_CASE)
private val NUMBERED = Regex("""^\*?\s*\d{1,2}\s*[.)]\s*\p{L}""")
// OCR reads "1." as "1," too ("1,Name*:", 10:12 run, SBI: the name was asked as "फ़ॉर्म में लिखा है: 1,Name").
private val NUMBER_PREFIX = Regex("""^\d{1,2}\s*[.,)]\s*""")
private val MISSING_COLON = Regex(""""line(\d+),""")
private val BROKEN_LINE = Regex("""\{(?!"line":\d+,)[^{}]*?"label"""")
private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
private val LABEL_END = Regex("""\b(no|id)\.?\s*$""", RegexOption.IGNORE_CASE)
// Boxes the bank fills on a new account (an "Account No." Gemma names as a known field is still asked), and sections.
private val NOT_ASKED = Regex(
    """customer\s*id|ckyc|\bcif\b|sol\s*id|pf\s*no|account\s*no|details|information|^[A-Z]\.\s""",
    RegexOption.IGNORE_CASE,
)
