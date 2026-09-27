package com.vishal.fillbyvoice

import android.graphics.Rect
import com.vishal.fillbyvoice.pipeline.Question
import com.vishal.fillbyvoice.pipeline.cleanLabel
import com.vishal.fillbyvoice.pipeline.looksLikeLabel
import com.vishal.fillbyvoice.pipeline.withoutHeadings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldFinderTest {

    private fun question(id: String, label: String) = Question(id, "text", "", "", emptyList(), label, Rect())

    @Test
    fun unknownFieldsAreKeptOnlyWhenTheyLookLikeLabels() {
        // SBI 09:33: real labels Gemma found but the catalogue does not know.
        assertTrue(looksLikeLabel("Application Type"))
        assertTrue(looksLikeLabel("Relationship with Guardian"))
        assertTrue(looksLikeLabel("Place of Posting"))
        // Junk Gemma also returned on the same page.
        assertFalse(looksLikeLabel("PSB"))
        assertFalse(looksLikeLabel("Ease"))
        assertFalse(looksLikeLabel("name and code no."))
        assertFalse(looksLikeLabel("A. Personal Details"))
        assertFalse(looksLikeLabel("Customer ID"))
        assertFalse(looksLikeLabel("CKYC No."))
        assertFalse(looksLikeLabel("Across Nation wvith Convenience &")) // 09:47 run: the PSB logo was asked
        assertFalse(looksLikeLabel("Account No."))
        assertFalse(looksLikeLabel("Third Gender"))
        assertFalse(looksLikeLabel("CUSTOMER INFORMATION SHEET (CIF Creation/Amendment)"))
        assertFalse(looksLikeLabel("In case of current account, declaration cum undertaking, to be obtained"))
    }

    @Test
    fun labelsLoseTheirNumbers() {
        assertEquals("Name", cleanLabel("1,Name*:")) // 10:12 run, SBI: OCR read "1." as "1,"
        assertEquals("Marital Status", cleanLabel("4.Marital Status"))
        assertEquals("Occupation Type", cleanLabel("*10.Occupation Type:"))
    }

    @Test
    fun sameFieldTwiceKeepsTheLastOne() {
        // 07:41 run: the heading and the blank under it were both the second address, with a question between.
        val page = listOf(
            question("address", "*Particulars :"),
            question("address_2", "REGISTERED ADDRESS (For Entities) / RESIDENCE ADDRESS"),
            question("address_match", "Same as communication address. Yes"),
            question("address_2", "*Particulars"),
            question("city_2", "City:"),
        )
        assertEquals(
            listOf("*Particulars :", "Same as communication address. Yes", "*Particulars", "City:"),
            withoutHeadings(page).map { it.label },
        )
        // 07:49 run: Gemma numbered the heading apart (address_2, then address). Two real address blanks both stay.
        val numbered = listOf(
            question("address_2", "COMMUNICATION ADDRESS"),
            question("address", "*Particulars:"),
            question("city_2", "City:"),
            question("mobile_2", "*Mobile:"),
            question("address", "REGISTERED ADDRESS"),
            question("address_3", "*Particulars:"),
            question("city", "City:"),
        )
        assertEquals(
            listOf("*Particulars:", "City:", "*Mobile:", "*Particulars:", "City:"),
            withoutHeadings(numbered).map { it.label },
        )
    }
}
