# Prompt for the next AI session (copy everything below the line)

---

You are helping me (Vishal Singh) build **Fill by Voice**, a native Android app, at the **iQOO Hackathon 2026, Hyderabad City Battle** (live now, Sat 26 to Sun 27 Sep 2026). Solo, working-professional bucket, FinTech track.

**Read these first, fully:** in `/Users/vishalsingh/Documents/Developer/Iqoo-Hackathon/`
1. `PROJECT-BRIEF.md`: source of truth (event, scoring, product, architecture, data shapes, test results, risks, demo).
2. `BUILD-STEPS.md`: the working plan. 11 flow layers at the top, then Parts 1 to 16 with targets and checkboxes, plus later extras. Continue from the first unchecked part.

Then reply with a 5-line status and the exact next step. Do not write code until I say "go".

## How to work with me
- I am strong in Go backend and AI. **New to Android/Kotlin.** Compare to Go where it helps.
- Explain in the order **What, How, Why**. Short bullets, simple English, **no em-dashes**. ASCII diagrams welcome.
- When I ask to understand something: a one-line map first, then **one small step at a time**, wait for "next".
- Code: **neat and minimal**, no extra code, no new libraries unless needed, match existing style. Full working code, name the file.
- Build on the laptop before asking me to run:
  `cd FillByVoice && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug -q`
  I click Run in Android Studio (Sync Now when Gradle files change).
- **I commit manually.** Never commit, never remind me to say "commit".
- Update `BUILD-STEPS.md` / `PROJECT-BRIEF.md` when a part is done or a decision is made (tick the part, record test results).

## Hard rules
- **The model reads, code decides.** Validation and verification are Kotlin, never the model.
- **Everything on-device.** No cloud, no OpenRouter in the app. Camera + voice + on-device model in one loop.
- No crashes: try/catch around model, OCR, voice (HackTracker logs crashes).
- **Never clear the app's data or uninstall it**: that deletes the model file.

## Status (Sat 26 Sep, 22:05, red window R3 until 01:00)
- Parts 1 to 5 done on the phone. **Part 6 (voice) built, not confirmed yet.**
- Only one git commit exists ("initial commit"). Parts 2 to 6 are uncommitted (I will commit).
- Last Part 6 test: "Ask question 1" showed "nothing heard" in **English** mode. Likely cause: offline English pack on the phone is **en-US**, app asks **en-IN**. Latest build shows the real error as "Speech error N". I must test in **Hindi** (Settings tab has हिंदी / English switch) and report the code or the heard text. Then test airplane mode.
- Latest field-finder fixes (label check, page-counter/URL filter, pass-2 note) are built but not yet tested on the ICICI page.

## What exists (code under `FillByVoice/app/src/main/java/com/vishal/fillbyvoice/`)
| File | What it does |
|---|---|
| `MainActivity.kt` | Starts the app, shows `FillByVoiceApp()` |
| `ui/AppNav.kt` | Language gate (first launch) + 4 tabs: Scan, Forms, My Info, Settings |
| `ui/LanguageScreen.kt` | हिंदी / English choice |
| `ui/ScanScreen.kt` | Camera, then photo -> OCR -> Gemma -> "Found N questions" list, red boxes on questions, speed line, "Ask question 1" voice test |
| `ui/SettingsScreen.kt` | Language switch + "Test Gemma" debug button |
| `ui/FormsScreen.kt`, `ui/MyInfoScreen.kt` | Placeholders |
| `camera/CameraCapture.kt` | CameraX 1.6.2, photo about 1920x2560, upright |
| `ocr/OcrReader.kt` | ML Kit text-recognition 16.0.1 (bundled, offline): lines + boxes |
| `llm/GemmaEngine.kt` | `object Gemma`: LiteRT-LM 0.16.1, GPU then CPU, `ask(prompt, jsonSchema?)` with constrained JSON, real tokens/sec from benchmark |
| `pipeline/FieldFinder.kt` | OCR lines -> questions (details below) |
| `voice/Language.kt` | HINDI (hi-IN) / ENGLISH (en-IN), saved in SharedPreferences |
| `voice/Speaker.kt` | TextToSpeech, `speak()` returns when done talking |
| `voice/Listener.kt` | SpeechRecognizer once, prefer offline, returns text / null, throws "Speech error N" |

**Field finder pipeline** (`findQuestions`):
1. Kotlin drops crumbs (<3 chars), spaced box hints ("T N"), page counters ("1 / 10"), URLs.
2. Kotlin groups lines into rows, sorts top-to-bottom, chunks about 25 lines (whole rows).
3. Gemma per chunk: English few-shot prompt, **JSON schema forced** (`ResponseFormat.json` + `enableResponseFormat`), returns `line, label, id, type, options, ask_hi` (Hindi in Devanagari). No `ask_en`: English mode speaks the cleaned form label.
4. Kotlin `findLine`: trusts `line` only if its text matches `label`, else best-matching line, else drops.
5. Pass 2: Gemma looks again at only the unused lines (with "most are NOT questions, [] is fine").
6. Kotlin: drop option words as questions, then "ask anyway" any unused line that looks like a question (numbered "4." or ends ":" / "?").
7. Debug `Log.d` of each chunk (tag `FieldFinder`), remove in Part 16 polish.
- Types: text, number, date, mobile, email, pincode, aadhaar, pan, ifsc, choice (with options).

## Key facts
- Phone: iQOO 15 (adb serial `10BFAT22EQ000XQ`), Android 16. adb: `~/Library/Android/sdk/platform-tools/adb`. Shell hook `rtk` may mangle grep/cat: use `rtk proxy <cmd>`.
- Model file: `/sdcard/Android/data/com.vishal.fillbyvoice/files/gemma-4-E2B-it.litertlm` (copied from AI Edge Gallery with `adb shell cp`, then **`chmod 666`** was needed). Measured: **GPU 48.7 tok/s** plain, about **18 tok/s** with forced JSON.
- Firecrawl is out of credits: use WebSearch / WebFetch.
- Check real library APIs from the jar with `javap` (Gradle cache) instead of guessing.

## Decisions made this session (all in the brief / build steps)
- Input: any printed page (form or plain question sheet). One tap only (camera). User never taps to place answers.
- Output: **two PDFs**: filled form (to submit) and answer sheet (to copy by hand). Download + Share.
- `choice` type is in the MVP (Parts 5 and 8). User can say "छोड़ो" / skip any question (Part 8).
- Gemma is stateless per call; Kotlin keeps session state (answers, profile, multi-page later).
- Later extras: fill the original PDF (first), tap-to-add a missed question, multi-page forms, Hindi OCR, E4B, NPU. Phase 2: online forms (WebView + DOM).

## Next steps
1. Finish Part 6: Hindi voice test ("Heard:" correct), then airplane mode. If English fails with code 12/13, use `en-US` for recognition.
2. Part 7: Kotlin validators + unit tests (Aadhaar Verhoeff, PAN, IFSC, mobile, PIN, date, email).
3. Part 8: question loop (ask, listen, regex then Gemma normaliser, validate, "सही है?", skip, choice matching).
4. Part 9: final read-back "सब सही है?" + big-text list = MVP. Then Parts 10 to 16.
