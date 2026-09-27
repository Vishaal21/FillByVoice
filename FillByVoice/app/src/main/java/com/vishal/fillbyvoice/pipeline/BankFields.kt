package com.vishal.fillbyvoice.pipeline

// Common fields of Indian bank and KYC forms, with checked wording.
// Gemma only names a question's field (by id). Code checks that name against the form's own label, then
// decides what the phone asks and which answer check applies. The ids are also the keys for saved values.
data class BankField(
    val id: String,
    val about: String, // what the field means, for Gemma's field list
    val type: String, // the answer check when the form prints no tick-box options
    val hi: String,
    val en: String,
    val label: Regex, // the form's label must match, else Gemma named the wrong field
    val options: List<String>, // read out when the form prints none (tiny box letters like "M F T" are dropped by OCR)
    val byLabel: Boolean, // safe to pick from the label alone (false: the same label can mean another person)
)

private fun field(
    id: String,
    about: String,
    type: String,
    hi: String,
    en: String,
    label: String,
    options: List<String> = emptyList(),
    byLabel: Boolean = true,
) = BankField(id, about, type, hi, en, Regex(label, RegexOption.IGNORE_CASE), options, byLabel)

private const val SKIP_HI = " न हो तो 'छोड़ो' बोलिए।"
private const val SKIP_EN = " Say 'skip' if you have none."

// Order matters when a field is picked from the label alone: the first match wins ("Father's Name" before "Name").
val BANK_FIELDS = listOf(
    // Person
    field(
        "prefix", "Mr / Mrs / Ms before the name", "text",
        "आपके नाम से पहले क्या लिखें?", "What title goes before your name?",
        """^(?!.*account).*(prefix|salutation|\btitle\b)""", listOf("Mr", "Mrs", "Ms"),
    ),
    // Father / spouse / mother need "name" (or "'s", "S/O") nearby: a bare option word like "Father" in a
    // nominee relationship list must never become "what is your father's name?".
    field(
        "father_name", "father's name", "text",
        "आपके पिता का पूरा नाम क्या है?", "What is your father's full name?",
        """father.*name|name.*father|father'?s\b|\bs\s*/\s*o\b|\bd\s*/\s*o\b""",
    ),
    field(
        "spouse_name", "husband's or wife's name", "text",
        "आपके पति या पत्नी का पूरा नाम क्या है?", "What is your husband's or wife's full name?",
        """(spouse|husband|wife).*name|name.*(spouse|husband|wife)|(spouse|husband)'?s\b|\bw\s*/\s*o\b""",
    ),
    field(
        "mother_name", "mother's name", "text",
        "आपकी माँ का पूरा नाम क्या है?", "What is your mother's full name?",
        """mother.*name|name.*mother|mother'?s\b""",
    ),
    field(
        "joint_name", "joint applicant / second account holder's name", "text",
        "संयुक्त खाताधारक (जॉइंट होल्डर) का पूरा नाम क्या है?$SKIP_HI",
        "What is the joint account holder's full name?$SKIP_EN",
        """joint|second\s*holder|2nd\s*holder""",
    ),
    field(
        "maiden_name", "name before marriage", "text",
        "शादी से पहले आपका पूरा नाम क्या था? नाम नहीं बदला तो 'छोड़ो' बोलिए।",
        "What was your full name before marriage? Say 'skip' if it did not change.",
        "maiden",
    ),
    field(
        "first_name", "first name", "text",
        "आपका पहला नाम क्या है?", "What is your first name?",
        """first\s*name|given\s*name""",
    ),
    field(
        "middle_name", "middle name", "text",
        "आपका बीच का नाम क्या है?$SKIP_HI", "What is your middle name?$SKIP_EN",
        """middle\s*name""",
    ),
    field(
        "last_name", "last name / surname", "text",
        "आपका उपनाम यानी सरनेम क्या है?", "What is your last name?",
        """last\s*name|surname""",
    ),
    // "Name of ..." is someone else's name ("6.Name of * Father / Mother / Spouse" was asked as the customer's own
    // name, 09:24 run), except "Name of (the) Applicant / Customer / Account holder".
    field(
        "name", "the customer's full name (Name, Applicant's Name, Account Title/Name)", "text",
        "आपका पूरा नाम क्या है?", "What is your full name?",
        """^(?!.*(father|mother|spouse|husband|wife|joint|nominee|guardian|branch|bank|employer|company|introducer|beneficiary|maiden|first|middle|last|surname|product|name\s*of(?!\s*(the\s+)?(applicant|customer|account)))).*name""",
        byLabel = false,
    ),
    field(
        "birth_place", "place of birth", "text",
        "आपका जन्म किस शहर या गाँव में हुआ था?", "In which city or village were you born?",
        """place\s*of\s*birth|birth\s*place|city\s*of\s*birth""",
    ),
    field(
        "dob", "the customer's date of birth", "date",
        "आपकी जन्म तिथि क्या है? दिन, महीना और साल बताइए।", "What is your date of birth? Say the day, month and year.",
        """^(?!.*nominee).*(birth|\bdob\b|d\.o\.b)""",
        byLabel = false,
    ),
    field(
        "gender", "gender", "text",
        "आपका लिंग क्या है?", "What is your gender?",
        """gender|\bsex\b""", listOf("Male", "Female", "Transgender"),
    ),
    field(
        "marital_status", "marital status", "text",
        "आपकी वैवाहिक स्थिति क्या है?", "What is your marital status?",
        "marital", listOf("Married", "Unmarried", "Others"),
    ),
    field(
        "nationality", "nationality / citizenship", "text",
        "आप किस देश के नागरिक हैं?", "What is your nationality?",
        "nationality|citizenship",
    ),
    field(
        "residential_status", "resident Indian / NRI / foreign national", "text",
        "आपकी निवास स्थिति क्या है?", "What is your residential status?",
        """residential\s*status|residence\s*status|resident\s*status""",
        listOf("Resident Individual", "Non Resident Indian", "Foreign National"),
    ),
    field(
        "religion", "religion", "text",
        "आपका धर्म क्या है?", "What is your religion?",
        "religion",
    ),
    field(
        "category", "social category: General / OBC / SC / ST", "text",
        "आप किस वर्ग से हैं?", "Which category do you belong to?",
        """^(?!.*account).*(category|caste)""", listOf("General", "OBC", "SC", "ST"),
    ),
    field(
        "education", "education / qualification", "text",
        "आपने कहाँ तक पढ़ाई की है?", "What is your highest education?",
        "education|qualification",
    ),
    field(
        "occupation", "occupation / profession", "text",
        "आप क्या काम करते हैं?", "What is your occupation?",
        "occupation|profession|employment",
    ),
    field(
        "annual_income", "yearly income", "number",
        "आपकी सालाना आमदनी कितनी है?", "What is your yearly income?",
        """^(?!.*source).*income""",
    ),
    // Contact
    field(
        "mobile", "mobile number", "mobile",
        "आपका मोबाइल नंबर क्या है?", "What is your mobile number?",
        """mobile|\bcell|(?<!tele)phone""",
    ),
    field(
        "phone", "telephone / landline number", "number",
        "आपका लैंडलाइन फ़ोन नंबर क्या है?$SKIP_HI", "What is your landline number?$SKIP_EN",
        """tele|landline|\btel\b|\bstd\b|phone""",
    ),
    field(
        "fax", "fax number", "number",
        "आपका फ़ैक्स नंबर क्या है?$SKIP_HI", "What is your fax number?$SKIP_EN",
        """\bfax\b""",
    ),
    field(
        "email", "e-mail address", "email",
        "आपका ईमेल पता क्या है?$SKIP_HI", "What is your email address?$SKIP_EN",
        """e\s*-?\s*mail""",
    ),
    // Address
    field(
        "address", "house, street and area (Address, Particulars, Address Line 1)", "text",
        "आपका पता क्या है? मकान नंबर, गली और इलाका बताइए।", "What is your address? Say the house number, street and area.",
        """^(?!.*(nominee|same\s*as|mail)).*(address|particular|line\s*1|house|flat|street)""",
        byLabel = false,
    ),
    field(
        "landmark", "landmark near the house", "text",
        "आपके घर के पास की कोई पहचान वाली जगह बताइए, जैसे मंदिर या स्कूल।",
        "What landmark is near your home, like a temple or a school?",
        """land\s*mark""",
    ),
    // City and state skip lines with "bank" in them: "STATE BANK OF INDIA" and "City Union Bank" are bank names.
    // "State Govt." is an occupation tick-box, not the state (10:12 run, SBI: asked as "आपका राज्य कौन सा है?").
    field(
        "city", "city / town / village", "text",
        "आपका शहर, कस्बा या गाँव कौन सा है?", "What is your city, town or village?",
        """^(?!.*bank).*(city|town|village)""",
    ),
    field(
        "district", "district", "text",
        "आपका ज़िला कौन सा है?", "What is your district?",
        """district|\bdist\b""",
    ),
    field(
        "state", "state", "text",
        "आपका राज्य कौन सा है?", "What is your state?",
        """^(?!.*(bank|govt|government)).*\bstate\b""",
    ),
    field(
        "pincode", "PIN code", "pincode",
        "आपके इलाके का पिन कोड क्या है?", "What is your PIN code?",
        """\bpin\b|pin\s*code|pincode|postal|\bzip\b""",
    ),
    field(
        "country", "country", "text",
        "आपका देश कौन सा है?", "What is your country?",
        "country",
    ),
    // Documents
    field(
        "aadhaar", "Aadhaar number", "aadhaar",
        "आपका आधार नंबर क्या है? बारह अंक बताइए।", "What is your Aadhaar number? Say the 12 digits.",
        """aadha|\buid\b""",
    ),
    field(
        "pan", "PAN (Permanent Account Number)", "pan",
        "आपका पैन कार्ड नंबर क्या है? एक-एक अक्षर और अंक बताइए।",
        "What is your PAN number? Say each letter and digit.",
        """\bpan\b|permanent\s*account""",
    ),
    field(
        "voter_id", "voter ID (EPIC) number", "text",
        "आपके वोटर आईडी कार्ड का नंबर क्या है?$SKIP_HI", "What is your voter ID number?$SKIP_EN",
        """voter|\bepic\b|election""",
    ),
    field(
        "passport", "passport number", "text",
        "आपका पासपोर्ट नंबर क्या है?$SKIP_HI", "What is your passport number?$SKIP_EN",
        "passport",
    ),
    field(
        "driving_licence", "driving licence number", "text",
        "आपके ड्राइविंग लाइसेंस का नंबर क्या है?$SKIP_HI", "What is your driving licence number?$SKIP_EN",
        """driving|licen[cs]e""",
    ),
    // Nominee: the same labels ("Name", "Address") mean another person, so never picked from the label alone.
    field(
        "nominee_name", "nominee's name", "text",
        "आप किसे अपना नॉमिनी बनाना चाहते हैं? उनका पूरा नाम बताइए।",
        "Who do you want as your nominee? Say their full name.",
        "name|nominee", byLabel = false,
    ),
    field(
        "nominee_relation", "nominee's relationship to the customer", "text",
        "नॉमिनी से आपका क्या रिश्ता है?", "How is the nominee related to you?",
        "relation", byLabel = false,
    ),
    field(
        "nominee_dob", "nominee's date of birth", "date",
        "नॉमिनी की जन्म तिथि क्या है?", "What is the nominee's date of birth?",
        """birth|\bdob\b""", byLabel = false,
    ),
    field(
        "nominee_age", "nominee's age", "number",
        "नॉमिनी की उम्र कितनी है?", "How old is the nominee?",
        """\bage\b""", byLabel = false,
    ),
    field(
        "nominee_address", "nominee's address", "text",
        "नॉमिनी का पूरा पता क्या है?", "What is the nominee's address?",
        "address", byLabel = false,
    ),
    field(
        "guardian_name", "guardian of a minor nominee", "text",
        "नॉमिनी नाबालिग हो तो उनके अभिभावक का नाम क्या है?$SKIP_HI",
        "If the nominee is a minor, what is the guardian's name?$SKIP_EN",
        "guardian", byLabel = false,
    ),
    // Account and money
    // No default options: account types differ a lot between forms (Savings, Current, EEFC, Salary...).
    field(
        "account_type", "type of account", "text",
        "खाते का प्रकार क्या है?", "What type of account is it?",
        """type\s*of\s*account|account\s*type|a/c\s*type""",
    ),
    field(
        "account_number", "bank account number", "number",
        "आपका खाता नंबर क्या है?", "What is your account number?",
        """account\s*(no|num)|a\s*/?\s*c\.?\s*no""", byLabel = false,
    ),
    field(
        "ifsc", "IFSC code of the bank branch", "ifsc",
        "बैंक शाखा का आईएफ़एससी कोड क्या है? यह पासबुक या चेक पर लिखा होता है।",
        "What is the IFSC code of the bank branch? It is printed on the passbook or cheque.",
        "ifsc",
    ),
    // Safe by label: OCR sometimes drops the ":" of "Branch:" and Gemma skips the bare word (07:26 run).
    // "Branch Manager" is boilerplate and never reaches here; branch code / use / stamp / seal are not the name.
    field(
        "branch", "bank branch name", "text",
        "बैंक की किस शाखा में? शाखा का नाम बताइए।", "Which bank branch? Say the branch name.",
        """^(?!.*(code|use|stamp|seal)).*branch""",
    ),
    field(
        "amount", "amount in rupees", "number",
        "कितने रुपये? राशि बताइए।", "How many rupees?",
        """amount|rupees|\brs\b|₹""", byLabel = false,
    ),
    field(
        "place", "place where the form is signed", "text",
        "आप यह फ़ॉर्म किस जगह भर रहे हैं? शहर या गाँव का नाम बताइए।",
        "Where are you filling this form? Say the city or village.",
        """^(?!.*birth).*\bplace\b""", byLabel = false,
    ),
    field(
        "date", "date of signing the form", "date",
        "आज की तारीख क्या है?", "What is today's date?",
        """^(?!.*birth).*\bdate\b""", byLabel = false,
    ),
)

private val BY_ID = BANK_FIELDS.associateBy { it.id }

// Short names for the read-back ("नाम: विशाल सिंह") and for "which one to change?". Hindi, then English.
private val SHORT_NAMES = mapOf(
    "prefix" to ("उपाधि" to "Title"), "father_name" to ("पिता का नाम" to "Father's name"),
    "spouse_name" to ("पति या पत्नी का नाम" to "Spouse's name"), "mother_name" to ("माँ का नाम" to "Mother's name"),
    "joint_name" to ("जॉइंट होल्डर" to "Joint holder"), "maiden_name" to ("शादी से पहले का नाम" to "Maiden name"),
    "first_name" to ("पहला नाम" to "First name"), "middle_name" to ("बीच का नाम" to "Middle name"),
    "last_name" to ("सरनेम" to "Last name"), "name" to ("नाम" to "Name"), "birth_place" to ("जन्म स्थान" to "Place of birth"),
    "dob" to ("जन्म तिथि" to "Date of birth"), "gender" to ("लिंग" to "Gender"),
    "marital_status" to ("वैवाहिक स्थिति" to "Marital status"), "nationality" to ("नागरिकता" to "Nationality"),
    "residential_status" to ("निवास स्थिति" to "Residential status"), "religion" to ("धर्म" to "Religion"),
    "category" to ("वर्ग" to "Category"), "education" to ("पढ़ाई" to "Education"), "occupation" to ("काम" to "Occupation"),
    "annual_income" to ("सालाना आमदनी" to "Yearly income"), "mobile" to ("मोबाइल" to "Mobile"),
    "phone" to ("लैंडलाइन" to "Landline"), "fax" to ("फ़ैक्स" to "Fax"), "email" to ("ईमेल" to "Email"),
    "address" to ("पता" to "Address"), "landmark" to ("लैंडमार्क" to "Landmark"), "city" to ("शहर" to "City"),
    "district" to ("ज़िला" to "District"), "state" to ("राज्य" to "State"), "pincode" to ("पिन कोड" to "PIN code"),
    "country" to ("देश" to "Country"), "aadhaar" to ("आधार" to "Aadhaar"), "pan" to ("पैन" to "PAN"),
    "voter_id" to ("वोटर आईडी" to "Voter ID"), "passport" to ("पासपोर्ट" to "Passport"),
    "driving_licence" to ("ड्राइविंग लाइसेंस" to "Driving licence"), "nominee_name" to ("नॉमिनी का नाम" to "Nominee's name"),
    "nominee_relation" to ("नॉमिनी से रिश्ता" to "Nominee relation"),
    "nominee_dob" to ("नॉमिनी की जन्म तिथि" to "Nominee's date of birth"), "nominee_age" to ("नॉमिनी की उम्र" to "Nominee's age"),
    "nominee_address" to ("नॉमिनी का पता" to "Nominee's address"), "guardian_name" to ("अभिभावक का नाम" to "Guardian's name"),
    "account_type" to ("खाते का प्रकार" to "Account type"), "account_number" to ("खाता नंबर" to "Account number"),
    "ifsc" to ("आईएफ़एससी" to "IFSC"), "branch" to ("शाखा" to "Branch"), "amount" to ("राशि" to "Amount"),
    "place" to ("जगह" to "Place"), "date" to ("तारीख" to "Date"),
)

// Whether a field's answer can be a tick-box: a field outside the catalogue, or one of its choice fields.
// Free-text fields (branch, name, city...) never take the next row as options: "Branch Name" got the labels
// "Customer ID / name and code no." below it and was asked as a mix of three questions (09:30 run, SBI form).
fun mayBeChoice(id: String): Boolean {
    val field = BY_ID[id.replace(COPY_SUFFIX, "")] ?: return true
    return field.options.isNotEmpty() || field.id in CHOICE_FIELDS
}

private val CHOICE_FIELDS = setOf("account_type", "occupation", "education", "nationality", "residential_status")

// "city_2" -> ("शहर", "City"). Null for a field outside the catalogue: the form's own label is used instead.
fun shortName(id: String): Pair<String, String>? = SHORT_NAMES[id.replace(COPY_SUFFIX, "")]

// Ids Gemma often writes instead of the listed ones.
private val ALIASES = mapOf(
    "full_name" to "name", "applicant_name" to "name", "customer_name" to "name", "account_holder_name" to "name",
    "account_title" to "name", "account_title_name" to "name", "name_field" to "name", "name_name" to "name",
    "fathers_name" to "father_name", "husband_name" to "spouse_name", "mothers_name" to "mother_name",
    "date_of_birth" to "dob", "birth_date" to "dob", "sex" to "gender", "citizenship" to "nationality",
    "caste" to "category", "qualification" to "education", "profession" to "occupation", "income" to "annual_income",
    "mobile_number" to "mobile", "mobile_no" to "mobile", "phone_number" to "phone", "telephone" to "phone",
    "email_id" to "email", "e_mail" to "email",
    "particulars" to "address", "address_line_1" to "address", "residential_address" to "address",
    "town" to "city", "village" to "city", "pin" to "pincode", "pin_code" to "pincode",
    "aadhaar_number" to "aadhaar", "aadhar" to "aadhaar", "aadhar_number" to "aadhaar", "uid" to "aadhaar",
    "pan_number" to "pan", "pan_no" to "pan", "epic" to "voter_id", "voter_id_number" to "voter_id",
    "passport_number" to "passport", "driving_license" to "driving_licence",
    "nominee_relationship" to "nominee_relation", "type_of_account" to "account_type",
    "account_no" to "account_number", "ifsc_code" to "ifsc", "branch_name" to "branch",
)

// "city_2": the second address on the form. Kept on the question id, ignored for the catalogue.
val COPY_SUFFIX = Regex("""_\d+$""")

// Gemma's id ("mobile_2", "date_of_birth") -> catalogue field, only if the form's label agrees.
fun bankField(id: String, label: String): BankField? {
    val base = id.lowercase().replace(COPY_SUFFIX, "")
    return BY_ID[ALIASES[base] ?: base]?.takeIf { it.label.containsMatchIn(label) }
}

// No usable answer from Gemma: pick a field from the label alone, only where that is safe.
fun bankFieldForLabel(label: String): BankField? =
    BANK_FIELDS.firstOrNull { it.byLabel && it.label.containsMatchIn(label) }

// "आपका लिंग क्या है?" + [पुरुष, महिला, ट्रांसजेंडर] -> "आपका लिंग क्या है? पुरुष, महिला या ट्रांसजेंडर?"
fun withOptions(question: String, options: List<String>, or: String): String = when (options.size) {
    0 -> question
    1 -> "$question ${options[0]}?"
    else -> "$question ${options.dropLast(1).joinToString(", ")} $or ${options.last()}?"
}

// Option words printed on bank forms, in Hindi, so options are read out in Hindi. Unknown words stay as printed.
private val HINDI_OPTIONS = mapOf(
    "mr" to "श्री", "mrs" to "श्रीमती", "ms" to "सुश्री", "miss" to "कुमारी",
    "male" to "पुरुष", "female" to "महिला", "transgender" to "ट्रांसजेंडर", "thirdgender" to "ट्रांसजेंडर",
    "married" to "विवाहित", "unmarried" to "अविवाहित", "single" to "अविवाहित", "widow" to "विधवा",
    "widowed" to "विधवा", "widower" to "विधुर", "divorced" to "तलाकशुदा",
    "yes" to "हाँ", "no" to "नहीं", "others" to "अन्य", "other" to "अन्य", "indian" to "भारतीय",
    "residentindividual" to "भारत के निवासी", "nonresidentindian" to "अनिवासी भारतीय", "nri" to "अनिवासी भारतीय",
    "foreignnational" to "विदेशी नागरिक",
    "general" to "सामान्य", "obc" to "ओबीसी", "sc" to "एससी", "st" to "एसटी",
    "savings" to "बचत खाता", "savingsaccount" to "बचत खाता", "current" to "चालू खाता", "currentaccount" to "चालू खाता",
    "salaried" to "नौकरी", "service" to "नौकरी", "business" to "व्यापार", "selfemployed" to "अपना काम",
    "farmer" to "किसान", "agriculture" to "खेती", "student" to "छात्र", "housewife" to "गृहिणी",
    "homemaker" to "गृहिणी", "retired" to "सेवानिवृत्त", "unemployed" to "बेरोज़गार",
    "son" to "बेटा", "daughter" to "बेटी", "wife" to "पत्नी", "husband" to "पति", "father" to "पिता",
    "mother" to "माँ", "brother" to "भाई", "sister" to "बहन",
)

fun hindiOption(option: String): String = HINDI_OPTIONS[squash(option)] ?: option

// An option as printed, tidied for the phone and the answer sheet: "SPECIALSAVINGACCOUNT" (OCR lost the spaces)
// -> "SPECIAL SAVING ACCOUNT", "ACCoUNT" -> "ACCOUNT", "OTHERS:" -> "OTHERS".
fun tidyOption(text: String): String {
    val t = text.trim().trim('*', ':', ' ')
    val caps = if (t.count(Char::isUpperCase) > t.count(Char::isLowerCase)) t.uppercase() else t
    return caps.split(Regex("""\s+""")).joinToString(" ") { if (it.length > 12) unglue(it) else it }
}

// Splits a long glued word into known option words, or leaves it as it is.
private fun unglue(word: String): String {
    val parts = mutableListOf<String>()
    var rest = word
    while (rest.isNotEmpty()) {
        val part = OPTION_WORDS.filter { rest.startsWith(it) }.maxByOrNull { it.length } ?: return word
        parts += part
        rest = rest.drop(part.length)
    }
    return parts.joinToString(" ")
}

private val OPTION_WORDS = listOf(
    "ACCOUNT", "SAVINGS", "SAVING", "SPECIAL", "CURRENT", "DEPOSIT", "FIXED", "RECURRING", "SALARY", "FOREIGN",
    "CURRENCY", "EXCHANGE", "TYPE", "OF", "JOINT", "SINGLE", "SENIOR", "CITIZEN", "OTHERS", "OTHER", "NON",
    "RESIDENT", "INDIAN", "SELF", "EMPLOYED", "BUSINESS",
)
