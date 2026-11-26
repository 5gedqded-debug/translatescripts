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

7. **Repeat cache**: identical phrases are translated once and reused (also inside a single run), so the AI is only asked for unique lines.
8. **Stop / errors**: *Stop* or the first API error closes all in-flight requests and halts every worker at once.
9. **Search and all lines**: search inside a chapter, or open **All lines** for a global searchable list. Each row shows its id (`script #n`).
    Use **Choices** for a separate list of the player choice buttons (translate them on their own or edit by hand).
10. **Similar lines**: the `≈` button on a row lists duplicates and similar phrases; tap one to reuse its translation.
11. **Find & replace**: in any search tap **Replace**, type the new text and replace it in one line (*Replace here*) or in all matches (*Replace in all*). Search is either an exact case-sensitive phrase or loose (any word order, ignoring case). The last replacement can be undone.
12. **Names**: tap a speaker name anywhere to set the correct translation yourself.
13. **Import translation**: with the original project open, tap **Import translation** and pick an already translated `.mkh` + `.mkp` pair and/or a `translation.txt`. Lines are matched by structure; only empty lines are filled and the name glossary is learned from the pair.

Translations are saved automatically, so you can close the app and continue later.

## File format
`.mkh` is the index and `.mkp` holds the data. Both are XOR-encoded with 0xA7, and the text inside is Shift-JIS. A line of dialogue is `【Name】`, the text, then `*`. A line without a name is plain narration or thoughts. Choices follow `#select`.

## Notes
- Shift-JIS cannot encode Cyrillic, so export defaults to UTF-8. Whether the game engine reads UTF-8 is up to the engine.
- Default model names are only placeholders; change them in Settings if needed.
