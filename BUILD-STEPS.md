# Fill by Voice: Build Steps

> The working plan. `PROJECT-BRIEF.md` is the source of truth for the why. This file is the what, in order.
> Rules:
> - Continue from the **first unchecked part**.
> - A part is done only when its **target** works **on the phone**. Then tick it.
> - Commit after each part (ask Vishal first: he says "commit").
> - No code until Vishal says "go".
> - File paths are relative to `FillByVoice/app/src/main/java/com/vishal/fillbyvoice/` unless shown in full. Names are the plan and may change.

```
 CORE (MVP)        1-9    scan -> questions -> voice -> check -> list on screen
 WOW               10     "What does this mean?"
 OUTPUT            11-12  answers drawn on the same page, PDF Download + Share
 COMPLETE APP      13-16  My Info, Forms history, Settings, polish + demo
 LATER EXTRAS             only if time is left
```

Priority if time is short: **MUST** 1-9. **SHOULD** 10-12 + speed line. **NICE** 13-15. Part 16 always.

## How it works (layers)

```
Photo -> OCR -> Gemma -> [ TTS (phone asks) -> STT (user answers) -> Clean -> Check -> Confirm ] x each question
      -> Final read-back -> Output (filled page + PDF) -> Save
```

| Layer | What happens | Example | Built in |
|---|---|---|---|
| 1. Photo | Camera takes a picture of the form | `photo.jpg` | Part 2 |
| 2. OCR | Pulls the text out of the photo, line by line (with positions) | `0: Enter your full name:` `1: City:` `2: Phone #:` | Part 3 |
| 3. Gemma | Reads the OCR text, makes the question list (line, id, type, Hindi question) | `line 1 -> city, text, "आपका शहर कौन सा है?"` | Parts 4, 5 |
| 4. TTS | The phone **asks** the question out loud | "आपका शहर कौन सा है?" | Part 6 |
| 5. STT | The user answers, voice becomes text | "Hyderabad" -> `हैदराबाद` | Part 6 |
| 6. Clean | Kotlin regex turns the STT text into a proper value. Gemma only if regex fails | `9 8 7 6 5 4 3 2 1 0` -> `9876543210`, `25 March 1990` -> `25/03/1990`, "pichhle saal holi ke din" -> Gemma | Part 8 |
| 7. Check | Kotlin rules decide if the value is valid. FAIL: TTS says what is wrong, back to layer 4 | `12345` as mobile -> "मोबाइल नंबर 10 अंक का होना चाहिए" | Parts 7, 8 |
| 8. Confirm | TTS reads the answer back. "haan" = save, next question. "nahi" = ask again | "9876543210, सही है?" | Part 8 |
| 9. Final read-back | After the last question, TTS reads all answers. "haan" = go on. "nahi" = "कौन सा बदलना है?", user names the field, ask it again. Kotlin only, no Gemma | "नाम: रमेश कुमार. शहर: हैदराबाद. मोबाइल: 9876543210. सब सही है?" | Part 9 |
| 10. Output | Kotlin draws each answer after or below its question on the photo (no user taps; no room = extra page). Two PDFs: filled form (to submit) and answer sheet (to copy by hand). Each: Download, Share | `filled.pdf`, `answers.pdf` | Parts 9, 11, 12 |
| 11. Save | Form folder to the Forms tab. Answers by `id` to My Info, so the next form offers "use this?". Nothing leaves the phone | "आपका नाम रमेश कुमार है, यही इस्तेमाल करें?" -> "haan" -> skipped | Parts 13, 14 |

- Gemma never hears or speaks. It only reads text and writes text. TTS is the phone's mouth, STT is the phone's ears.
- The phone always speaks first, then listens.
- Layers 4 to 8 repeat for every question. Most answers never reach Gemma (STT already gives digits, regex does the rest).
- Layer 7 is "code decides": Kotlin is the final judge, never the model.

---

## Before we start

- [x] Git init at the workspace root + first commit (done by Vishal, 18:56).
- [x] Record the Gemma tests from AI Edge Gallery in `PROJECT-BRIEF.md` section 0.
- [x] Retest the field finder with the few-shot English prompt (test 4b): pass, 2.7 s. Use this prompt style in Part 5.
- [ ] 2 to 3 phone photos of printed pages (one bank form, one plain question sheet).
- [x] Model file in our app's folder (needed by Part 4). Done 16:32 by copying Gallery's file on the phone: `/sdcard/Android/data/com.vishal.fillbyvoice/files/gemma-4-E2B-it.litertlm`.

---

## CORE (MVP)

### Part 1: App skeleton with 4 tabs
- [x] Done (Sat 26 Sep)
- **What**: bottom navigation bar with 4 tabs (icon + word): Scan, Forms, My Info, Settings. Each shows placeholder text.
- **Files**: `MainActivity.kt`, `ui/AppNav.kt`, `ui/ScanScreen.kt`, `ui/FormsScreen.kt`, `ui/MyInfoScreen.kt`, `ui/SettingsScreen.kt`. Maybe `gradle/libs.versions.toml` + `app/build.gradle.kts` (icons).
- **Target**: tap each tab on the phone, its screen shows.

### Part 2: Camera
- [x] Done (Sat 26 Sep)
- **What**: the Scan tab asks for camera permission, shows a live preview and one big capture button, then shows the photo, shrunk to about 2000 px wide.
- **Files**: `camera/CameraCapture.kt`, `ui/ScanScreen.kt`, `AndroidManifest.xml`, `libs.versions.toml`, `app/build.gradle.kts` (CameraX).
- **Target**: a photo of a printed page appears on screen.

### Part 3: OCR
- [x] Done (Sat 26 Sep). Test on a Google Form shown on the laptop: all 4 questions read correctly, 19 lines.
- **What**: ML Kit reads the photo and gives text lines + boxes. A debug view draws a box around each line.
- **Files**: `ocr/OcrReader.kt`, `ui/ScanScreen.kt` (debug view), Gradle files (ML Kit).
- **Target**: on a real page, lines like "Full Name" and "When were you born?" are boxed correctly.

### Part 4: Load Gemma
- [x] Done (Sat 26 Sep, 19:45). Test prompt gave `25/03/1990`, **GPU | 48.7 tokens/sec | on-device**. Needed `chmod 666` on the model file.
- **What**: LiteRT-LM loads the model file (already on the phone, see "Before we start") on GPU, with CPU as fallback. A test prompt button and the speed line.
- **Files**: `llm/GemmaEngine.kt`, a debug button on `ui/SettingsScreen.kt` (or Scan), Gradle files (LiteRT-LM).
- **Target**: the reply shows on screen with `GPU | N tokens/sec`. No crash if the file is missing (an error message instead).

### Part 5: Field finder
- [x] Done (Sat 26 Sep). School form: 4 questions. SBI page: questions found with pass 2 + ask-anyway.
- **What**: numbered OCR lines go to Gemma, which returns a JSON question list (`line`, `id`, `type`, `ask_hi`, `ask_en`). Kotlin parses it, joins each question to its OCR box, retries once, then falls back to lines ending in `?` or `:`. Shows a "Found N questions" screen.
- **Files**: `pipeline/FieldFinder.kt`, `ui/FoundScreen.kt`.
- **Prompt rules** (from tests 4, 4b, 5): instructions in English; a worked example with **different** labels than the test form; "Reply with ONLY a JSON array"; "skip headings and instructions"; "`ask_hi` is a FULL polite spoken question, never copy the label"; "do not skip any question". Test 5 (18-field form, weak prompt) copied labels like "Sheher (City):" instead of a full question.
- **From the Part 3 test**: OCR also returns noise (browser tabs "Questions / Responses / Settings", placeholders "Short answer text", a long URL, bits like "gle", "g") and radio options read as "ONo". ML Kit's line order is not always top-to-bottom, so Kotlin sorts lines by position (top, then left) before numbering them. Gemma must skip noise and placeholders.
- **From the SBI form test**: logo words ("Accessibility", "Leveraging", "Large"...) mixed into the list, single letters from box hints ("A", "M", "R" from "SALUTATION / FIRST NAME" boxes), and office-only fields (Branch Code, Customer ID, Account No). So before Gemma, Kotlin: (1) drops lines of 1 to 2 characters, (2) groups lines into rows (same height on the page) and orders rows top-to-bottom, left-to-right inside a row. Prompt rule: "skip fields marked 'For office use only'".
- **From the first SBI run (107 lines, one call)**: Gemma broke the JSON mid-reply (missing quotes), garbled line numbers (610, 710), made each option word its own question, and turned box hints / notes into questions. Fixes: (1) constrained JSON output (`ResponseFormat.json(schema)` + `enableResponseFormat`), (2) about 25 lines per call (whole rows), merged, (3) no `ask_en` in the reply (English uses the label), (4) prompt example with those traps, (5) Kotlin drops a question whose label is another question's option.
- **From the second SBI run (27 questions, valid JSON, 17.9 tok/s)**: missed 4.Marital Status, Place of Posting, Business, Application Type; boxed "T N" / "M I D D L E N" hints and option words (Father, Mother, Spouse*). Fixes: (1) **pass 2**: Gemma looks again at only the lines pass 1 left unused, (2) **ask anyway**: a still-unused line that looks like a question (numbered "4.", ends with ":" / "?") is asked with its own label ("फ़ॉर्म में लिखा है: ... इसका जवाब बताइए।"), (3) Kotlin drops spaced-letter box hints, (4) option compare ignores `*`, `:` and case. Office fields (Branch Name etc.) stay: the user can say "skip".
- **From the ICICI run (log)**: Gemma's Hindi question was right but its `line` number was sometimes wrong ("acceptance" pointed at "MAB" instead of "I/We have received, read and accepted"), so boxes landed on junk and English mode read the junk label. Fix: Gemma also returns `label` (the line's text); Kotlin keeps `line` only if its text matches the label, else uses the best-matching line, else drops it. Also: Kotlin drops page counters ("1 / 10") and URLs; prompt skips logos and hints ("Maximum 40 characters"); pass 2 is told "most leftovers are NOT questions, [] is fine".
- **`choice` type** (tick boxes / radio / dropdown, e.g. Gender: Male / Female / Third Gender): Gemma returns `"type": "choice"` plus `"options": [...]` taken from the option lines next to the question, and an `ask_hi` that reads the options out ("आपका लिंग क्या है? पुरुष, महिला या थर्ड जेंडर?"). Option lines are not questions themselves.
- **Target**: a real bank form gives 5+ correct questions, each with a full Hindi question (not a copied label), and at least one `choice` question with its options. A plain question sheet also works.

### Part 6: Voice
- [ ] Done
- **What**: language choice on first launch (Hindi / English). TTS speaks a question, waits until it ends, then SpeechRecognizer listens and shows the text.
- **Files**: `voice/Speaker.kt`, `voice/Listener.kt`, `ui/LanguageScreen.kt`, `AndroidManifest.xml` (mic permission).
- **Target**: speak Hindi and see the correct text on screen, offline.

### Part 7: Validators (do in RED via Office Kit Remote PC)
- [ ] Done
- **What**: Kotlin rules for Aadhaar (Verhoeff), PAN, IFSC, mobile, PIN, date, email, plus unit tests. Pure Kotlin, so no phone is needed.
- **Files**: `validate/Validators.kt`, `FillByVoice/app/src/test/java/com/vishal/fillbyvoice/ValidatorsTest.kt`.
- **Target**: all unit tests pass. This part does not depend on Parts 1 to 6, so it can be done early.

### Part 8: Question loop
- [ ] Done
- **What**: for each question: ask, listen, normalise (regex first, then Gemma), validate, read back "sahi hai?", then yes (save, next) or no (ask again). On FAIL, say what is wrong and ask again. After 2 silent tries, skip.
- **Files**: `flow/QuestionLoop.kt`, `pipeline/Normaliser.kt`, `ui/QuestionScreen.kt`.
- **Skip**: the user can say "छोड़ो" / "skip" to any question (office fields, questions that do not apply). No answer is saved for it.
- **`choice` answers**: Kotlin matches the spoken answer to one option (Hindi or English word, e.g. "mahila" / "female" -> `Female`). No match = say the options again and ask again.
- **Target**: 5 questions filled by voice, end to end, including one wrong answer caught by a rule and one `choice` question.

### Part 9: Final read-back + filled list (MVP DONE)
- [ ] Done
- **What**: after the last question, the phone reads all answers and asks "सब सही है?". "nahi" = "कौन सा बदलना है?", the user names the field, and it is asked again. Then a big-text list of question and answer.
- **Files**: `flow/QuestionLoop.kt`, `ui/ResultScreen.kt`.
- **Target**: the phone reads all answers and the list shows. **The MVP is done. Commit.**

---

## WOW

### Part 10: "What does this mean?"
- [ ] Done
- **What**: while a question is being asked, the user asks "IFSC kya hai?". Kotlin spots a question ("kya hai", "matlab", "what is"), Gemma explains the field in simple Hindi or English, TTS speaks it, then the same question is asked again.
- **Files**: `pipeline/Explainer.kt`, `flow/QuestionLoop.kt`.
- **Target**: "IFSC kya hai?" gets a correct, simple Hindi answer, then the loop continues.

---

## OUTPUT

### Part 11: Answers drawn on the same page
- [ ] Done
- **What**: Kotlin draws each answer right of its question box, or below it, using Bitmap + Canvas. If there is no room, the answer goes only to the list. No user taps.
- **Files**: `output/PageRenderer.kt`, `ui/ResultScreen.kt`.
- **Target**: on a bank form and on a plain sheet, the answers appear next to the right questions without overlapping text.

### Part 12: PDF Download + Share
- [ ] Done
- **What**: two separate PDFs (PdfDocument), each with Download (to Downloads via MediaStore) and Share (FileProvider):
  - **Filled form PDF** (to submit): the form photo with answers drawn in. Any answer with no room on the page goes on an extra page. Print, sign, submit where printouts are accepted.
  - **Answer sheet PDF** (to copy by hand): only question + answer, big text, BLOCK letters where the form asks. The user fills the real paper form from it.
- **Files**: `output/PdfExporter.kt`, `ui/ResultScreen.kt`, `AndroidManifest.xml` + `res/xml/file_paths.xml` (FileProvider).
- **Target**: both PDFs show in the Files app and open in WhatsApp / Gmail share.

---

## COMPLETE APP

### Part 13: My Info (profile)
- [ ] Done
- **What**: saves answers by `id` in DataStore. On the next form, known fields are offered as "Yeh use karein?" (use this?). The tab lists entries with edit / delete, and Aadhaar is masked as `XXXX XXXX 1234`.
- **Files**: `data/ProfileStore.kt`, `ui/MyInfoScreen.kt`, `flow/QuestionLoop.kt`.
- **Target**: the second form skips known fields with a single "haan".

### Part 14: Forms history
- [ ] Done
- **What**: each finished form is saved as a folder (`photo.jpg`, `answers.json`, `filled.pdf`). The Forms tab lists them, and tapping one reopens the result screen.
- **Files**: `data/FormStore.kt`, `ui/FormsScreen.kt`.
- **Target**: a past form reopens after restarting the app.

### Part 15: Settings & Privacy
- [ ] Done
- **What**: language switch, "All data stays on this phone" note, "Delete all my data" (with confirmation).
- **Files**: `ui/SettingsScreen.kt`, `data/ProfileStore.kt`, `data/FormStore.kt`.
- **Target**: the language switch changes the questions. Delete clears My Info and Forms.

### Part 16: Polish + demo
- [ ] Done
- **What**: README with attributions (LiteRT-LM, Gemma license, ML Kit, CameraX), a crash check, airplane mode test, backup video, Office Kit screen mirror, and 3 rehearsals. Submit on Reskilll.
- **Files**: `README.md` (workspace root).
- **Target**: the full demo runs 3 times in airplane mode without a crash.

---

## LATER EXTRAS (only after the parts above work)

- [ ] **Fill the original PDF form** (first extra: removes hand copying for downloadable bank forms). User opens the bank's PDF in our app -> `PdfRenderer` turns pages into images -> same OCR, Gemma, voice, checks -> answers written INTO the original PDF at exact positions (fillable PDF fields filled directly) with PdfBox-Android (Apache 2.0) -> print, sign, submit.
- [ ] **Tap to add a missed question** (for helpers / Bank Mitra): on the result screen, tap any text on the photo, the app adds it and asks it by voice.
- [ ] **Multi-page forms** (Vishal's idea, high value: the SBI form is 7 pages). Scan page 1, answer it, "Agla page scan karein?", scan page 2, and so on. One PDF with all pages. Profile answers skip repeats.
- [ ] Gemma 4 E4B upgrade if Hindi numbers are weak.
- [ ] NPU backend if the organisers provide a ready setup.
- [ ] Hindi (Devanagari) OCR for pages printed in Hindi.
- [ ] Scan an Aadhaar / PAN card photo to auto-fill fields.
- [ ] Hindi translation of each English question shown on screen.
