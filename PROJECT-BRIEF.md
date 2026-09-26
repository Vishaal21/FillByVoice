# Fill by Voice: Project Brief

> The single source of truth for this project. Read it fully before doing anything. The build parts (what to build, in order, with a target check each) live in `BUILD-STEPS.md`.
> Old name: **Form Saathi** (old deck `Form-Saathi-deck.pdf`, source `deck-source.html`, not in this folder). New name: **Fill by Voice**.
> Last updated: Sat 26 Sep 2026, 16:20. Sources: printed event booklet, event website, web research, decisions made during the event.

## Contents

[0 Right now](#0-right-now) · [1 Product](#1-what-fill-by-voice-is) · [2 Event](#2-the-event) · [3 Builder + AI rules](#3-who-is-building-it-and-rules-for-ai-helpers) · [4 User flow](#4-user-flow) · [5 Architecture](#5-architecture) · [6 Tech stack](#6-tech-stack) · [7 Data shapes](#7-data-shapes) · [8 Validation](#8-validation-rules-kotlin-not-the-model) · [9 Build plan](#9-build-plan) · [10 Risks](#10-risks-and-fallbacks) · [11 Demo](#11-demo-script-3-to-5-min) · [12 Open questions](#12-open-questions) · [13 Sources](#13-sources)

---

## 0. Right now

- **The event is live** (Sat 26 to Sun 27 Sep 2026). App code is allowed. All app code must be written inside the event window.

| Item | State |
|---|---|
| Android Studio + SDK + adb, phone authorised, "Hello Android" ran | Done |
| Office Kit connected, Remote PC works | Done |
| AI Edge Gallery + Gemma 4 E2B downloaded on the phone | Done |
| Offline Hindi + English speech packs, Hindi TTS voice | Done (14:05) |
| Gemma tests in AI Edge Gallery (below) | Done 16:27. All pass with the few-shot English prompt |
| Test forms | Google Form, SBI and ICICI account opening PDFs (shown on the laptop screen). Demo needs a printed form |
| Git | Repo at workspace root, 1 commit (Vishal commits manually) |
| App code (22:05) | Parts 1 to 5 done on the phone. Part 6 (voice) built, test pending. See `BUILD-STEPS.md` |

Gemma 4 E2B test results (AI Edge Gallery, GPU, Sat 16:21):

| Test | Input given | Output | Time | Verdict |
|---|---|---|---|---|
| 1. Date | Spoken in Hindi via the keyboard mic, which typed "25 March 1990" | `25031990`, then `25-03-1990` | 658 ms, 340 ms | Value right, format wrong (no `/`) |
| 2. Digits | Spoken in Hindi via the keyboard mic, which typed "9 8 7 6 5 4 3 2 1 0" | `9876543210` | 343 ms | Right |
| 3. IFSC kya hai | Hindi explain in 2 lines | Good Hindi, but long (not 2 lines) and says "11 अंक" (it is 11 characters: letters + digits) | 4.3 s | Useful, needs a tight prompt + facts |
| 4. Field finder | 5 numbered lines, one paragraph, Hinglish prompt, no example | Kept heading + instruction lines, "When were you born?" typed as `text`, `ask_hi` not translated | not seen | **Weak** |
| 4b. Field finder | Same lines (still one paragraph), **English prompt + one example** (few-shot) | Lines 1, 2, 3 only; `text`, `date`, `mobile`; good Hindi questions | 2.7 s | **Pass. This prompt style is the plan** |

What this changes:
- Short replies are fast (about 0.35 s), so the normaliser is cheap.
- **Speech recognition already turns spoken Hindi numbers into digits** (tests 1 and 2 were spoken). So Kotlin regex does most normalising, and Gemma is the fallback for leftovers. Check this again in Part 6 with our app's offline SpeechRecognizer in airplane mode (the keyboard mic may have used online recognition).
- **Kotlin formats the output** (e.g. any date to `DD/MM/YYYY`). The model is not trusted for format.
- **Explainer**: give Gemma the fact in the prompt (e.g. "IFSC = 11 characters, on passbook / cheque") and cap it at 2 short sentences.
- **Field finder works with a few-shot English prompt** (test 4b: 2.7 s, all correct). Prompt rules: instructions in English, one worked example, "Reply with ONLY a JSON array". Keep cheap Kotlin backups anyway: keyword rules for `type` (born / DOB = date, phone / mobile = mobile, Aadhaar, PAN, IFSC, PIN, email) and Hindi question templates per type. E4B only if real forms break it.

---

## 1. What Fill by Voice is

- A native Android app that helps people fill paper financial forms by voice, in their own language, fully on the phone (no server, no internet).
- Pitch line: **Paper form in. Filled form out. By voice, in your language, on your phone.**
- **Input**: a photo of a paper form (bank account opening, KYC, loan, insurance) **or any page of printed questions** (a plain sheet works, no boxes needed).
- The phone reads the page, asks each question aloud, listens, checks the answer, and **writes the answers onto the same page**.
- **Output: two separate PDFs**, each with Download + Share (decided 26 Sep):
  - **Filled form PDF** (to submit): the same page with answers drawn in. Print, sign, submit where printouts are accepted. Answers with no room go on an extra page, never lost.
  - **Answer sheet PDF** (to copy by hand): only questions + answers in big text. The user (or a helper) fills the real paper form from it.
- Why people use it even when copying: the hard part of a form is knowing **what** to write (Hindi explanations, English form) and getting it **right** (checks catch a wrong Aadhaar / IFSC / date before the bank rejects it). Main user: Bank Mitra / CSC agents, for whom the app is a Hindi interviewer + checker.
- No-copying upgrades: **fill the original PDF form** (first later extra) and **online forms** (Phase 2).
- **The user never taps to place anything.** They only listen and speak. The one tap is the camera button.
- **Languages: Hindi + English only, for now.** Telugu is dropped to save time. All examples here and in `BUILD-STEPS.md` use Hindi or English only.
- Users (for the pitch):
  - Villagers and Hindi-first people who cannot read English forms.
  - Urban users: elderly parents, anyone uneasy with English paperwork.
  - **Bank helpers (Bank Mitra / CSC agents)** who fill many forms a day. They are the "would someone keep using it" answer.

**Phase 2 idea (not in this build): online forms.** The user opens a form URL inside our app (Android **WebView**). JavaScript reads the DOM (`<label>`, `<input>` types, `<select>` options), Gemma turns labels into questions (same field finder, no OCR), the same voice loop runs, JavaScript writes each answer into its `<input>`, and **the user taps Submit** (we never submit for them).
- Easier than paper in one way: the DOM gives exact inputs, types and dropdown options, so no OCR and no position guessing.
- Hard cases: React-style sites need special JS to accept values; captchas / OTP / logins cannot be voice-filled; some sites block WebView; needs internet (weakens the airplane-mode story, though Gemma stays on-device).
- Use: pitch "what's next" line ("paper or online, fill any form by voice") and the Grand Finale (Open Innovation / Community App).

---

## 2. The event

### 2.1 Basics

- **iQOO Hackathon 2026, Hyderabad City Battle** (iQOO x Reskilll). 30 hours: check-in about **Sat 08:00**, awards about **Sun 17:00**.
- Series: 4 city battles (Bengaluru 29 to 30 Aug, Pune 5 to 6 Sep, Chennai 12 to 13 Sep, **Hyderabad 26 to 27 Sep**) + Grand Finale (Bengaluru, 9 to 11 Oct 2026, 48 hours, Friday evening to Sunday evening). Total prize pool Rs 40,00,000.
- Over 5,000 registered; only the top 120 builders got into the City Battles.
- Track: **FinTech and Commerce** (payments, lending, financial inclusion).
- Bucket: **working professional** (scored separately from students, with its own prize pool). Prizes per city (Reskilll blog, check at venue): 1st Rs 1.5L, 2nd Rs 1L, 3rd Rs 70K.

### 2.2 Red Light / Green Light

- **GREEN**: use anything (phone, laptop, any setup). **RED**: direct laptop use is restricted and monitored. Work on the phone, or reach the laptop **only through Office Kit on the phone** (remote control, screen mirror). Split is about 55% green, 45% red.

```
 SAT 26 SEP (Day 1)                                          SUN 27 SEP (Day 2)
 11:00   14:00  15:30  16:30     19:00     22:00      01:00         06:30     09:00
   |=======|------|=====|---------|=========|----------|=============|---------|
   | GREEN | RED  |GREEN|   RED   |  GREEN  |    RED   |    GREEN    |   RED   |
   |  3h   | 1.5h | 1h  |  2.5h   |   3h    |    3h    |    5.5h     |  2.5h   |
     G1      R1     G2     R2        G3         R3          G4           R4
 After Sun 09:00: not in the booklet. Times may change at the venue.
```

- Total: about 12.5 h green, 9.5 h red (up to Sun 09:00). Round 1 is **Saturday evening**, Round 2 **Sunday morning** (exact times unknown).
- In red you can still code: Office Kit Remote PC on the phone controls the laptop. Slower, but it counts toward the Office Kit score. Plan red windows for phone-suited work: testing on real pages, voice tests, prompt tuning, rehearsal, small Kotlin edits.

### 2.3 Scoring (tie every decision back to this)

| Line | Weight | Measured by | They look for (official) | How we score it |
|---|---|---|---|---|
| End product quality | 30% | Jury | Does it work, is it useful, would someone keep using it | Full loop on a real paper form. Profile makes the second form faster |
| Novelty and impact | 20% | Jury | Originality and real-world impact | Other apps read the form to you. We fill it. Helps people who pay agents today |
| Creative phone use | 15% | **HackTracker** | Camera, voice, on-device AI in the build | Camera + mic + speaker + on-device model in one loop, used for real |
| Technical depth | 15% | Jury | Architecture, code quality, robustness, real use of the hardware | Clean pipeline, Kotlin rules, no crashes, retries and fallbacks, model on GPU, tokens/sec on screen |
| Office Kit usage | 10% | **HackTracker** | Phone and laptop bridge use | File transfer, clipboard, Remote PC in red, screen mirror in the demo |
| Demo and presentation | 10% | Jury | A compelling 3 to 5 minute pitch | Live on the iQOO 15, real form, spoken Hindi (section 11) |

- Jury total 75%, HackTracker (device data) 25%. You cannot claim "phone first" if the data says otherwise.
- Booklet "Tip to win": apps that run locally and on-device, including the backend, with on-device LLMs, get brownie points. **The most on-device builds are preferred for the Top 10.**
- Top 6 per city go to the Finale (3 student + 3 working professional); standout teams beyond can also get slots. The prize pool also covers **special track awards**.

**Scoring checklist.** Every build task should tick at least one box. If it ticks none, skip it.
- **End product (30%)**: [ ] full loop on a real printed bank form (scan, ask, answer, filled list) · [ ] 5+ fields without restarting · [ ] big-text filled list · [ ] PDF save and share · [ ] profile: second form asks only new fields
- **Novelty (20%)**: [ ] the app **fills** the form, not just reads it · [ ] answers drawn on the same page next to each question, no user taps · [ ] Hindi voice in and out · [ ] impact line in the pitch: people pay agents today
- **Phone use (15%)**: [ ] camera (CameraX) · [ ] mic (SpeechRecognizer) · [ ] speaker (TextToSpeech) · [ ] on-device model on every form (Field finder + Normaliser + "What does this mean?") · [ ] use the loop a lot while testing (HackTracker counts real usage)
- **Tech depth (15%)**: [ ] one file or class per pipeline box · [ ] all validation in Kotlin · [ ] bad-JSON retry, OCR fallback, try/catch around the model · [ ] model on GPU, backend + tokens/sec shown · [ ] works in airplane mode
- **Office Kit (10%)**: [ ] file transfer (APKs, test photos) · [ ] shared clipboard (logs, model output) · [ ] Remote PC in every red window · [ ] screen mirror in the demo
- **Demo (10%)**: [ ] rehearsed 3 times on the iQOO · [ ] one planned validation error (wrong Aadhaar) · [ ] airplane mode on live · [ ] backup video
- **Bonus**: [ ] zero network calls in the app (no OpenRouter, no cloud)

### 2.4 HackTracker

- Pre-installed on the loaner, runs the whole time. It **reads model outputs and logs inference calls, tokens and thermals in real time** ("the 30 hours become a record rather than a claim"). Counts and durations only: no keystrokes, screenshots or browsing.
- Feeds the "creative phone use" and "Office Kit" scores.
- **Do not tamper with it.** Crash and tamper logs are detected and penalised. Locked out? Go to the organisers.
- **Still unknown**: how it detects our model's inference (SDK, log format, special API?). Ask the organisers.

### 2.5 Office Kit

- Links phone and laptop: **screen mirror, shared clipboard, file transfer, remote control**. Comes paired with the loaner (pairing covered at the Sat 10:00 teach-in). Laptop app from `pc.vivoglobal.com`.
- Office Kit and Remote PC are **the same app**. Remote PC is one feature (others: Phone Mirroring, File Manager, Free transfer, Clipboard sync). Every feature counts toward the 10%.
- Our setup (verified): Mac app `/Applications/pcsuite.app` ("vivo Office Kit"), connected over USB, Screen Recording + Accessibility permissions given, Remote PC works.
- Real use: file transfer (APKs, form photos), clipboard (logs, model output), Remote PC (coding in red), screen mirror (demo).

### 2.6 The device: iQOO 15

| Part | Spec | What it means for us |
|---|---|---|
| Chip | Snapdragon 8 Elite Gen 5 (SM8850) + Q3 chip | Fast CPU and GPU. NPU is hard to use (section 6) |
| RAM | 16 GB LPDDR5X (confirmed) | Gemma 4 E2B needs under 1 GB on GPU. Lots of room |
| Storage | 512 GB UFS 4.1 (confirmed) | 2.6 GB model file is no problem |
| Software | Android 16, OriginOS 6 (confirmed) | Supported by LiteRT-LM, CameraX, ML Kit. User taps "Allow" for camera and mic |
| Screen | 6.85 inch 2K AMOLED, 144 Hz | Good for the big-text list and the page photo |
| Camera | 3 x 50 MP (main Sony IMX921) | **Shrink the photo to about 2000 px wide before OCR**, or it gets slow |
| Cooling | 14,000 mm2 vapour chamber | Many model runs without slowing down |
| Battery | 7,000 mAh, 100 W | Fine for the day. Chargers and wires on request |

- One phone per person. It belongs to iQOO, stays in the venue, and must be returned. Comes with HackTracker installed and Office Kit paired.

### 2.7 Build rules

- **Original work only**: code written during the event window, no pre-built product. Organisers may check this, so commit often.
- **Open-source libraries and frameworks are fine, with attribution** (list LiteRT-LM, ML Kit, CameraX, Gemma license and so on in the README). Bringing a finished app is not.
- Submit **repo + demo assets on Reskilll before the hard cutoff**. Repos are locked before the Top 10 pitches. Late submissions may lose points or be disqualified.
- Stacks allowed: native Android, Flutter, React Native, PWA. We use **native Android (Kotlin)**.
- OpenRouter and "AI credits for the weekend" are free but **only for help while coding, never inside the app**.

### 2.8 Venue, safety and media

- Report on time; check-in closes per the city schedule. Stay in the zones (hacking floor, rest area, meal zone). No smoking, no alcohol, keep noise down.
- First aid on site; in an emergency, alert the nearest organiser. **Take breaks**, especially at night. Tell a volunteer if you step out after dark.
- Respect everyone, zero tolerance for harassment, help others debug. **Do not film or share another team's screen or build** without permission. Event filming is covered by consent at check-in.
- Technical issue before your slot: tell an organiser right away. Contacts: any organiser or volunteer, the direct contact from check-in, the query desk (open until close), series contact `sameera@reskilll.com`. More info: `iqoo-blr.reskilll.com`.

### 2.9 Tracks and the Finale

- City battles have 7 tracks. **The Finale has 6 and drops FinTech and Commerce**, Smart Education and HealthTech, adding Mobility and Community App. Tracks are broad domains; **Open Innovation** is the wildcard.
- If we reach the Finale, move to Open Innovation or Community App. You can also register directly for the Finale.
- Organisers' summary: "Build phone-first. Compete for your city. Advance to the finale. Be good to each other."

---

## 3. Who is building it, and rules for AI helpers

- **Vishal Singh**, solo, working-professional bucket. Strong in **Go backend** and AI engineering. **New to Android and Kotlin.**
- How to explain:
  - Order every concept **What, then How, then Why**.
  - Short bullets, simple English, **no em-dashes**. ASCII diagrams welcome.
  - Compare to Go where it helps: Gradle = go.mod + go build, coroutines = goroutines, APK = binary, adb = SSH-like tool to the phone, MainActivity = main().
- How to work:
  - Give full, working code, never fragments. Name the exact file and explain in plain terms.
  - **One small step at a time.** Wait for Vishal to confirm it runs on the phone.
  - "Discuss first" means write no files. No app code until Vishal says **"go"**. No git commit until he says **"commit"**. Ask before big or hard-to-undo actions.
  - Follow `BUILD-STEPS.md`: continue from the first unchecked part, and tick a part only after its target works on the phone.
  - In red windows, suggest phone-friendly tasks.
- Hard rules for the app:
  - Always keep **camera + voice + on-device model in one loop** (the scoring core).
  - **The model reads, code decides.** Validation is always Kotlin.
  - **Everything on-device.** No cloud APIs or OpenRouter in the app.
  - Wrap every model call (load, prompt, parse) in try/catch. Crash logs are detected.

### 3.1 Setup (verified 26 Sep)

- **Mac**: MacBook Pro, M4 Pro, 48 GB RAM, macOS 26.2, arm64, USB-C / Thunderbolt only, Homebrew at `/opt/homebrew`.
- **Android Studio**: Quail 4, 2026.1.4 Patch 1 (`brew install --cask android-studio`). **SDK**: `~/Library/Android/sdk`; `adb` at `~/Library/Android/sdk/platform-tools/adb` (on PATH via `~/.zshrc`).
- Shell has a command-rewriting hook (`rtk`). If `grep` / `find` output looks mangled, use `rtk proxy <cmd>`.
- **Phone**: iQOO 15 (reports as **vivo I2501**, adb serial `10BFAT22EQ000XQ`), SM8850, 16 GB, 512 GB, Android 16 (API 36), arm64-v8a. USB-C to USB-C cable, USB debugging authorised.
  - Installed: HackTracker (`com.reskill.hacktracker`), Office Kit (`com.vivo.pcsuite`), Google TTS, Google app, AI Edge Gallery (`com.google.ai.edge.gallery`), our app (`com.vishal.fillbyvoice`).
  - Speech: default recogniser is Google. Offline packs for Hindi (India) and English (US), plus the Hindi TTS voice.
- **Project**: `/Users/vishalsingh/Documents/Developer/Iqoo-Hackathon/FillByVoice/` (open THIS folder in Android Studio). Empty Activity (Compose), app name "Fill by Voice", package `com.vishal.fillbyvoice`. minSdk 31, compileSdk 37, targetSdk 37, Kotlin DSL. `gradle/libs.versions.toml`: AGP 9.4.1, Kotlin 2.2.10, Compose BOM 2026.02.01. Main file `app/src/main/java/com/vishal/fillbyvoice/MainActivity.kt`, theme in `ui/theme/`.
- **Workflow**: the AI writes code into `FillByVoice/` from VS Code. Vishal clicks Run in Android Studio (directly in green, via Office Kit Remote PC in red). The APK goes to the phone over USB or Office Kit; the model file lives in phone storage.
- **Git**: not initialised yet. The repo goes at the workspace root (brief, build steps, project).

---

## 4. User flow

1. **Scan**: photo of the page (form or plain sheet). The only tap.
2. **Understand**: OCR reads each line and its position. Gemma picks out the questions, the answer type for each, and a short question to speak. Kotlin joins each question to its OCR position.
3. **Ask**: one question at a time, spoken aloud in the chosen language. The user speaks the answer. If the user asks "IFSC kya hai?", Gemma explains the field in simple Hindi, then the question is asked again.
4. **Fill**: regex first, then Gemma, turns words into a clean value ("pachees March unnees sau nabbe" becomes `25/03/1990`). Kotlin rules check it. The phone reads it back: "sahi hai?"
5. **Final read-back**: after the last question, the phone reads all answers ("Sab sahi hai?"). Kotlin only, no model.
6. **Show**: the same page with answers drawn in automatically, plus a big-text list, **Download PDF** and **Share**. The profile saves answers, so the next form offers "use this?".

**Tabs** (bottom bar, icon + word): `[Scan] [Forms] [My Info] [Settings]`
- **Scan**: camera, starts the voice flow. The bar hides during the flow.
- **Forms**: history. Each form is a folder (`photo.jpg`, `answers.json`, `filled.pdf`). Tap to reopen.
- **My Info**: the saved profile, with edit / delete. Aadhaar is masked as `XXXX XXXX 1234`.
- **Settings & Privacy**: Hindi / English, "All data stays on this phone", "Delete all my data". Language is also asked once on first launch.

**Scan flow screens:**
```
 Choose language --> Camera --> "Found N questions" --> Question k/N (x N) --> "Sab sahi hai?" --> Same page + answers
 (first launch)      [snap]      Name, DOB ...           ask, listen,                              + big list
                                                         "sahi hai?"                               [Download PDF] [Share]
```
- A question is skipped if the profile has it (the user just says "haan").
- **No taps to place answers.** Kotlin draws each answer in the empty space after or below its question. With no room, the answer goes on an extra page of the filled form PDF (and is always in the answer sheet PDF).
- The real paper stays empty. The user prints the PDF, shares it, or copies from the list.
- Testing can use a page shown on the laptop screen. **The demo uses a printed paper form.**

**Voice loop for one question:**
```
 TTS asks --> STT listens --> clean (regex, then Gemma) --> Kotlin rule --PASS--> "25 March 1990, sahi hai?" --haan--> save, next
                 ^                                              |                          |
                 +------ FAIL: TTS says what is wrong ----------+                          +--nahi--> ask again
```

---

## 5. Architecture

```
 Camera --> OCR --> Field finder --> Voice Q&A --> Normaliser --> Validator --> Renderer
 CameraX    ML Kit   Gemma            STT + TTS     Gemma          Kotlin        page, list, PDF, profile
                     MODEL READS                    MODEL READS    CODE DECIDES
```

- **The model reads, code decides.** The model never decides if a value is valid.
- Gemma has three jobs:
  - **Field finder**: numbered OCR lines (text only, no pixels) in, JSON question list out. Runs once per form.
  - **Normaliser**: transcript + type in, clean value out. Only when regex cannot do it.
  - **Explainer** ("What does this mean?"): field label in, a short simple Hindi / English explanation out.
- OCR is a **separate small model** (ML Kit, a few MB, inside the APK). It reads letters and positions only, not meaning.
- Everything else is plain Kotlin: camera, calling OCR and Gemma, TTS / STT, the loop, validation, drawing, PDF, storage. Nothing leaves the phone.

Planned code layout (under `app/src/main/java/com/vishal/fillbyvoice/`; names may change when built):
```
MainActivity.kt           app start, hosts the 4 tabs
ui/                       Compose screens (tabs + flow screens)
camera/CameraCapture.kt   CameraX photo + shrink to ~2000 px
ocr/OcrReader.kt          ML Kit: photo -> lines + boxes
llm/GemmaEngine.kt        LiteRT-LM: load, prompt, tokens/sec
pipeline/FieldFinder.kt   prompt + JSON parse + retry + fallback
pipeline/Normaliser.kt    regex first, then Gemma
pipeline/Explainer.kt     "What does this mean?"
voice/Speaker.kt          TextToSpeech
voice/Listener.kt         SpeechRecognizer
validate/Validators.kt    Kotlin rules (section 8)
flow/QuestionLoop.kt      ask -> listen -> clean -> check -> confirm
output/PageRenderer.kt    draw answers on the photo
output/PdfExporter.kt     2 PDFs (filled form, answer sheet), Download, Share
data/ProfileStore.kt      DataStore profile
data/FormStore.kt         per-form folder
```

---

## 6. Tech stack

- **Kotlin**, native Android, UI in **Jetpack Compose**.
- **LLM runtime: LiteRT-LM** (Google, replaces MediaPipe LLM Inference). Gradle `com.google.ai.edge.litertlm:litertlm-android` (pin the latest). API: `Engine(EngineConfig(modelPath = ..., backend = Backend.GPU()))`. Backends: CPU, GPU, NPU. Reference app: **AI Edge Gallery** (`github.com/google-ai-edge/gallery`).
- **Model: Gemma 4 E2B instruct**, `.litertlm` from Hugging Face `litert-community/gemma-4-E2B-it-litert-lm`. About 2.6 GB, Apache 2.0 (no sign-in gate), Hindi + English fine for our jobs.
  - Load from phone storage, never bundle it in the APK.
  - **Our app cannot read Gallery's copy** (each app has a private folder, like separate containers). **Done 16:32**: copied on the phone with `adb shell cp` (no download) to our app's folder:
    - `/sdcard/Android/data/com.vishal.fillbyvoice/files/gemma-4-E2B-it.litertlm` (2,588,147,712 bytes, same as Gallery's).
    - Source: `/sdcard/Android/data/com.google.ai.edge.gallery/files/Gemma_4_E2B_it/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm`.
    - The file is owned by `shell` (it was created by adb), so our app got "Permission denied" in Part 4. **Fixed** with `adb shell chmod 666 <our path>` (now `-rw-rw-rw-`). Redo this after any new copy of the model.
    - **Never uninstall our app**: Android deletes this folder and the model with it. Normal Run from Android Studio is fine.
  - **Speed line** on screen: `GPU | 48.7 tokens/sec | on-device` (measured in our app, 26 Sep, from LiteRT-LM's benchmark; real hardware use, visible to the jury).

| Model (Google's numbers, flagship phone, GPU) | Size | Decode | First reply | Hindi | Verdict |
|---|---|---|---|---|---|
| Gemma 3 1B | ~0.5 GB | fastest | fastest | Weak | No. Hindi is the point |
| **Gemma 4 E2B** | **2.6 GB** | **~52 tok/s** | **~0.3 s** | **Good** | **Use this** |
| Gemma 4 E4B | 3.7 GB | ~22 tok/s | ~0.8 s | Better | Only if E2B gets Hindi numbers wrong often |

- Field list JSON is about 300 tokens (~6 s on E2B, ~14 s on E4B). A normalised answer is about 10 tokens (instant).
- Fallbacks: (1) same E2B on CPU (slower, always works); (2) Gemma 3n E2B if Gemma 4 will not load with our LiteRT-LM version.
- **Backend: GPU first.** The booklet says inference "targets the Snapdragon NPU", but Google's NPU model files only go up to SM8750; our SM8850 has none yet, and NPU needs Qualcomm QAIRT libraries. NPU only if the organisers give a ready setup (model file + libs + sample).
```
 NPU : fastest, needs chip-specific file + Qualcomm libs  -> only if organisers help
 GPU : fast, normal .litertlm file                        -> START HERE
 CPU : slowest, always works                              -> last fallback
```
- **Camera**: CameraX.
- **OCR**: ML Kit Text Recognition v2 (on-device): text blocks, lines, bounding boxes. It reads Latin (English) print; bank and KYC forms are English or English + Hindi. Print reads well, handwriting poorly. Hindi (Devanagari) print needs ML Kit's Devanagari model: **later extra**.
- **Voice**: SpeechRecognizer (`hi-IN`, `en-IN`, offline packs installed) + TextToSpeech. Wait for TTS to finish before listening, so the mic does not hear the phone.
- **Profile**: DataStore (local key-value).
- **Drawing**: Bitmap + Canvas (built in), `drawText` each answer at its spot.
- **PDF**: PdfDocument (built in). Two files: filled form PDF (drawn page) and answer sheet PDF (questions + answers). **Download** saves to Downloads via MediaStore (visible in the Files app). **Share** uses FileProvider (a temporary read link for WhatsApp / Gmail, no internet).

---

## 7. Data shapes

**Field finder input** (Kotlin sends numbered OCR lines, no pixels):
```
0: ABC Bank - Account Form
1: 1. What is your full name?
2: 2. When were you born?
3: 3. Your phone number?
4: Sign below
```

**Field finder output** (the model returns only this JSON, forced by a JSON schema; long pages go in chunks of about 25 lines; the app no longer asks for `ask_en`, English mode uses the form's label):
```json
[
  { "line": 1, "id": "name",   "type": "text",   "ask_hi": "Aapka poora naam kya hai?",    "ask_en": "What is your full name?" },
  { "line": 2, "id": "dob",    "type": "date",   "ask_hi": "Aapki janm tithi kya hai?",    "ask_en": "What is your date of birth?" },
  { "line": 3, "id": "mobile", "type": "mobile", "ask_hi": "Aapka mobile number kya hai?", "ask_en": "What is your mobile number?" }
]
```
- `line`: the OCR line the question came from. Gemma skips headings and instructions (lines 0 and 4).
- `label` (added after the ICICI test): the exact text of that line. Kotlin trusts `line` only if its text matches `label`, else uses the best-matching line, else drops the item (Gemma sometimes points at the wrong line number).
- `id`: a stable key, used by the profile ("dob" on two forms is the same field).
- `type`: one of `text`, `date`, `aadhaar`, `pan`, `ifsc`, `mobile`, `pincode`, `number`, `email`, `yesno`, `choice`. It picks the Kotlin rule.
- `choice` (tick boxes / radio / dropdown) also has `"options": ["Male", "Female", "Third Gender"]`, and `ask_hi` reads the options out. Kotlin matches the spoken answer to one option. Bank forms are full of these (gender, marital status, account type, occupation), so it is in the MVP (Parts 5 and 8).
- **No pixels from Gemma** (small models get them wrong). Kotlin takes the box from OCR line `line`.
- Bad JSON: retry once, then fall back to every OCR line ending in `?` or `:` as a `text` question.

**Where an answer is drawn** (Kotlin): to the right of the question box if there is empty space before the next text; else below it, if there is a gap before the next line; else no room, so it goes on an extra page of the filled form PDF.
```
 Full Name     [Ramesh Kumar_______]     <- right of the question
 2. When were you born?
    25/03/1990                           <- below, on a plain sheet
```

**Saved form** (one folder per form):
```
files/forms/2026-09-26_1430/
  photo.jpg     original (shrunk)
  answers.json  the data
  filled.pdf    filled form (to submit)
  answers.pdf   answer sheet (to copy by hand)
```
```json
{
  "created": "2026-09-26T14:30:00",
  "language": "hi",
  "answers": [
    { "id": "name", "question": "What is your full name?", "type": "text", "value": "Ramesh Kumar", "box": [40, 100, 600, 140] },
    { "id": "dob",  "question": "When were you born?",     "type": "date", "value": "25/03/1990",   "box": [40, 300, 520, 340] }
  ]
}
```

**Profile**: a map of `id` to value, e.g. `{ "name": "Ramesh Kumar", "dob": "25/03/1990" }`. If a new form has a known `id`, offer "use this?" instead of asking.

---

## 8. Validation rules (Kotlin, not the model)

| Type | Rule |
|---|---|
| Aadhaar | 12 digits, first digit not 0 or 1, passes Verhoeff checksum |
| PAN | `^[A-Z]{5}[0-9]{4}[A-Z]$` |
| IFSC | `^[A-Z]{4}0[A-Z0-9]{6}$` |
| Mobile | `^[6-9][0-9]{9}$` |
| PIN code | `^[1-9][0-9]{5}$` |
| Date | real calendar date, not in the future, `DD/MM/YYYY` |
| Email | basic email regex |

- On failure, say aloud what is wrong, in the user's language, and ask again.

---

## 9. Build plan

- Details (Parts 1 to 16, files, targets, checkboxes) are in **`BUILD-STEPS.md`**. We are behind the original plan (camera + OCR were due by 14:00), because setup took the morning.

```
 CORE (MVP)    1-9    scan -> questions -> voice -> check -> list on screen
 WOW           10     "What does this mean?"
 OUTPUT        11-12  answers drawn on the same page, PDF Download + Share
 COMPLETE APP  13-16  My Info, Forms history, Settings, polish + demo
```
- If time is short: **MUST** 1-9 · **SHOULD** 10-12 + speed line · **NICE** 13-15.

| Window | Time | Mode | Parts |
|---|---|---|---|
| G2 | to 16:30 | GREEN | Git init + first commit. Part 1 (tabs) |
| R2 | 16:30 to 19:00 | RED | Part 7 (validators via Remote PC), Part 2 (camera), download the model on the laptop. **Round 1 probably here** |
| G3 | 19:00 to 22:00 | GREEN | Parts 3 (OCR), 4 (load Gemma), 5 (field finder) |
| R3 | 22:00 to 01:00 | RED | Parts 6 (voice), 8 (loop), 9 (MVP). Test on real pages |
| G4 | 01:00 to 06:30 | GREEN | Parts 10 to 15. README with attributions |
| R4 | 06:30 to 09:00 | RED | Part 16: rehearse 3 times, backup video, Office Kit mirror. **Round 2 probably here** |
| After 09:00 | ? | ? | Final fixes. **Submit repo + demo assets on Reskilll before the cutoff.** Top 10 pitch |

- Round 1 target: tabs, camera photo, validators passing (plus OCR boxes if G3 starts first). End of Saturday: MVP (Part 9) on a real page.
- Commit after every working part (ask Vishal first). Git history proves we built inside the window.

---

## 10. Risks and fallbacks

| Risk | Fallback |
|---|---|
| Gemma mishears Hindi numbers | Regex turns digits into numbers first. Hardcoded Hindi questions for common fields. Try E4B if speed allows |
| Gemma 4 E2B slow or will not load | CPU backend, then Gemma 3n E2B. Field finder once per form. Regex before the model. Progress indicator |
| NPU does not work | GPU. Still fully on-device |
| Bad JSON from the model | Strict prompt with one example, retry once, then raw OCR lines |
| Field finder keeps headings / wrong types / no Hindi (seen in test 4) | Few-shot English prompt. Kotlin keyword rules fix `type`, Hindi question templates per type, E4B if still weak |
| Model ignores the output format | Kotlin formats the final value (date to `DD/MM/YYYY`, digits only for numbers) |
| No room to draw an answer | Answer goes on an extra page of the filled form PDF, and is always in the answer sheet PDF. Never ask the user to tap |
| Page printed in Hindi | Later extra: ML Kit Devanagari. For now, English-printed pages |
| App cannot read Gallery's model file | Copied into our folder with `adb shell cp` (done). If our app still cannot open it: `chmod 666`, else download on the laptop + `adb push` |
| Speech mishears numbers | Read back every answer, ask digits in groups of 4 |
| Hindi offline speech pack missing | Installed already. Online recogniser only if the rules allow |
| Red light slows coding | Phone-only tasks in red. Keep red code edits small |
| Crashes logged by HackTracker | try/catch around model load and parse. Show an error on screen instead |
| Time runs out | MVP loop is the product. Then drawn page + PDF. Profile, history, settings are extras |

---

## 11. Demo script (3 to 5 min)

1. **Problem (30 s)**: forms are in English, about 1 in 10 Indians speak English, people pay agents or walk away. Users: villagers, elderly parents in cities, Bank Mitra / CSC agents who fill many forms a day.
2. **Live demo (2 to 3 min)**: phone mirrored via Office Kit, airplane mode on, speed line visible. Scan a real bank form, answer 4 to 5 fields in Hindi, ask "IFSC kya hai?" once, show one validation error (wrong Aadhaar), hear "Sab sahi hai?", show the same page with answers drawn in, tap Download PDF.
3. **Second form (30 s)**: scan a KYC form, show it asks only new fields.
4. **Why on-device (30 s)**: private (Aadhaar never leaves the phone), offline (airplane mode live), free per use. Gemma on the phone GPU, no cloud.
5. **What's next (10 s)**: online forms by voice (Phase 2, section 1).
6. **Close**: "The model reads. Code decides."

---

## 12. Open questions

**For Vishal:**
- Solo or team? (Assumed solo.)
- Was Phase 1 submitted as "Form Saathi"? Is the rename OK with the organisers, or keep "Form Saathi" for the event? (Old deck: `~/Downloads/Form-Saathi-deck.pdf`.)

**For the organisers:**
- **HackTracker**: how does it detect our inference and tokens? SDK, log format, specific runtime?
- **NPU**: an official path (Qualcomm AI Hub, QNN, NPU model files), or is on-device GPU fine?
- Exact times: Round 1, Round 2, submission cutoff, Top 10 pitch. Timeline after Sun 09:00: red or green?
- In red, can the phone stay on USB to install builds, or Office Kit file transfer only? Is a Bluetooth keyboard with the phone allowed in red?
- What "demo assets" to submit: video, slides, APK?
- Online speech recognition allowed as a fallback, or must speech be offline too?

---

## 13. Sources

- Printed booklet: About iQOO, Playbook 1/2 and 2/2, Red Light / Green Light page (photos, 26 Sep 2026). Event website: "Rules & Guidelines" and "How we score" (26 Sep 2026).
- Reskilll blog: [iQOO Hackathon 2026 overview](https://reskilll.com/blogs/iqoo-hackathon-2026-india-phone-first-ai-hackathon-iqoo-reskilll/), [How to win iQOO City Battles](https://reskilll.com/blogs/how-to-win-iqoo-city-battles-strategy-guide-phone-first-ai-hackathon/).
- Google: [LiteRT-LM on Android](https://developers.google.com/edge/litert-lm/android), [LiteRT-LM NPU](https://developers.google.com/edge/litert/next/litert_lm_npu), [AI Edge Gallery](https://github.com/google-ai-edge/gallery).
- Model: [Gemma 4 E2B LiteRT-LM on Hugging Face](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm).
