package com.vishal.fillbyvoice.validate

import java.time.DateTimeException
import java.time.LocalDate

// "The model reads, code decides": every answer passes one of these checks before it is saved.
// A check gets the answer after cleaning (Devanagari digits, spoken letters and month names already turned
// into 0-9, A-Z and numbers) and returns the value to save, or what is wrong, to be spoken to the user.
sealed interface Checked {
    data class Ok(val value: String) : Checked
    data class Wrong(val hi: String, val en: String) : Checked
}

fun check(type: String, answer: String, today: LocalDate = LocalDate.now()): Checked = when (type) {
    "aadhaar" -> aadhaar(answer)
    "pan" -> pan(answer)
    "ifsc" -> ifsc(answer)
    "mobile" -> mobile(answer)
    "pincode" -> pincode(answer)
    "date" -> date(answer, today)
    "email" -> email(answer)
    "number" -> number(answer)
    else -> text(answer) // text, and choice (the loop matches a choice to one of its options first)
}

// ASCII digits only: Kotlin's isDigit() also accepts Devanagari digits like "५".
private val DIGITS = Regex("[0-9]+")
private val PAN = Regex("[A-Z]{5}[0-9]{4}[A-Z]")
private val IFSC = Regex("[A-Z]{4}0[A-Z0-9]{6}")
private val DATE = Regex("""(\d{1,2})[\s/.\-]+(\d{1,2})[\s/.\-]+(\d{4})""")
private val EMAIL = Regex("""[a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,}""")

// Gaps people say or speech adds between groups: "98765 43210", "SBIN-0001234", "A.B.C.".
private fun compact(answer: String) = answer.replace(Regex("""[\s\-.]"""), "")

private fun wrong(hi: String, en: String) = Checked.Wrong(hi, en)

private fun aadhaar(answer: String): Checked {
    val n = compact(answer)
    return when {
        !DIGITS.matches(n) -> wrong("आधार नंबर में सिर्फ़ अंक होते हैं।", "An Aadhaar number has digits only.")
        n.length != 12 -> wrong(
            "आधार नंबर बारह अंक का होता है, आपने ${n.length} अंक बोले।",
            "An Aadhaar number has 12 digits, you said ${n.length}.",
        )
        n[0] == '0' || n[0] == '1' -> wrong(
            "आधार नंबर शून्य या एक से शुरू नहीं होता।", "An Aadhaar number never starts with 0 or 1.",
        )
        !verhoeff(n) -> wrong(
            "यह आधार नंबर सही नहीं है, कोई अंक गलत है। कार्ड देखकर फिर से बताइए।",
            "This Aadhaar number is not valid, a digit is wrong. Please check the card and say it again.",
        )
        else -> Checked.Ok(n)
    }
}

private fun pan(answer: String): Checked {
    val v = compact(answer).uppercase()
    return when {
        v.length != 10 -> wrong(
            "पैन नंबर में दस अक्षर और अंक होते हैं, आपने ${v.length} बोले।",
            "A PAN has 10 letters and digits, you said ${v.length}.",
        )
        !PAN.matches(v) -> wrong(
            "पैन नंबर ऐसा होता है: पाँच अक्षर, फिर चार अंक, फिर एक अक्षर।",
            "A PAN is 5 letters, then 4 digits, then 1 letter.",
        )
        else -> Checked.Ok(v)
    }
}

private fun ifsc(answer: String): Checked {
    // The fifth character is always zero; speech often writes the letter O there.
    val v = compact(answer).uppercase().let { if (it.length == 11 && it[4] == 'O') it.replaceRange(4, 5, "0") else it }
    return when {
        v.length != 11 -> wrong(
            "आईएफ़एससी कोड में ग्यारह अक्षर और अंक होते हैं, आपने ${v.length} बोले।",
            "An IFSC code has 11 letters and digits, you said ${v.length}.",
        )
        !IFSC.matches(v) -> wrong(
            "आईएफ़एससी कोड ऐसा होता है: चार अक्षर, फिर शून्य, फिर छह अक्षर या अंक।",
            "An IFSC code is 4 letters, then 0, then 6 letters or digits.",
        )
        else -> Checked.Ok(v)
    }
}

private fun mobile(answer: String): Checked {
    // "+91 98765 43210" and "098765 43210" are the same number.
    val n = compact(answer).removePrefix("+").let {
        when {
            it.length == 12 && it.startsWith("91") -> it.drop(2)
            it.length == 11 && it.startsWith("0") -> it.drop(1)
            else -> it
        }
    }
    return when {
        !DIGITS.matches(n) -> wrong("मोबाइल नंबर में सिर्फ़ अंक होते हैं।", "A mobile number has digits only.")
        n.length != 10 -> wrong(
            "मोबाइल नंबर दस अंक का होता है, आपने ${n.length} अंक बोले।",
            "A mobile number has 10 digits, you said ${n.length}.",
        )
        n[0] !in '6'..'9' -> wrong(
            "मोबाइल नंबर छह, सात, आठ या नौ से शुरू होता है।", "A mobile number starts with 6, 7, 8 or 9.",
        )
        else -> Checked.Ok(n)
    }
}

private fun pincode(answer: String): Checked {
    val n = compact(answer)
    return when {
        !DIGITS.matches(n) -> wrong("पिन कोड में सिर्फ़ अंक होते हैं।", "A PIN code has digits only.")
        n.length != 6 -> wrong(
            "पिन कोड छह अंक का होता है, आपने ${n.length} अंक बोले।", "A PIN code has 6 digits, you said ${n.length}.",
        )
        n[0] == '0' -> wrong("पिन कोड शून्य से शुरू नहीं होता।", "A PIN code never starts with 0.")
        else -> Checked.Ok(n)
    }
}

// "25/03/1990", "25-3-1990", "25 03 1990" -> "25/03/1990".
private fun date(answer: String, today: LocalDate): Checked {
    val match = DATE.matchEntire(answer.trim()) ?: return wrong(
        "तारीख समझ नहीं आई। दिन, महीना और पूरा साल बताइए।",
        "I could not get the date. Say the day, month and full year.",
    )
    val (day, month, year) = match.destructured
    val date = try {
        LocalDate.of(year.toInt(), month.toInt(), day.toInt())
    } catch (e: DateTimeException) {
        null
    }
    return when {
        date == null || date.year < 1900 -> wrong("यह तारीख सही नहीं है।", "That date does not exist.")
        date.isAfter(today) -> wrong("यह तारीख आगे की है, आज के बाद की।", "That date is in the future.")
        else -> Checked.Ok("${day.padStart(2, '0')}/${month.padStart(2, '0')}/$year")
    }
}

private fun email(answer: String): Checked {
    val v = answer.replace(Regex("""\s"""), "").lowercase()
    return if (EMAIL.matches(v)) Checked.Ok(v) else wrong(
        "यह ईमेल पता सही नहीं लगा। जैसे: रमेश ऐट जीमेल डॉट कॉम।",
        "That email address does not look right. Like: ramesh at gmail dot com.",
    )
}

// Account numbers, ages, amounts. "50,000" -> "50000". A decimal point is refused, never dropped.
private fun number(answer: String): Checked {
    val n = answer.replace(Regex("""[\s,]"""), "")
    return if (DIGITS.matches(n)) Checked.Ok(n) else wrong("कृपया सिर्फ़ अंक बताइए।", "Please say digits only.")
}

private fun text(answer: String): Checked {
    val v = answer.trim().replace(Regex("""\s+"""), " ")
    return if (v.any(Char::isLetterOrDigit)) Checked.Ok(v) else wrong("मुझे जवाब सुनाई नहीं दिया।", "I did not hear an answer.")
}

// Verhoeff checksum: the last Aadhaar digit is made from the other 11, so one wrong digit
// or two swapped neighbours are always caught.
private val D = arrayOf(
    intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
    intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
    intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
    intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
    intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
    intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
    intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
    intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
    intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
    intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
)
private val P = arrayOf(
    intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
    intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
    intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
    intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
    intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
    intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
    intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
    intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
)

internal fun verhoeff(digits: String): Boolean =
    digits.reversed().foldIndexed(0) { i, c, ch -> D[c][P[i % 8][ch - '0']] } == 0
