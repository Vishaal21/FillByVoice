package com.vishal.fillbyvoice.pipeline

import com.vishal.fillbyvoice.log.Log
import com.vishal.fillbyvoice.llm.Gemma
import com.vishal.fillbyvoice.validate.Checked
import com.vishal.fillbyvoice.validate.check
import com.vishal.fillbyvoice.voice.Language
import java.text.Normalizer
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

private const val TAG = "Normaliser"

// Spoken answer -> checked value, for one question. Rules first; Gemma only reads what the rules could not
// (a date or number said in words, a Hindi email, an option said another way). Gemma's value is checked too.
suspend fun understand(question: Question, heard: String): Checked {
    if (question.type == "choice") {
        val option = matchOption(heard, question.options) ?: gemmaOption(heard, question.options)
        Log.i(TAG, "${question.id}: \"$heard\" -> option ${option ?: "none"}")
        return option?.let { Checked.Ok(it) }
            ?: Checked.Wrong("यह विकल्पों में से नहीं है।", "That is not one of the options.")
    }
    // "My full name is Vishal Singh" -> "Vishal Singh": Gemma cuts the lead-in words of a longer text answer.
    if (question.type == "text" && words(heard, minLength = 1).size >= 3) {
        gemmaAnswer(question, heard)?.let { return check("text", it) }
    }
    val cleaned = clean(question.type, heard)
    val result = check(question.type, cleaned)
    Log.i(TAG, "${question.id} (${question.type}): \"$heard\" -> \"$cleaned\" -> $result")
    if (result is Checked.Ok || !gemmaMayHelp(question.type, cleaned)) return result
    val value = gemmaValue(question, heard) ?: return result
    // Code decides: Gemma must keep the year that was said. It turned "third number 1865" into 18/06/2024 (09:47 run).
    if (YEAR.findAll(heard).any { it.value !in value }) {
        Log.i(TAG, "Gemma's \"$value\" refused: a year said in \"$heard\" is missing")
        return result
    }
    return (check(question.type, value) as? Checked.Ok) ?: result
}

// Rules only, no model: Devanagari digits and digit words to 0-9, "double 9" to 99, spoken letter names
// to A-Z, month names to numbers, "at the rate" / "dot" in emails.
internal fun clean(type: String, heard: String): String {
    val text = heard.map { if (it in '०'..'९') '0' + (it - '०') else it }.joinToString("")
    return when (type) {
        "aadhaar", "mobile", "pincode" -> digitWords(text).filter { it in '0'..'9' || it == '+' }
        // Words go, but a decimal point stays so the check refuses it instead of silently making 12.5 into 125.
        "number" -> digitWords(text).replace(Regex("[^0-9.,]"), "")
        "pan", "ifsc" -> letterNames(digitWords(text))
        "date" -> date(digitWords(text))
        "email" -> email(text)
        else -> text.trim()
    }
}

// Gemma is asked only where it can add something the user really said: never for digits already heard as
// digits (it could invent or change one), always for dates and emails (the check and the read-back catch mistakes).
private fun gemmaMayHelp(type: String, cleaned: String): Boolean = when (type) {
    "date", "email" -> true
    "pan", "ifsc" -> DEVANAGARI.containsMatchIn(cleaned)
    "aadhaar", "mobile", "pincode", "number" -> cleaned.none { it in '0'..'9' }
    else -> false
}

private fun digitWords(text: String): String {
    var times = 1
    return text.split(Regex("""\s+""")).mapNotNull { word ->
        val key = plain(word).trim(',', '.', '।', '?')
        REPEAT[key]?.let { times = it; return@mapNotNull null }
        val digits = DIGIT_WORDS[key] ?: word
        val repeated = if (times > 1 && digits.firstOrNull() in '0'..'9') {
            digits.first().toString().repeat(times) + digits.drop(1)
        } else {
            digits
        }
        times = 1
        repeated
    }.joinToString(" ")
}

private fun letterNames(text: String): String =
    text.split(Regex("""\s+""")).joinToString(" ") { LETTER_NAMES[plain(it).trim(',', '.', '।')] ?: it }

// "25 March 1990", "25th of march 1990", "twenty fifth March 1990", "25 मार्च 1990", "25031990" -> "25/03/1990".
// Anything else is left as it is.
private fun date(text: String): String {
    val parts = mutableListOf<String>()
    var tens = 0 // "twenty" waiting for "third"
    for (word in words(text, minLength = 1)) {
        val w = ORDINAL.matchEntire(word)?.groupValues?.get(1) ?: word
        if (w in TENS) {
            tens = TENS.getValue(w)
            continue
        }
        val ordinal = ORDINAL_WORDS[w]
        if (tens > 0 && ordinal == null) parts += tens.toString()
        val part = if (ordinal != null) (tens + ordinal).toString()
        else monthNumber(w)?.toString() ?: w.takeIf { it.all { c -> c in '0'..'9' } }
        tens = 0
        part?.let { parts += it }
    }
    if (tens > 0) parts += tens.toString()
    val one = parts.singleOrNull()
    return when {
        parts.size == 3 -> parts.joinToString("/")
        one != null && one.length == 8 -> "${one.take(2)}/${one.substring(2, 4)}/${one.drop(4)}"
        else -> text
    }
}

private fun monthNumber(word: String): Int? {
    // The English recognizer hears "November" as "number" ("23rd number 1954" at 08:08, "third number 1865" at 09:47).
    if (word == "number") return 11
    val english = ENGLISH_MONTHS.indexOfFirst { word.length >= 3 && word.all(Char::isLetter) && word.startsWith(it) }
    if (english >= 0) return english + 1
    return HINDI_MONTH_WORDS[word]
}

// "ramesh dot kumar at the rate gmail dot com" -> "ramesh.kumar@gmail.com". Hindi words are left for Gemma.
private fun email(text: String): String = text.lowercase()
    .replace(Regex("""\bat\s+the\s+rate(\s+of)?\b"""), "@")
    .replace(Regex("""\bat\b"""), "@")
    .replace(Regex("""\bdot\b"""), ".")
    .replace(Regex("""\bunderscore\b"""), "_")
    .replace(Regex("""\s+"""), "")

// "It is current account" -> "CURRENT ACCOUNT", "महिला" -> "Female". Each option is matched by its words
// (as printed + its Hindi name). A word shared by several options counts less ("account").
// Same points: only an option said in full wins ("ikit" is IKIT, not NON-IKIT); otherwise no guess.
internal fun matchOption(heard: String, options: List<String>): String? {
    val said = words(heard).map(::stem).toSet()
    val optionWords = options.map { (words(it) + words(hindiOption(it))).map(::stem).toSet() }
    val spread = optionWords.flatten().groupingBy { it }.eachCount()
    fun weight(word: String) = 1.0 / spread.getValue(word)
    data class Score(val option: String, val points: Double, val full: Boolean)
    val scores = options.indices.map { i ->
        val points = optionWords[i].filter { it in said }.sumOf(::weight)
        Score(options[i], points, optionWords[i].isNotEmpty() && optionWords[i].all { it in said })
    }.filter { it.points > 0 }
    val best = scores.maxByOrNull { it.points } ?: return null
    val tied = scores.filter { abs(it.points - best.points) < 1e-9 }
    return if (tied.size == 1) best.option else tied.singleOrNull { it.full }?.option
}

// "savings" and "saving", "others" and "other" are the same option word.
private fun stem(word: String): String =
    if (word.length > 3 && word.endsWith('s') && word[0] in 'a'..'z') word.dropLast(1) else word

// "हाँ जी", "सही है", "yes" -> true. "नहीं", "गलत", "सही नहीं है" -> false (a no word wins). Anything else -> null.
fun yesOrNo(heard: String?): Boolean? {
    val said = words(heard ?: return null)
    return when {
        said.any { it in NO_WORDS } -> false
        said.any { it in YES_WORDS } -> true
        else -> null
    }
}

// "छोड़ो", "छोड़ दीजिए", "skip": the user does not want to answer this one.
fun isSkip(heard: String): Boolean = words(heard).any { it.startsWith("छोड") || it in SKIP_WORDS }

// "No", "नहीं है", "none" as the answer to a question that is not a choice: the user has none
// (no joint holder, no fax number). Saved as skipped, never as the name "No no".
fun saysNone(heard: String): Boolean {
    val said = words(heard)
    return said.size <= 3 && (yesOrNo(heard) == false || said.any { it in NONE_WORDS })
}

// "I am done", "that's all", "बस करो", "बस", "हो गया": the user stops the form here. A phrase counts anywhere
// (07:26 run: "I am done he can now fill the form" was saved as the fax number); a short word only at the end of
// a reply of 3 words or less, so "I have done MBA", "बस स्टैंड" and "मैं रिटायर हो गया हूँ" stay answers.
fun isFinish(heard: String): Boolean {
    val text = words(heard, minLength = 1).joinToString(" ")
    return FINISH_ANYWHERE.any { " $it " in " $text " } ||
        (text.split(" ").size <= 3 && FINISH_AT_END.any { text == it || text.endsWith(" $it") })
}

// "The product name should be", "Street is": the recognizer stopped at a pause and the answer goes on.
// Hindi ends a full sentence with "है", so only "का / की / के / और / या" count there.
fun endsMidPhrase(heard: String): Boolean = words(heard, minLength = 1).lastOrNull() in DANGLING

// "It is 28th November", then a pause: the year is still to come (07:49 run: the date was refused).
fun missingYear(heard: String): Boolean = !YEAR.containsMatchIn(clean("date", heard))

// "मोबाइल गलत है", "change the date of birth" -> which answer to change. Each field has a few names (short Hindi,
// short English, the form's label). The field whose name was said most completely wins, then the one with more of
// its words said ("date of birth" is the birth date, not the signing date). A tie: no guess.
fun pickField(heard: String, names: List<List<String>>): Int? {
    val said = words(heard).map(::stem).toSet()
    val byShare = compareBy<Pair<Double, Int>>({ it.first }, { it.second })
    val scores = names.map { variants ->
        variants.map { name ->
            val want = words(name).map(::stem).toSet()
            val hit = want.count { it in said }
            (if (want.isEmpty()) 0.0 else hit.toDouble() / want.size) to hit
        }.maxWithOrNull(byShare) ?: (0.0 to 0)
    }
    val best = scores.maxWithOrNull(byShare)?.takeIf { it.second > 0 } ?: return null
    return scores.indices.singleOrNull { scores[it] == best }
}

// "तीसरा", "थर्ड वाले में", "the third one", "3", "आखिरी": the answer at that place in the numbered list on screen
// (skipped ones count, as on screen). 10:14 run: "नहीं थर्ड वाले में कुछ दिक्कत है" and "तीसरा" named no field, so
// nothing was changed. A number only as digits, and only one: "कोई एक गलत है" (any one is wrong) names no place.
fun pickPosition(heard: String, count: Int): Int? {
    val said = words(heard, minLength = 1)
    val place = said.firstNotNullOfOrNull { word ->
        ORDINAL_WORDS[word] ?: SPOKEN_PLACES[word] ?: ORDINAL.matchEntire(word)?.groupValues?.get(1)?.toInt()
            ?: HINDI_PLACES.entries.firstOrNull { word.startsWith(it.key) }?.value
            ?: count.takeIf { word in LAST_WORDS }
    } ?: said.filter { word -> word.all(Char::isDigit) }.singleOrNull()?.toIntOrNull()
    return place?.takeIf { it in 1..count }?.minus(1)
}

// "What is this?", "Can you explain more", "यह क्या है?", "फिर से बोलिए": the user is asking back, not answering.
fun isAskingBack(heard: String): Boolean {
    val said = words(heard)
    return said.firstOrNull() in ASK_START || said.any { it in ASK_WORDS || it.startsWith("समझ") } ||
        plain(heard).contains("फिर से")
}

// "25/03/1990" -> "25 मार्च 1990" / "25 March 1990", so the phone reads the month as a word.
fun spokenDate(date: String, language: Language): String {
    val (day, month, year) = date.split("/").map(String::toInt)
    val name = if (language == Language.HINDI) HINDI_MONTHS[month - 1]
    else Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    return "$day $name $year"
}

private val VALUE_SYSTEM = """
You turn a customer's spoken answer on an Indian bank form into the exact value to write.
Reply with ONLY the value, nothing else. If the answer has no such value, reply NONE.
""".trimIndent()

private val VALUE_RULES = mapOf(
    "date" to "a date as DD/MM/YYYY",
    "mobile" to "a 10-digit mobile number, digits only",
    "aadhaar" to "a 12-digit Aadhaar number, digits only",
    "pincode" to "a 6-digit PIN code, digits only",
    "number" to "a number, digits only",
    "email" to "an email address in English letters",
    "pan" to "a PAN: 5 capital letters, 4 digits, 1 capital letter",
    "ifsc" to "an IFSC code: 4 capital letters, the digit 0, then 6 capital letters or digits",
)

private val VALUE_EXAMPLES = listOf(
    "Field: Date of Birth\nWrite: a date as DD/MM/YYYY\nSpoken: पच्चीस मार्च उन्नीस सौ नब्बे" to "25/03/1990",
    "Field: E-mail ID\nWrite: an email address in English letters\nSpoken: रमेश डॉट कुमार एट जीमेल डॉट कॉम" to
        "ramesh.kumar@gmail.com",
    "Field: Annual Income\nWrite: a number, digits only\nSpoken: ढाई लाख रुपये" to "250000",
)

private suspend fun gemmaValue(question: Question, heard: String): String? = try {
    val prompt = "Field: ${question.label}\nWrite: ${VALUE_RULES[question.type]}\nSpoken: $heard"
    Gemma.ask(prompt, system = VALUE_SYSTEM, examples = VALUE_EXAMPLES).text.trim()
        .takeUnless { it.isEmpty() || it.equals("NONE", ignoreCase = true) || it.equals("null", ignoreCase = true) }
        .also { Log.i(TAG, "Gemma read \"$heard\" as \"$it\"") }
} catch (e: Exception) {
    if (e is CancellationException) throw e
    Log.w(TAG, "Gemma could not read \"$heard\"", e)
    null
}

private val ANSWER_SYSTEM = """
A customer answered a question on an Indian bank form by voice. Reply with ONLY the part of the answer that goes
on the form, in the customer's own words and spelling. Drop lead-in words like "my name is", "it is", "I want to",
"मेरा नाम ... है". Never add, translate or correct words.
""".trimIndent()

// Hindi examples too: "सेविंग्स खाता है मेरा" came back uncut (09:33 run) when Gemma saw an English question.
private val ANSWER_EXAMPLES = listOf(
    "Question: What is your full name?\nSpoken: my full name is Vishal Singh" to "Vishal Singh",
    "Question: Which bank branch?\nSpoken: I want to open it in Kondapur Hyderabad" to "Kondapur Hyderabad",
    "Question: आपका शहर, कस्बा या गाँव कौन सा है?\nSpoken: मैं हैदराबाद में रहता हूँ" to "हैदराबाद",
    "Question: What is your occupation?\nSpoken: I am a farmer" to "farmer",
    "Question: आपका पूरा नाम क्या है?\nSpoken: मेरा नाम रमेश कुमार है" to "रमेश कुमार",
    "Question: बैंक की किस शाखा में? शाखा का नाम बताइए।\nSpoken: मुझे कोंडापुर शाखा में खाता खोलना है" to "कोंडापुर",
    "Question: आप क्या काम करते हैं?\nSpoken: मैं तो किसान हूँ जी" to "किसान",
)

// Code decides: Gemma's cut is kept only if every word in it was really said, so nothing is invented.
// The question goes in the answer's own language, so a Hindi answer is read against a Hindi question.
private suspend fun gemmaAnswer(question: Question, heard: String): String? = try {
    val asked = if (DEVANAGARI.containsMatchIn(heard)) question.askHi else question.askEn
    val reply = Gemma.ask("Question: $asked\nSpoken: $heard", system = ANSWER_SYSTEM, examples = ANSWER_EXAMPLES)
        .text.trim().trim('"', '.', ' ')
    val said = words(heard, minLength = 1).toSet()
    val kept = reply.takeIf { it.isNotEmpty() && words(it, minLength = 1).all { word -> word in said } }
    Log.i(TAG, "Gemma cut \"$heard\" to \"$reply\": ${if (kept != null) "kept" else "refused, words not said"}")
    kept
} catch (e: Exception) {
    if (e is CancellationException) throw e
    Log.w(TAG, "Gemma could not cut \"$heard\"", e)
    null
}

private suspend fun gemmaOption(heard: String, options: List<String>): String? = try {
    val list = options.withIndex().joinToString("\n") { (i, option) -> "${i + 1}. $option" }
    val reply = Gemma.ask(
        "Options:\n$list\nThe customer said: $heard\n" +
            "Which option did the customer choose? Reply with only its number, or 0 if none fits."
    ).text
    // Code decides: only a number that names a real option counts.
    Regex("""\d+""").find(reply)?.value?.toIntOrNull()?.let { options.getOrNull(it - 1) }
        .also { Log.i(TAG, "Gemma matched \"$heard\" to ${it ?: "no option"} (reply \"$reply\")") }
} catch (e: Exception) {
    if (e is CancellationException) throw e
    Log.w(TAG, "Gemma could not match \"$heard\"", e)
    null
}

// Which script the form is printed in, from its own labels: more English letters than Hindi ones = an English form.
fun printedInEnglish(labels: List<String>): Boolean {
    val text = labels.joinToString(" ")
    return text.count { it in 'a'..'z' || it in 'A'..'Z' } > text.count { it in 'ऀ'..'ॿ' }
}

private val SPELL_SYSTEM = """
Write the customer's Hindi answer on an Indian bank form in English letters, the way Indians usually spell it.
Spell by sound, never translate. Keep every word and every number, in the same order.
Reply with ONLY the answer in English letters.
""".trimIndent()

private val SPELL_EXAMPLES = listOf(
    "विशाल सिंह" to "Vishal Singh",
    "कोंडापुर हैदराबाद" to "Kondapur Hyderabad",
    "मकान नंबर 12 गांधी नगर" to "Makan Number 12 Gandhi Nagar",
    "सुनीता देवी" to "Sunita Devi",
    "किसान" to "Kisan",
)

// Hindi spoken on an English form (built 12:19): "विशाल सिंह" -> "Vishal Singh", by sound, never translated, because the bank
// reads English. Gemma spells it; code keeps it only if it passes the check below. Null: the Hindi answer stays.
suspend fun inEnglishLetters(hindi: String): String? {
    if (!DEVANAGARI.containsMatchIn(hindi)) return null
    return try {
        val reply = Gemma.ask(hindi, system = SPELL_SYSTEM, examples = SPELL_EXAMPLES).text.trim().trim('"', '.', ' ')
        reply.takeIf { sameAnswerInEnglish(hindi, it) }
            .also { Log.i(TAG, "Gemma spelled \"$hindi\" as \"$reply\": ${if (it != null) "kept" else "refused"}") }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "Gemma could not spell \"$hindi\" in English letters", e)
        null
    }
}

// Code decides: only English letters, the same number of words, and every number kept ("मकान 12" -> "Makan 12").
internal fun sameAnswerInEnglish(hindi: String, english: String): Boolean {
    fun count(text: String) = text.trim().split(Regex("""\s+""")).size
    fun numbers(text: String) = text.filter(Char::isDigit).map(Char::digitToInt)
    return ENGLISH_SPELLING.matches(english) && count(english) == count(hindi) && numbers(english) == numbers(hindi)
}

private val ENGLISH_SPELLING = Regex("""[A-Za-z0-9][A-Za-z0-9 .,'/-]*""")

// "एप्लीकेशन टाइप" for the form's "Application Type" (10:14 run): the rules match a name letter by letter, so a label
// said in the other script is missed. Gemma picks the answer's number; code keeps only one that is on the list.
suspend fun gemmaField(heard: String, names: List<List<String>>): Int? = try {
    val list = names.withIndex().joinToString("\n") { (i, variants) -> "${i + 1}. ${variants.distinct().joinToString(" / ")}" }
    val reply = Gemma.ask(
        "Answers on the form:\n$list\nThe customer said: $heard\n" +
            "Which answer does the customer want to change? Reply with only its number, or 0 if none fits."
    ).text
    Regex("""\d+""").find(reply)?.value?.toIntOrNull()?.takeIf { it in 1..names.size }?.minus(1)
        .also { Log.i(TAG, "Gemma picked answer ${it?.plus(1) ?: "none"} for \"$heard\" (reply \"$reply\")") }
} catch (e: Exception) {
    if (e is CancellationException) throw e
    Log.w(TAG, "Gemma could not pick the answer for \"$heard\"", e)
    null
}

// Lowercase, and one spelling for Hindi words that speech writes in two ways: "छोड़ो" with or without the
// nukta dot, "हाँ" or "हां".
private fun plain(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace("़", "").replace('ँ', 'ं')

// Letters, Hindi vowel signs and digits make a word; everything else splits.
internal fun words(text: String, minLength: Int = 2): List<String> =
    plain(text).split(Regex("""[^\p{L}\p{M}\p{N}]+""")).filter { it.length >= minLength }

private fun Map<String, String>.plainKeys() = mapKeys { plain(it.key) }

private val DIGIT_WORDS = mapOf(
    "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
    "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
    "शून्य" to "0", "ज़ीरो" to "0", "एक" to "1", "दो" to "2", "तीन" to "3", "चार" to "4",
    "पाँच" to "5", "छह" to "6", "छः" to "6", "छे" to "6", "सात" to "7", "आठ" to "8", "नौ" to "9",
).plainKeys()

private val REPEAT = mapOf("double" to 2, "triple" to 3, "डबल" to 2, "ट्रिपल" to 3).mapKeys { plain(it.key) }

private val LETTER_NAMES = mapOf(
    "ए" to "A", "बी" to "B", "सी" to "C", "डी" to "D", "ई" to "E", "एफ" to "F", "जी" to "G", "एच" to "H",
    "आई" to "I", "जे" to "J", "के" to "K", "एल" to "L", "एम" to "M", "एन" to "N", "ओ" to "O", "पी" to "P",
    "क्यू" to "Q", "आर" to "R", "एस" to "S", "टी" to "T", "यू" to "U", "वी" to "V", "डब्ल्यू" to "W",
    "एक्स" to "X", "वाई" to "Y", "ज़ेड" to "Z",
).plainKeys()

private val ENGLISH_MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
private val HINDI_MONTHS = listOf(
    "जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर",
)
private val HINDI_MONTH_WORDS = HINDI_MONTHS.withIndex().associate { (i, name) -> plain(name) to i + 1 } +
    mapOf("अप्रेल" to 4, "सितम्बर" to 9, "अक्तूबर" to 10, "नवम्बर" to 11, "दिसम्बर" to 12).mapKeys { plain(it.key) }

private val ORDINAL = Regex("""(\d{1,2})(st|nd|rd|th)""")
private val TENS = mapOf("twenty" to 20, "thirty" to 30)
private val ORDINAL_WORDS = listOf(
    "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth", "eleventh",
    "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth",
    "twentieth",
).withIndex().associate { (i, word) -> word to i + 1 } + ("thirtieth" to 30)
private val YEAR = Regex("""\d{4}""")

// Places in the read-back list: English said in Hindi letters ("थर्ड", 10:14 run), Hindi by the start of the word
// ("तीसरा / तीसरे / तीसरी"), and the last one.
private val SPOKEN_PLACES = mapOf("फर्स्ट" to 1, "सेकंड" to 2, "सेकेंड" to 2, "थर्ड" to 3, "फोर्थ" to 4, "फिफ्थ" to 5)
    .mapKeys { plain(it.key) }
private val HINDI_PLACES = listOf("पहल", "दूसर", "तीसर", "चौथ", "पांचव", "छठ", "सातव", "आठव", "नौव", "दसव")
    .withIndex().associate { (i, stem) -> plain(stem) to i + 1 }
private val LAST_WORDS = setOf("last", "लास्ट", "आख़िरी", "आखिरी").map(::plain).toSet()

private val NO_WORDS = setOf("नहीं", "नही", "ना", "ग़लत", "नो", "no", "nope", "not", "wrong").map(::plain).toSet()
private val YES_WORDS = setOf(
    "हाँ", "हा", "हान", "जी", "सही", "ठीक", "बिल्कुल", "ओके", "यस",
    "yes", "yeah", "yep", "yup", "ya", "right", "correct", "ok", "okay", "haan", "han",
).map(::plain).toSet()
private val SKIP_WORDS = setOf("skip", "स्किप")
// "बहुत हुआ", "बंद करो", "रोक दो": 09:20 run, "बस करो, आज का बहुत हुआ" lost its "बस" and was saved as an answer.
// Not "stop": "bus stop" is a landmark.
private val FINISH_ANYWHERE = listOf(
    "i am done", "i'm done", "im done", "we are done", "that's all", "thats all", "that is all", "बस करो", "खत्म",
    "बहुत हुआ", "बंद करो", "रोक दो",
).map { words(it, minLength = 1).joinToString(" ") }
private val FINISH_AT_END = listOf("done", "finish", "finished", "बस", "हो गया", "हो गए")
    .map { words(it, minLength = 1).joinToString(" ") }
private val DANGLING = setOf(
    "is", "are", "was", "be", "the", "an", "and", "or", "of", "my", "to", "at", "in", "with", "का", "की", "के", "और", "या",
).map(::plain).toSet()
private val NONE_WORDS = setOf("none", "nothing", "nil")
private val ASK_START = setOf("what", "which", "why", "how", "can", "could")
// "what" anywhere, "means", "elaborate": 08:07 run saved "Valu means what exactly can you elaborate" as the answer.
// "कैसे", "एग्जांपल": 09:25 run saved "कैसे प्रकार मुझे एग्जांपल दो" (what kinds? give an example).
private val ASK_WORDS = setOf(
    "क्या", "मतलब", "दोबारा", "कैसे", "एग्जांपल", "उदाहरण", "meaning", "mean", "means", "explain", "elaborate",
    "understand", "repeat", "again", "pardon", "what", "example", "options",
).map(::plain).toSet()

private val DEVANAGARI = Regex("[ऀ-ॿ]")
