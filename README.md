# Fill by Voice

**Paper form in. Filled form out. By voice, in your language, on your phone.**

Fill by Voice is a native Android app that turns an English bank form into a spoken conversation in Hindi or English. Take one photo of the form. The phone finds every question, asks each one aloud, listens, checks the answer, and gives back the filled form.

Built solo by Vishal Singh at the **iQOO Hackathon 2026, Hyderabad City Battle** (FinTech and Commerce track).

## The problem

- Bank and KYC forms in India are printed in English, full of terms like IFSC, CKYC and nominee.
- Many customers cannot read them: Hindi-first people in villages, elderly parents, first-time account holders.
- They depend on a clerk, an agent or a relative, or they give up.
- One wrong Aadhaar digit, IFSC code or date gets the form rejected: another trip to the branch.

## How it works

```
1 Scan        one photo of the paper form (the only tap)
2 Find        on-device OCR reads the page, Gemma finds the questions
3 Ask         each question spoken aloud, in Hindi or English
4 Listen      the user answers by voice
5 Check       code cleans and checks the answer, then asks "सही है?"
6 Read back   every answer read out: "सब सही है?", change any one by voice
7 Output      the filled form + an answer-sheet PDF (Download, Share)
```

**The model reads. Code decides.** Gemma does what rules cannot: find questions on any form, word them naturally in Hindi, read messy speech. Kotlin code makes every decision: every check, every save, every stop. The user confirms every answer.

## Key features

- **Any bank's form, no template.** OCR, then Gemma names each field, then code checks the printed label agrees. A catalogue of 50 common bank fields gives checked Hindi and English wording. Tick-box options ("Normal / Small / Minor") are read from the page.
- **Natural speech.** Dates in words ("25 मार्च 1990"), Hindi digits, spelled letters ("ए बी सी"), options said in Hindi, long answers said with pauses.
- **Every answer checked in Kotlin.** Aadhaar (Verhoeff checksum), PAN, IFSC, mobile, PIN code, real dates (after 1900, not in the future), email. A wrong answer is explained aloud and asked again.
- **Full voice control.** "बस करो" stops, "छोड़ो" skips, "क्या मतलब?" asks again, "मोबाइल गलत है" or "तीसरा गलत है" changes an answer at the read-back.
- **Filled form output.** The scanned photo with each answer written beside its label, plus an answer sheet (big text, BLOCK letters). Saved to Downloads or shared to WhatsApp / Gmail.
- **On-device AI.** Gemma 4 E2B runs on the phone GPU through LiteRT-LM (CPU fallback). No cloud AI, no server.

## Measured on the iQOO 15 (Snapdragon 8 Elite Gen 5)

| What | Time |
|---|---|
| Gemma 4 E2B on the GPU | about 45 tokens/sec |
| Model load | 1.5 s |
| OCR of a full form page (81 lines) | 0.3 s |
| Finding the questions on a page | 8 to 17 s |
| Wording a question or reading an answer | about 0.5 s |

## Architecture

```
Camera --> OCR --> Field finder --> Voice loop --> Normaliser --> Checks --> Output
CameraX   ML Kit   Gemma (GPU)      TTS + STT      rules, then    Kotlin     filled form
                   MODEL READS                     Gemma          CODE       + PDF
                                                   MODEL READS    DECIDES
```

Code in `FillByVoice/app/src/main/java/com/vishal/fillbyvoice/`:

| Folder | What it does |
|---|---|
| `camera/` | CameraX photo, cut to what the preview showed |
| `ocr/` | ML Kit text recognition, on-device |
| `llm/` | LiteRT-LM engine: loads Gemma on the GPU, CPU fallback |
| `pipeline/` | Field finder, 50-field bank catalogue, question wording, answer cleaning |
| `flow/` | The question loop and the read-back |
| `validate/` | Kotlin checks per answer type |
| `voice/` | Text to speech and speech recognition (Hindi, English) |
| `output/` | The filled form and the PDF |
| `ui/` | Jetpack Compose screens |
| `log/` | App log, also written to a file on the phone |

## Build and run

Needs Android Studio, and an Android 12+ phone (tested on an iQOO 15, Android 16).

1. Open the `FillByVoice` folder in Android Studio and run the app once on the phone.
2. Download `gemma-4-E2B-it.litertlm` (about 2.6 GB) from [litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) and copy it to the phone:
   ```
   adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.vishal.fillbyvoice/files/
   adb shell chmod 666 /sdcard/Android/data/com.vishal.fillbyvoice/files/gemma-4-E2B-it.litertlm
   ```
   Uninstalling the app deletes this folder and the model with it.
3. Allow the camera and microphone when asked.
4. Hindi listening uses the phone's Google speech service. It works online. For offline Hindi, install the pack: Google app, Settings, Voice, Offline speech recognition, हिन्दी (भारत).

Unit tests (29, run on the laptop):

```
cd FillByVoice
./gradlew :app:testDebugUnitTest
```

## Privacy

Gemma, OCR, every check, the filled form and the PDF all run on the phone. The app has no server and calls no cloud AI. Speech recognition uses the phone's recognizer: English works offline, Hindi goes online unless the offline Hindi pack is installed.

## What's next

- Fill the bank's own PDF: every letter in its box, ready to print.
- "IFSC क्या है?": spoken explanations of bank terms.
- Scan Aadhaar / PAN once, later forms fill themselves.
- Multi-page forms, online forms by voice, more Indian languages.

## Built with

| Library / model | By | License |
|---|---|---|
| [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) | Google AI Edge | Apache 2.0 |
| [Gemma 4 E2B instruct](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) | Google | See the model page |
| [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2) | Google | ML Kit terms |
| [CameraX](https://developer.android.com/media/camera/camerax), [Jetpack Compose](https://developer.android.com/compose), AndroidX | Google | Apache 2.0 |
| Android TextToSpeech, SpeechRecognizer, PdfDocument | Android | Part of Android |
| [Kotlin](https://kotlinlang.org) | JetBrains | Apache 2.0 |

The model file is not in this repo; it is downloaded separately (see Build and run).
