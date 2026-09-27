package com.vishal.fillbyvoice

import com.vishal.fillbyvoice.pipeline.bankField
import com.vishal.fillbyvoice.pipeline.bankFieldForLabel
import com.vishal.fillbyvoice.pipeline.hindiOption
import com.vishal.fillbyvoice.pipeline.mayBeChoice
import com.vishal.fillbyvoice.pipeline.tidyOption
import com.vishal.fillbyvoice.pipeline.withOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Labels below are real OCR lines from the ICICI and SBI test runs.
class BankFieldsTest {

    @Test
    fun gemmaIdIsUsedWhenTheLabelAgrees() {
        assertEquals("name", bankField("account_title", "*Account Title/Name:")?.id)
        assertEquals("dob", bankField("dob", "*Date of Incorporation/ Date of Birth:")?.id)
        assertEquals("mobile", bankField("mobile_2", "*Mobile:")?.id)
        assertEquals("email", bankField("email_id", "E-mail ID:")?.id)
        assertEquals("pincode", bankField("pin_code", "PIN")?.id)
        assertEquals("address", bankField("particulars", "* Particulars :")?.id)
        assertEquals("pan", bankField("pan", "*PAN OF APPLICANT:")?.id)
        assertEquals("father_name", bankField("father_name", "Father's / Husband's Name")?.id)
        assertEquals("nominee_dob", bankField("nominee_dob", "Date of Birth (if minor):")?.id)
        assertEquals("account_type", bankField("account_type", "• TYPE OFACCOUNT")?.id)
        assertEquals("phone", bankField("telephone_2", "Telephone:")?.id)
        assertEquals("date", bankField("date", "Date:")?.id)
    }

    @Test
    fun gemmaIdIsRejectedWhenTheLabelDisagrees() {
        assertNull(bankField("name", "Nominee Name"))
        assertNull(bankField("dob", "Date:"))
        assertNull(bankField("address", "Same as communication address. Yes"))
        assertNull(bankField("address", "E-mail Address"))
        assertNull(bankField("prefix", "*Account Title/Name:"))
        assertNull(bankField("category", "Account Category"))
        assertNull(bankField("mobile", "Importer/Exporter Code Number"))
        assertNull(bankField("date", "Place of Birth"))
        assertNull(bankField("name", "Name of Joint Applicant"))
        assertNull(bankField("account_title", "PRODUCT DETAILS@ \"Product Name:")) // 06:58 run: asked as the name
        assertNull(bankField("name", "6.Name of *")) // 09:24 run, SBI: the father's / mother's / spouse's name
        assertEquals("name", bankField("name", "Name of the Applicant")?.id)
        assertEquals("name", bankField("name_field", "1.Name*")?.id)
        assertEquals("name", bankField("name_name", "1,Name*:")?.id) // 10:12 run, SBI: asked with the raw label
    }

    @Test
    fun labelAlonePicksOnlySafeFields() {
        assertEquals("pan", bankFieldForLabel("*PAN OF APPLICANT:")?.id)
        assertEquals("email", bankFieldForLabel("E-mail ID:")?.id)
        assertEquals("mobile", bankFieldForLabel("*Mobile:")?.id)
        assertEquals("phone", bankFieldForLabel("Telephone:")?.id)
        assertEquals("father_name", bankFieldForLabel("Father's Name:")?.id)
        assertNull(bankFieldForLabel("Name:")) // could be the nominee's name
        assertNull(bankFieldForLabel("Date of Birth:")) // could be the nominee's
        assertNull(bankFieldForLabel("Importer/Exporter Code Number (ifany):"))
        // Lines without ":" that the safety net now catches.
        assertEquals("account_type", bankFieldForLabel("• TYPE OFACCOUNT")?.id)
        assertEquals("joint_name", bankFieldForLabel("Joint Applicant")?.id)
        // 07:26 run: OCR dropped the ":" and the branch was never asked.
        assertEquals("branch", bankFieldForLabel("Branch")?.id)
        assertEquals("branch", bankFieldForLabel("Branch:")?.id)
        assertNull(bankFieldForLabel("Branch Code"))
        assertNull(bankFieldForLabel("For Branch Use Only"))
        // Bank names and bare option words are never questions.
        assertNull(bankFieldForLabel("STATE BANK OF INDIA"))
        assertNull(bankFieldForLabel("State Govt.")) // 10:12 run, SBI: an occupation option, asked as the state
        assertEquals("state", bankFieldForLabel("State:")?.id)
        assertNull(bankFieldForLabel("Father"))
        assertNull(bankFieldForLabel("Spouse*"))
        assertNull(bankFieldForLabel("Mother"))
    }

    @Test
    fun onlyChoiceFieldsTakeOptionsFromTheRowBelow() {
        // 09:30 run, SBI: "Branch Name" took "Customer ID / name and code no." as its options.
        assertFalse(mayBeChoice("branch"))
        assertFalse(mayBeChoice("name"))
        assertFalse(mayBeChoice("city_2"))
        assertTrue(mayBeChoice("account_type"))
        assertTrue(mayBeChoice("gender"))
        assertTrue(mayBeChoice("name_of")) // "6.Name of *": Father / Mother / Spouse
    }

    @Test
    fun optionsAreReadOutInHindi() {
        val gender = listOf("Male", "Female", "Transgender").map(::hindiOption)
        assertEquals("आपका लिंग क्या है? पुरुष, महिला या ट्रांसजेंडर?", withOptions("आपका लिंग क्या है?", gender, "या"))
        assertEquals("चालू खाता", hindiOption("CURRENTACCOUNT"))
        assertEquals("EEFC", hindiOption("EEFC"))
    }

    @Test
    fun optionsAreTidied() {
        // 07:19 run: OCR lost the spaces and the phone spelled it letter by letter.
        assertEquals("SPECIAL SAVING ACCOUNT", tidyOption("SPECIALSAVINGACCOUNT"))
        assertEquals("EXCHANGE EARNER'S FOREIGN CURRENCY ACCOUNT (EEFC)", tidyOption("EXCHANGE EARNER'S FOREIGN CURRENCY ACCoUNT (EEFC)"))
        assertEquals("OTHERS", tidyOption("OTHERS:"))
        assertEquals("Male", tidyOption("Male"))
        assertEquals("XYZQWERTYUIOPAS", tidyOption("XYZQWERTYUIOPAS")) // unknown glued word stays as it is
    }
}
