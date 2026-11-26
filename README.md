# MKP Translator (Android)

Android app that translates `.mkh` + `.mkp` game scripts using an LLM API (Gemini, Claude, ChatGPT, Grok, or any OpenAI-compatible endpoint).

## Build the APK with GitHub Actions
1. Push this repository to GitHub (the workflow runs automatically on every push to `main`).
2. Open the **Actions** tab, select **Build APK** and, if needed, click **Run workflow**.
3. When the run finishes, download `MkpTranslator-apk` from **Artifacts** and install `app-debug.apk`.

## Usage
1. **Settings**: choose the API type, enter the endpoint, API key and model, then tap **Check API**.
2. Choose the target language (Russian, English, others, or a custom one).
3. On the main screen tap **Open .mkh + .mkp** and select both files at once.
4. Open a chapter. With auto-translate on it is translated automatically, or use **Translate all**.
5. Tap a line to edit it by hand or re-translate it with the LLM. **Names** is a shared glossary of character names.
6. **Export**: pick a folder to get `script.mkh`, `script.mkp` (translated) and `translation.txt`.

Translations are saved automatically, so you can close the app and continue later.

## File format
`.mkh` is the index and `.mkp` holds the data. Both are XOR-encoded with 0xA7, and the text inside is Shift-JIS. A line of dialogue is `【Name】`, the text, then `*`. A line without a name is plain narration or thoughts. Choices follow `#select`.

## Notes
- Shift-JIS cannot encode Cyrillic, so export defaults to UTF-8. Whether the game engine reads UTF-8 is up to the engine.
- Default model names are only placeholders; change them in Settings if needed.
