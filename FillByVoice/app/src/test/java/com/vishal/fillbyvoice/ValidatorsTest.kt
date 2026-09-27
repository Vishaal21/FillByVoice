package com.vishal.fillbyvoice

import com.vishal.fillbyvoice.validate.Checked
import com.vishal.fillbyvoice.validate.check
import com.vishal.fillbyvoice.validate.verhoeff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ValidatorsTest {

    private val today = LocalDate.of(2026, 9, 27)

    private fun ok(type: String, answer: String, value: String) =
        assertEquals(Checked.Ok(value), check(type, answer, today))

    private fun wrong(type: String, answer: String) =
        assertTrue("$type '$answer' should fail", check(type, answer, today) is Checked.Wrong)

    private fun wrongEn(type: String, answer: String) = (check(type, answer, today) as Checked.Wrong).en

    @Test
    fun aadhaar() {
        assertTrue(verhoeff("2363")) // the textbook example: 236 gets check digit 3
        assertFalse(verhoeff("2364"))
        ok("aadhaar", "2345 6789 0124", "234567890124")
        ok("aadhaar", "499118665246", "499118665246")
        wrong("aadhaar", "234567890125") // one wrong digit
        wrong("aadhaar", "324567890124") // two neighbours swapped
        assertEquals("An Aadhaar number has 12 digits, you said 11.", wrongEn("aadhaar", "23456789012"))
        assertEquals("An Aadhaar number never starts with 0 or 1.", wrongEn("aadhaar", "134567890124"))
        wrong("aadhaar", "२३४५६७८९०१२४") // Devanagari digits are converted before the check, never here
    }

    @Test
    fun pan() {
        ok("pan", "abcde 1234 f", "ABCDE1234F")
        wrong("pan", "ABCDE12345")
        wrong("pan", "ABCD1234F")
    }

    @Test
    fun ifsc() {
        ok("ifsc", "sbin 0001234", "SBIN0001234")
        ok("ifsc", "SBINO001234", "SBIN0001234") // letter O heard where the zero is
        wrong("ifsc", "SBIN1001234")
        wrong("ifsc", "SBIN000123")
    }

    @Test
    fun mobile() {
        ok("mobile", "98765 43210", "9876543210")
        ok("mobile", "+91 98765 43210", "9876543210")
        ok("mobile", "919876543210", "9876543210")
        ok("mobile", "09876543210", "9876543210")
        assertEquals("A mobile number starts with 6, 7, 8 or 9.", wrongEn("mobile", "5876543210"))
        assertEquals("A mobile number has 10 digits, you said 5.", wrongEn("mobile", "98765"))
    }

    @Test
    fun pincode() {
        ok("pincode", "500 081", "500081")
        wrong("pincode", "050081")
        wrong("pincode", "50008")
    }

    @Test
    fun date() {
        ok("date", "25/03/1990", "25/03/1990")
        ok("date", "25-3-1990", "25/03/1990")
        ok("date", "25 03 1990", "25/03/1990")
        ok("date", "5/3/1990", "05/03/1990")
        ok("date", "27/09/2026", "27/09/2026") // today is fine
        wrong("date", "31/02/1990") // no such day
        wrong("date", "25/03/2030") // future
        wrong("date", "25/03/90") // short year
        wrong("date", "1/1/1850")
        wrong("date", "25 March 1990") // month names are turned into numbers before the check
    }

    @Test
    fun email() {
        ok("email", "Ramesh.Kumar @Gmail.com", "ramesh.kumar@gmail.com")
        wrong("email", "ramesh at gmail dot com")
        wrong("email", "ramesh@gmail")
    }

    @Test
    fun number() {
        ok("number", "50,000", "50000")
        wrong("number", "12.5") // refused, never turned into 125
        wrong("number", "abc")
    }

    @Test
    fun textAndChoice() {
        ok("text", "  रमेश   कुमार ", "रमेश कुमार")
        ok("choice", "Female", "Female")
        wrong("text", " ... ")
    }
}
