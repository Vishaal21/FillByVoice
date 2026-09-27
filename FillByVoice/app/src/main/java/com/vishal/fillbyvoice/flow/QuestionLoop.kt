package com.vishal.fillbyvoice.flow

import android.content.Context
import com.vishal.fillbyvoice.log.Log
import com.vishal.fillbyvoice.pipeline.COPY_SUFFIX
import com.vishal.fillbyvoice.pipeline.Phrased
import com.vishal.fillbyvoice.pipeline.Question
import com.vishal.fillbyvoice.pipeline.ask
import com.vishal.fillbyvoice.pipeline.cleanLabel
import com.vishal.fillbyvoice.pipeline.endsMidPhrase
import com.vishal.fillbyvoice.pipeline.gemmaField
import com.vishal.fillbyvoice.pipeline.hindiOption
import com.vishal.fillbyvoice.pipeline.isAskingBack
import com.vishal.fillbyvoice.pipeline.isFinish
import com.vishal.fillbyvoice.pipeline.isSkip
import com.vishal.fillbyvoice.pipeline.missingYear
import com.vishal.fillbyvoice.pipeline.phrase
import com.vishal.fillbyvoice.pipeline.pickField
import com.vishal.fillbyvoice.pipeline.pickPosition
import com.vishal.fillbyvoice.pipeline.saysNone
import com.vishal.fillbyvoice.pipeline.shortName
import com.vishal.fillbyvoice.pipeline.spokenDate
import com.vishal.fillbyvoice.pipeline.understand
import com.vishal.fillbyvoice.pipeline.yesOrNo
import com.vishal.fillbyvoice.validate.Checked
import com.vishal.fillbyvoice.voice.Language
import com.vishal.fillbyvoice.voice.Speaker
import com.vishal.fillbyvoice.voice.listen
import com.vishal.fillbyvoice.voice.pick
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "Loop"

// Answers heard for one question before it is skipped (two silences also skip it).
private const val MAX_TRIES = 3

// What hear() gets when the user mutes the mic while it is open.
private val MUTED = Any()

// Read back one character at a time ("9 8 7 6..."), so the phone never says "nine hundred eighty-seven crore".
private val SPELLED = setOf("aadhaar", "mobile", "pincode", "pan", "ifsc")

// A long answer comes in pieces (the recognizer stops at each pause): at most this many are joined.
private const val MAX_PIECES = 4

// Listening for the next piece gives up this soon if the user says nothing more.
private const val MORE_WAIT_MS = 4_000L

// Address answers are long and said with pauses: house number, street, area.
private val ADDRESS_IDS = setOf("address", "nominee_address")
private val ADDRESS_LABEL = Regex("address|particular", RegexOption.IGNORE_CASE)

// Answers the user can change at the read-back before the list stands as it is.
private const val MAX_CHANGES = 5

// One line of the answer sheet. value null: skipped. asked: the wording the phone used, to ask it the same way again.
data class Answer(val question: Question, val value: String?, val asked: String)

// What the screen shows while the loop runs.
data class LoopState(
    val number: Int,
    val total: Int,
    val question: String,
    val heard: String = "",
    val value: String = "",
    val listening: Boolean = false, // the mic is open: the screen says "speak now"
    val options: List<String> = emptyList(), // shown under the question, in the chosen language
    val review: List<Answer>? = null, // the read-back: every answer on screen with "सब सही है?"
)

// The voice loop over every question: ask, listen, clean, check, read back "सही है?", then save or ask again.
// Code decides every step; Gemma only helps read an answer the rules could not.
class QuestionLoop(
    private val context: Context,
    private val language: Language,
    private val speaker: Speaker,
    private val muted: StateFlow<Boolean>, // the mic button: while true, the phone does not listen
    private val show: (LoopState) -> Unit,
) {
    private var current = LoopState(0, 0, "")

    // The user said "I am done" / "बस": no more questions.
    private var finished = false

    private fun update(state: LoopState) {
        current = state
        show(state)
    }

    suspend fun run(questions: List<Question>): List<Answer> {
        val answers = mutableListOf<Answer>()
        for ((i, q) in questions.withIndex()) {
            // The same field twice ("8.Nationality:" and "9.Citizenship:" are both nationality, 10:12 run, SBI):
            // asked once, and that answer fills the second blank too. A second address has its own id (address_2).
            val same = answers.firstOrNull { it.question.id == q.id && it.value != null }
            if (same != null) {
                Log.i(TAG, "Q${i + 1} ${q.id}: same field as \"${same.question.label}\", answer reused")
                answers += same.copy(question = q)
                continue
            }
            update(LoopState(i + 1, questions.size, q.ask(language)))
            // Gemma words each question with the whole page in view; a junk line it drops is not on the answer sheet.
            val phrased = phrase(q, questions, language)
            if (phrased is Phrased.Ask) answers += Answer(q, askOne(q, phrased.text, i + 1, questions.size), phrased.text)
            // Finished early: the questions after this one are left out of the answer sheet.
            if (finished) {
                Log.i(TAG, "finished by the user at Q${i + 1}, ${questions.size - i - 1} questions left out")
                break
            }
        }
        speaker.speak(
            if (finished) language.pick("ठीक है, यहीं रोकते हैं।", "Okay, stopping here.")
            else language.pick("सारे सवाल हो गए।", "All questions are done."),
            language,
        )
        finished = false
        review(answers)
        speaker.speak(
            language.pick(
                "आपके जवाब स्क्रीन पर हैं। पीडीएफ़ डाउनलोड या शेयर कर सकते हैं।",
                "Your answers are on the screen. You can download or share the PDF.",
            ),
            language,
        )
        return answers
    }

    // Part 9: every answer read back, then "सब सही है?". A field named in the reply ("मोबाइल गलत है", "तीसरा"), or after
    // "कौन सा बदलना है?", is asked again, then "अब सब सही है?". Code first; Gemma only when the reply to "कौन सा
    // बदलना है?" names nothing the rules know. Silence: the list stands.
    private suspend fun review(answers: MutableList<Answer>) {
        // A reused answer (nationality for citizenship) is read out once.
        val said = answers.filter { it.value != null }.distinctBy { it.question.id }
        if (said.isEmpty()) return
        val names = answers.map { a ->
            listOfNotNull(shortName(a.question.id)?.first, shortName(a.question.id)?.second, cleanLabel(a.question.label))
        }
        val examples = said.take(2).joinToString(language.pick(" या ", " or ")) { spokenName(it.question) }
        var say = language.pick("आपके जवाब।", "Your answers.") + " " +
            said.joinToString(" ") { "${spokenName(it.question)}: ${spoken(it.value!!, it.question.type)}${language.pick("।", ".")}" } +
            " " + language.pick("सब सही है?", "Is everything right?")
        repeat(MAX_CHANGES) {
            update(LoopState(0, 0, "", review = answers.toList()))
            speaker.speak(say, language)
            val heard = hear(say) ?: return
            Log.i(TAG, "review: \"$heard\"")
            // "हाँ, नाम सही है" names a field but means yes: yes wins.
            if (isFinish(heard) || yesOrNo(heard) == true) return
            var index = pickField(heard, names) ?: pickPosition(heard, answers.size)
            if (index == null) {
                val which = language.pick("कौन सा बदलना है? जैसे $examples।", "Which one should I change? For example, $examples.")
                speaker.speak(which, language)
                val reply = hear(which) ?: return
                if (isFinish(reply)) return
                index = pickField(reply, names) ?: pickPosition(reply, answers.size)
                // "कोई नहीं": nothing to change. Checked after the rules, so "नहीं, तीसरा" still picks the third.
                if (index == null && saysNone(reply)) return
                if (index == null) index = gemmaField(reply, names)
            }
            if (index == null) {
                say = language.pick("माफ़ कीजिए, समझ नहीं आया। सब सही है?", "Sorry, I did not get that. Is everything right?")
                return@repeat
            }
            val old = answers[index]
            Log.i(TAG, "review: asking ${old.question.id} again")
            val value = askOne(old.question, old.asked, index + 1, answers.size)
            if (finished) return
            // Skipped this time: the earlier answer stays. A new one also changes the blank that reused it.
            if (value != null) answers.replaceAll { if (it.question.id == old.question.id) it.copy(value = value) else it }
            say = language.pick("अब सब सही है?", "Is everything right now?")
        }
    }

    // "नाम", "Mobile", or the form's own label for a field outside the catalogue.
    private fun spokenName(q: Question): String =
        shortName(q.id)?.let { language.pick(it.first, it.second) } ?: cleanLabel(q.label)

    // The confirmed value, or null when the user skips or the question fails too often.
    private suspend fun askOne(q: Question, question: String, number: Int, total: Int): String? {
        var say = question
        var silent = 0
        var tries = 0
        val asking = LoopState(number, total, question, options = q.options.map { if (language == Language.HINDI) hindiOption(it) else it })
        while (tries < MAX_TRIES && !finished) {
            update(asking)
            speaker.speak(say, language)
            val heard = hear(say)
            if (heard == null) {
                Log.i(TAG, "Q$number ${q.id}: nothing heard")
                if (++silent >= 2) break
                say = language.pick("मुझे कुछ सुनाई नहीं दिया।", "I did not hear anything.") + " " + question
                continue
            }
            update(asking.copy(heard = heard))
            // Checked first: "I am done he can now fill the form" was saved as the fax number (07:26 run).
            if (isFinish(heard)) {
                Log.i(TAG, "Q$number ${q.id}: the user finished (\"$heard\")")
                finished = true
                return null
            }
            if (isSkip(heard) || (q.type != "choice" && saysNone(heard))) {
                Log.i(TAG, "Q$number ${q.id}: skipped by the user (\"$heard\")")
                speaker.speak(language.pick("ठीक है, छोड़ दिया।", "Okay, skipped."), language)
                return null
            }
            tries++
            // "What is this?" is the user asking back, not an answer. (Part 10 will explain the field here.)
            if (isAskingBack(heard)) {
                Log.i(TAG, "Q$number ${q.id}: user asked back \"$heard\"")
                say = language.pick("यह फ़ॉर्म का सवाल है, अपना जवाब बताइए।", "This is a question on the form, please say your answer.") +
                    " " + question
                continue
            }
            val answer = rest(q, heard, say)
            when (val result = understand(q, answer)) {
                is Checked.Wrong -> {
                    Log.i(TAG, "Q$number ${q.id}: \"$answer\" is wrong: ${result.en}")
                    say = language.pick(result.hi, result.en) + " " + question
                }
                is Checked.Ok -> {
                    update(asking.copy(heard = answer, value = result.value))
                    if (confirm(result.value, q.type)) {
                        Log.i(TAG, "Q$number ${q.id}: saved \"${result.value}\"")
                        return result.value
                    }
                    Log.i(TAG, "Q$number ${q.id}: \"${result.value}\" not confirmed")
                    say = language.pick("ठीक है, फिर से बताइए।", "Okay, please say it again.") + " " + question
                }
            }
        }
        if (finished) return null
        Log.i(TAG, "Q$number ${q.id}: skipped after $tries answers, $silent silences")
        speaker.speak(language.pick("यह सवाल अभी छोड़ रहे हैं।", "Skipping this question for now."), language)
        return null
    }

    // "9 8 7 6 5 4 3 2 1 0, सही है?" -> true on yes, false on no. Unclear twice counts as no (asked again).
    private suspend fun confirm(value: String, type: String): Boolean {
        repeat(2) { attempt ->
            val say = if (attempt == 0) {
                language.pick("${spoken(value, type)}, सही है?", "${spoken(value, type)}, is that right?")
            } else {
                language.pick("हाँ या नहीं बोलिए।", "Please say yes or no.")
            }
            speaker.speak(say, language)
            val heard = hear(say)
            // "Okay I think I am done" was counted as yes (07:26 run): the user stops here, and the answer is
            // kept only if they also said yes.
            if (heard != null && isFinish(heard)) {
                Log.i(TAG, "the user finished at \"is that right?\" (\"$heard\")")
                finished = true
                return yesOrNo(heard) == true
            }
            yesOrNo(heard)?.let { return it }
        }
        return false
    }

    // The recognizer stops at the first pause (07:26 run: only "House number is 591" was saved, and "street is
    // Gachibowli" landed on "is that right?"). An address keeps listening until the user is quiet; any answer that
    // stops mid-phrase ("The product name should be"), or a date with no year yet ("It is 28th November", 07:49 run),
    // listens once more. The pieces are joined.
    private suspend fun rest(q: Question, first: String, again: String): String {
        var answer = first
        var pieces = 1
        while (pieces < MAX_PIECES &&
            (q.isAddress() || endsMidPhrase(answer) || (pieces == 1 && q.type == "date" && missingYear(answer)))
        ) {
            val more = hear(again, MORE_WAIT_MS) ?: break
            // "That's all" after an address ends the address, not the whole form.
            if (isFinish(more)) break
            answer = "$answer $more"
            pieces++
            update(current.copy(heard = answer))
        }
        if (pieces > 1) Log.i(TAG, "${q.id}: joined $pieces pieces: \"$answer\"")
        return answer
    }

    private fun Question.isAddress() =
        type == "text" && (id.replace(COPY_SUFFIX, "") in ADDRESS_IDS || ADDRESS_LABEL.containsMatchIn(label))

    // How a value is read back: codes one character at a time, a date with the month's name, an option in Hindi.
    private fun spoken(value: String, type: String): String = when {
        type == "choice" && language == Language.HINDI -> hindiOption(value)
        type == "date" -> spokenDate(value, language)
        type in SPELLED || (type == "number" && value.length > 6) -> value.toList().joinToString(" ")
        else -> value
    }

    // Listens once, with "speak now" on screen. Muted by the user: the mic closes at once, nothing heard counts,
    // and after unmute the phone says `again` and listens again. waitMs: see listen().
    private suspend fun hear(again: String, waitMs: Long? = null): String? {
        while (true) {
            if (muted.value) {
                Log.i(TAG, "mic muted by the user, waiting")
                muted.first { !it }
                speaker.speak(again, language)
            }
            update(current.copy(listening = true))
            val heard = try {
                coroutineScope {
                    val listening = async { listenOnce(waitMs) }
                    val muting = async { muted.first { it } }
                    select<Any?> {
                        listening.onAwait { it }
                        muting.onAwait { MUTED }
                    }.also {
                        listening.cancel()
                        muting.cancel()
                    }
                }
            } finally {
                update(current.copy(listening = false))
            }
            if (heard !== MUTED) return heard as String?
        }
    }

    // A recognizer error counts as silence so the loop goes on (the error is in the Voice log).
    private suspend fun listenOnce(waitMs: Long?): String? = try {
        listen(context, language, waitMs)
    } catch (e: IllegalStateException) {
        if (e is CancellationException) throw e
        null
    }
}
