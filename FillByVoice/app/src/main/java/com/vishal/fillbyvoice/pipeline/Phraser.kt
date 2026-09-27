package com.vishal.fillbyvoice.pipeline

import com.vishal.fillbyvoice.log.Log
import com.vishal.fillbyvoice.llm.Gemma
import com.vishal.fillbyvoice.voice.Language
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "Phraser"

// What the phone does with one question: speak this text, or drop a line that is not a real question.
sealed interface Phrased {
    data class Ask(val text: String) : Phrased
    data object Skip : Phrased
}

// Gemma writes the spoken question with the page in view (the form's other fields, the options, the catalogue
// wording as a hint), so "City:" under the registered address becomes "your registered address's city".
// Code checks the wording; anything off falls back to the catalogue wording. Gemma may drop a junk line
// ("M Re", a heading), but never a known bank field.
suspend fun phrase(question: Question, all: List<Question>, language: Language): Phrased {
    val fallback = Phrased.Ask(question.ask(language))
    val reply = try {
        Gemma.ask(prompt(question, all, language), system = system(language), examples = examples(language)).text.trim()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "Gemma could not phrase ${question.id}", e)
        return fallback
    }
    val known = bankField(question.id, question.label) != null
    val result = when {
        reply.trim('.', ' ').equals("SKIP", ignoreCase = true) -> if (known) fallback else Phrased.Skip
        fits(reply, question, language) -> Phrased.Ask(reply)
        else -> fallback
    }
    Log.i(TAG, "${question.id} \"${question.label}\": Gemma \"$reply\" -> $result")
    return result
}

// One line, a real question in the chosen script, not too long, and every option still named.
// English may also be a polite request ending "." ("Please tell me your address, including house number, street,
// and area." was thrown away in the 07:26 run).
private fun fits(reply: String, question: Question, language: Language): Boolean {
    val hindi = DEVANAGARI.containsMatchIn(reply)
    val said = words(reply).toSet()
    val namesOptions = question.options.all { option ->
        val names = words(option) + words(hindiOption(option))
        names.any { it in said } || (language == Language.HINDI && hindiOption(option) == option)
    }
    return '\n' !in reply && reply.split(Regex("""\s+""")).size <= 40 && namesOptions &&
        (if (language == Language.HINDI) hindi && reply.last() in "?।" else !hindi && (reply.endsWith('?') || isRequest(reply)))
}

private fun isRequest(reply: String) = reply.endsWith('.') && words(reply).firstOrNull() in REQUEST_START

private val REQUEST_START = setOf("please", "kindly", "tell", "say")

private fun system(language: Language): String {
    val script = if (language == Language.HINDI) "simple, polite Hindi (Devanagari script)" else "simple, polite English"
    return """
You help a customer fill an Indian bank form by voice. The phone speaks ONE question for one field of the form.
Write that question in $script.
Rules:
- One short question, at most 15 words, plus the options if there are any.
- Use the form's other fields to understand this field (for example, which address a City belongs to).
- If there are options, end by naming every option, short. Name options ONLY from the Options line:
  when it says none, list no options.
- Ask only for this field. No explanations, no notes.
- If this field is not something a customer answers, reply SKIP: a heading, a box the bank fills, broken text like "M Re",
  or an option of another field (SAVINGS ACCOUNT printed under TYPE OF ACCOUNT).
Reply with ONLY the question, or SKIP.
""".trim()
}

private fun prompt(question: Question, all: List<Question>, language: Language): String {
    val fields = all.withIndex().joinToString("\n") { (i, q) -> "${i + 1}. ${q.label}" }
    val options = question.options.joinToString(" / ") { option ->
        hindiOption(option).takeIf { language == Language.HINDI && it != option }?.let { "$option ($it)" } ?: option
    }.ifEmpty { "none" }
    return """
Form fields, in order:
$fields
This field: ${all.indexOf(question) + 1}. ${question.label}
Answer: ${ANSWER_KIND[question.type] ?: "words"}
Options: $options
Standard question: ${question.ask(language)}
""".trim()
}

private val ANSWER_KIND = mapOf(
    "number" to "a number", "date" to "a date", "mobile" to "a mobile number", "email" to "an email address",
    "pincode" to "a PIN code", "aadhaar" to "an Aadhaar number", "pan" to "a PAN", "ifsc" to "an IFSC code",
    "choice" to "one of the options",
)

private const val EXAMPLE_FIELDS = """Form fields, in order:
1. * TYPE OF ACCOUNT
2. SAVINGS ACCOUNT OTHERS:
3. * Account Title/Name :
4. *Particulars:
5. City:
6. REGISTERED ADDRESS (For Entities) / RESIDENCE ADDRESS
7. *Particulars
8. City:
9. M Re"""

private fun examples(language: Language): List<Pair<String, String>> {
    val hindi = language == Language.HINDI
    return listOf(
        """
$EXAMPLE_FIELDS
This field: 1. * TYPE OF ACCOUNT
Answer: one of the options
Options: ${if (hindi) "SAVINGS ACCOUNT (बचत खाता) / CURRENT ACCOUNT (चालू खाता)" else "SAVINGS ACCOUNT / CURRENT ACCOUNT"}
Standard question: ${if (hindi) "खाते का प्रकार क्या है? बचत खाता या चालू खाता?" else "What type of account is it? SAVINGS ACCOUNT or CURRENT ACCOUNT?"}
""".trim() to if (hindi) "आप कौन सा खाता खोलना चाहते हैं? बचत खाता या चालू खाता?"
        else "Which account do you want to open? Savings account or current account?",
        """
$EXAMPLE_FIELDS
This field: 2. SAVINGS ACCOUNT OTHERS:
Answer: words
Options: none
Standard question: ${if (hindi) "फ़ॉर्म में लिखा है: SAVINGS ACCOUNT OTHERS. इसका जवाब बताइए।" else "SAVINGS ACCOUNT OTHERS?"}
""".trim() to "SKIP",
        """
$EXAMPLE_FIELDS
This field: 8. City:
Answer: words
Options: none
Standard question: ${if (hindi) "आपका शहर, कस्बा या गाँव कौन सा है?" else "What is your city, town or village?"}
""".trim() to if (hindi) "आपके रहने के पते में शहर, कस्बा या गाँव कौन सा है?"
        else "In your residence address, which city, town or village is it?",
        """
$EXAMPLE_FIELDS
This field: 9. M Re
Answer: words
Options: none
Standard question: ${if (hindi) "फ़ॉर्म में लिखा है: M Re. इसका जवाब बताइए।" else "M Re?"}
""".trim() to "SKIP",
    )
}

private val DEVANAGARI = Regex("[ऀ-ॿ]")
