package com.vishal.fillbyvoice

import com.vishal.fillbyvoice.pipeline.clean
import com.vishal.fillbyvoice.pipeline.endsMidPhrase
import com.vishal.fillbyvoice.pipeline.isAskingBack
import com.vishal.fillbyvoice.pipeline.isFinish
import com.vishal.fillbyvoice.pipeline.isSkip
import com.vishal.fillbyvoice.pipeline.matchOption
import com.vishal.fillbyvoice.pipeline.missingYear
import com.vishal.fillbyvoice.pipeline.pickField
import com.vishal.fillbyvoice.pipeline.pickPosition
import com.vishal.fillbyvoice.pipeline.printedInEnglish
import com.vishal.fillbyvoice.pipeline.sameAnswerInEnglish
import com.vishal.fillbyvoice.pipeline.saysNone
import com.vishal.fillbyvoice.pipeline.spokenDate
import com.vishal.fillbyvoice.pipeline.yesOrNo
import com.vishal.fillbyvoice.validate.Checked
import com.vishal.fillbyvoice.validate.check
import com.vishal.fillbyvoice.voice.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NormaliserTest {

    // Rules clean the spoken text, then the Part 7 check decides.
    private fun value(type: String, heard: String) = (check(type, clean(type, heard)) as? Checked.Ok)?.value

    @Test
    fun digitsInAnyForm() {
        assertEquals("9876543210", value("mobile", "98765 43210"))
        assertEquals("9876543210", value("mobile", "९८७६५ ४३२१०"))
        assertEquals("9876543210", value("mobile", "nine eight seven six five four three two one zero"))
        assertEquals("9876543210", value("mobile", "नौ आठ सात छह पाँच चार तीन दो एक शून्य"))
        assertEquals("9987654321", value("mobile", "double 9 8 7 6 5 4 3 2 1"))
        assertEquals("9876543210", value("mobile", "my number is 98765 43210"))
        assertEquals("234567890124", value("aadhaar", "2345 6789 0124"))
        assertEquals("50000", value("number", "rupees 50,000"))
        assertNull(value("number", "12.5")) // refused, never 125
    }

    @Test
    fun lettersSpokenInHindi() {
        assertEquals("ABCDE1234F", value("pan", "ए बी सी डी ई 1 2 3 4 एफ"))
        assertEquals("SBIN0001234", value("ifsc", "S B I N zero zero zero 1 2 3 4"))
    }

    @Test
    fun datesWithMonthNames() {
        assertEquals("25/03/1990", value("date", "25 March 1990"))
        assertEquals("25/03/1990", value("date", "25th of march 1990"))
        assertEquals("25/03/1990", value("date", "25 मार्च 1990"))
        assertEquals("25/03/1990", value("date", "25031990"))
        // 09:47 run: the English recognizer heard "November" as "number", and "third" was not read.
        assertEquals("03/11/1990", value("date", "It is third number 1990"))
        assertEquals("23/11/1954", value("date", "twenty third November 1954"))
        assertEquals("20/03/1990", value("date", "twenty March 1990"))
        assertNull(value("date", "Today's third number 1865")) // before 1900: refused, never guessed
        // Words the rules cannot read are left as they are, for Gemma.
        assertEquals("पच्चीस मार्च उन्नीस सौ नब्बे", clean("date", "पच्चीस मार्च उन्नीस सौ नब्बे"))
        assertEquals("25 मार्च 1990", spokenDate("25/03/1990", Language.HINDI))
        assertEquals("5 March 1990", spokenDate("05/03/1990", Language.ENGLISH))
    }

    @Test
    fun emailSpokenInEnglish() {
        assertEquals("ramesh.kumar@gmail.com", value("email", "Ramesh dot Kumar at the rate gmail dot com"))
        assertEquals("ramesh@gmail.com", value("email", "ramesh at gmail.com"))
    }

    @Test
    fun optionsMatchedByTheirWords() {
        val accounts = listOf(
            "CURRENT ACCOUNT", "EXCHANGE EARNER'S FOREIGN CURRENCY ACCOUNT (EEFC)", "SPECIALSAVING ACCOUNT", "OTHERS",
        )
        assertEquals("CURRENT ACCOUNT", matchOption("It is current account", accounts))
        assertEquals("CURRENT ACCOUNT", matchOption("चालू खाता", accounts))
        assertEquals("EXCHANGE EARNER'S FOREIGN CURRENCY ACCOUNT (EEFC)", matchOption("EEFC", accounts))
        assertNull(matchOption("account", accounts)) // every option says "account": no guess
        // 06:58 run: the form prints "SPECIAL SAVING ACCOUNT", the user says "Savings account".
        assertEquals("SPECIAL SAVING ACCOUNT", matchOption("Savings account", listOf("CURRENT ACCOUNT", "SPECIAL SAVING ACCOUNT", "OTHERS")))

        val gender = listOf("Male", "Female", "Transgender")
        assertEquals("Female", matchOption("female", gender))
        assertEquals("Female", matchOption("मैं महिला हूँ", gender))
        assertEquals("Male", matchOption("पुरुष", gender))

        assertEquals("NON-IKIT", matchOption("non ikit", listOf("IKIT", "NON-IKIT")))
        assertEquals("IKIT", matchOption("ikit", listOf("IKIT", "NON-IKIT")))
        assertEquals("Unmarried", matchOption("अविवाहित", listOf("Married", "Unmarried", "Others")))
        assertEquals("Yes", matchOption("हां", listOf("Yes", "No")))
    }

    @Test
    fun yesNoAndSkip() {
        assertEquals(true, yesOrNo("हाँ जी"))
        assertEquals(true, yesOrNo("हां"))
        assertEquals(true, yesOrNo("सही है"))
        assertEquals(true, yesOrNo("yes"))
        assertEquals(false, yesOrNo("सही नहीं है"))
        assertEquals(false, yesOrNo("no"))
        assertNull(yesOrNo("बदलना है"))
        assertNull(yesOrNo(null))

        assertTrue(isSkip("छोड़ो"))
        assertTrue(isSkip("छोड़ दीजिए"))
        assertTrue(isSkip("Skip this"))
        assertFalse(isSkip("रमेश कुमार"))
        assertFalse(isSkip("next to the temple"))

        assertTrue(isAskingBack("What is Enrique")) // seen on the phone: it was saved as a name
        assertTrue(isAskingBack("यह क्या है"))
        assertTrue(isAskingBack("इसका मतलब"))
        assertTrue(isAskingBack("Can you explain more")) // 06:48 run: was saved as the product name
        assertTrue(isAskingBack("what is it"))
        assertTrue(isAskingBack("फिर से बोलिए"))
        assertTrue(isAskingBack("समझाइए"))
        assertTrue(isAskingBack("Valu means what exactly can you elaborate")) // 08:07 run: was saved as the value
        assertTrue(isAskingBack("कैसे प्रकार मुझे एग्जांपल दो")) // 09:25 run: was saved as the account type
        assertFalse(isAskingBack("रमेश कुमार"))
        assertFalse(isAskingBack("It is current account"))
        assertFalse(isAskingBack("Canara Bank"))

        assertTrue(saysNone("No no")) // 06:48 run: was saved as an answer
        assertTrue(saysNone("नहीं है"))
        assertTrue(saysNone("none"))
        assertFalse(saysNone("Ramesh Kumar"))
    }

    @Test
    fun finishWords() {
        // 07:26 run: saved as the fax number, and counted as yes.
        assertTrue(isFinish("I am done he can now fill the form"))
        assertTrue(isFinish("Okay I think I am done"))
        assertTrue(isFinish("done"))
        assertTrue(isFinish("I'm done"))
        assertTrue(isFinish("finish"))
        assertTrue(isFinish("That's all"))
        assertTrue(isFinish("बस"))
        assertTrue(isFinish("बस करो"))
        assertTrue(isFinish("हो गया"))
        assertTrue(isFinish("बस हो गया"))
        assertTrue(isFinish("ख़त्म"))
        assertTrue(isFinish("करो आज का बहुत हुआ")) // 09:20 run: "बस करो" lost its "बस"
        assertTrue(isFinish("अब बंद करो"))
        // Real answers that happen to hold a finish word.
        assertFalse(isFinish("I have done MBA"))
        assertFalse(isFinish("बस स्टैंड"))
        assertFalse(isFinish("बस स्टैंड के पास"))
        assertFalse(isFinish("मैं रिटायर हो गया हूँ"))
        assertFalse(isFinish("Ramesh Kumar"))
        assertFalse(isFinish(""))
    }

    @Test
    fun answerCutAtAPause() {
        // 07:26 run: saved as the product name, and "House number is 591" lost its street.
        assertTrue(endsMidPhrase("The product name should be"))
        assertTrue(endsMidPhrase("Street is Gachibowli area is"))
        assertTrue(endsMidPhrase("राम नगर और"))
        assertFalse(endsMidPhrase("House number is 591"))
        assertFalse(endsMidPhrase("मेरा नाम रमेश कुमार है")) // a full Hindi sentence ends with "है"
        assertFalse(endsMidPhrase("हैदराबाद में"))
        assertFalse(endsMidPhrase(""))
        // 07:49 run: the year came after a pause.
        assertTrue(missingYear("It is 28th November"))
        assertFalse(missingYear("28th November 1990"))
        assertFalse(missingYear("25031990"))
    }

    @Test
    fun readBackPicksTheFieldToChange() {
        // Short Hindi, short English, the form's label: as the read-back builds them.
        val names = listOf(
            listOf("नाम", "Name", "Account Title/ Name"),
            listOf("पिता का नाम", "Father's name", "Father's / Husband's Name"),
            listOf("जन्म तिथि", "Date of birth", "Date of Incorporation/ Date of Birth"),
            listOf("तारीख", "Date", "Date"),
            listOf("मोबाइल", "Mobile", "*Mobile"),
            listOf("Importer/Exporter Code Number (ifany)"),
        )
        assertEquals(0, pickField("नाम गलत है", names))
        assertEquals(0, pickField("the name is wrong", names))
        assertEquals(1, pickField("पिता का नाम बदलना है", names))
        assertEquals(2, pickField("change the date of birth", names))
        assertEquals(3, pickField("date", names))
        assertEquals(4, pickField("मोबाइल नंबर", names))
        assertEquals(5, pickField("importer code", names))
        assertNull(pickField("नहीं", names))
        assertNull(pickField("", names))
    }

    @Test
    fun readBackPicksTheFieldByPlace() {
        // 10:14 run, 4 answers on screen: both replies meant "Application Type", the third.
        assertEquals(2, pickPosition("नहीं थर्ड वाले में कुछ दिक्कत है", 4))
        assertEquals(2, pickPosition("तीसरा", 4))
        assertEquals(1, pickPosition("दूसरे वाला गलत है", 4))
        assertEquals(0, pickPosition("the first one", 4))
        assertEquals(2, pickPosition("3rd", 4))
        assertEquals(3, pickPosition("नंबर 4", 4))
        assertEquals(3, pickPosition("आख़िरी वाला", 4))
        assertNull(pickPosition("कोई एक गलत है", 4)) // "any one", not the first
        assertNull(pickPosition("नहीं", 4))
        assertNull(pickPosition("पांचवा", 4)) // not on the list
        assertNull(pickPosition("15 जनवरी 2022", 4))
    }

    @Test
    fun hindiAnswersOnAnEnglishFormAreSpelledInEnglish() {
        // The form's language comes from its own labels (SBI 10:12 run).
        assertTrue(printedInEnglish(listOf("Branch Name", "1,Name*:", "2.Date of Birth*.")))
        assertFalse(printedInEnglish(listOf("शाखा का नाम", "जन्म तिथि")))
        assertTrue(printedInEnglish(listOf("नाम / Name of the Applicant", "जन्म तिथि / Date of Birth"))) // bilingual
        // Gemma's spelling is kept only with English letters, every word and every number.
        assertTrue(sameAnswerInEnglish("विशाल सिंह", "Vishal Singh"))
        assertTrue(sameAnswerInEnglish("मकान नंबर १२ गांधी नगर", "Makan Number 12 Gandhi Nagar"))
        assertFalse(sameAnswerInEnglish("विशाल सिंह", "Vishal")) // a word lost
        assertFalse(sameAnswerInEnglish("मकान 12", "Makan 21")) // a number changed
        assertFalse(sameAnswerInEnglish("विशाल सिंह", "विशाल Singh")) // still Hindi letters
        assertFalse(sameAnswerInEnglish("किसान", "")) // nothing
    }
}
