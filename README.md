# Glossa Pocket

The phone version of Glossa, for Android. It works on its own when the PC is off and syncs with the PC when both are on.

**On the phone alone**
- Books (EPUB or text) with the words coloured by what you know, and a chapter list.
- Videos with subtitle files (.srt / .vtt): words coloured on the subtitle, repeat the line, step to the previous / next line, a second line for the translation.
- Tap any word for its dictionary entry (your wty zip files, read directly, offline, with inflected forms and IPA).
- Known / Save / Make card. Cards go straight into AnkiDroid, with the picture and the sound of the line cut from the video on the phone, or wait for the PC.

**With the PC** (Glossa → Settings → Phone access switched on)
- Sync both ways: your known words, card states and looked-up words come from the PC; words you mark known, looked-up words and waiting cards go to the PC.
- Copy a video with its subtitles, or a book from the Reader, over Wi-Fi.

## Getting the app (GitHub builds it for you)

1. On github.com press **+ → New repository**, name it `batch-mine-pocket`, keep it **Private**, create it.
2. On the new repository's page: **uploading an existing file** → drag in *everything inside this folder* (the `app` folder, `.github`, `build.gradle`, `settings.gradle`, `gradle.properties`, ...). Press **Commit changes**.
   The folder `.github` starts with a dot; if your file manager hides it, turn on "show hidden files". (Or unzip on the PC and upload with GitHub Desktop.)
3. Open the **Actions** tab. A run called "Build the app" starts by itself (about 5 minutes). When it has a green tick, open it and download **GlossaPocket** at the bottom (a zip with `GlossaPocket.apk` inside).
4. Copy `GlossaPocket.apk` to the phone and open it. Android asks once to allow installing from that app (Files / Chrome): allow it.

A new version installed over the old one keeps your words, books and dictionaries.

## First start

1. **Settings → Dictionaries → Import a dictionary**: choose `wty-it-it.zip` (and `wty-it-it-ipa.zip` for pronunciation). It takes a minute or two.
2. **Settings → The PC**: paste the link from Glossa on the PC (Settings → Phone access). *Save and test*, then press **Sync** at the top.
3. **Settings → Cards → AnkiDroid fields…**: choose the note type, the deck and which field gets what (sentence, translation, definition, sound, picture...). AnkiDroid asks once to allow access.

## What is checked and what is not

The logic (dictionary import and lookup, word colours, subtitles, EPUB, sync, the PC's side) has tests that run on a computer: `sh coretest/run.sh` (add the dictionary zips as arguments to also test them). The screens, AnkiDroid and the video cutting use Android itself and were checked to compile against Android's library, but not run on a phone. If something does not work, tell me what you tapped and what you saw.

## Japanese on the phone (new, only compile-checked and unit-tested on a computer, never run on a phone)
- Import `jitendex-yomitan.zip` (or JMdict) and, for pitch, the NHK zip. Japanese text is then cut into words by the longest headword in the dictionary; inflected forms (食べた, 書かれなかった, 勉強します) are traced back to their dictionary form. The word sheet shows the reading and the pitch number.
- Less exact than the PC (UniDic): words missing from the dictionary come out as single kanji or a katakana run, and 東京都-like headwords can swallow a boundary.
- Subtitles: Settings → Jimaku key (jimaku.cc → your account) adds Japanese subtitle results to the search.
- Aozora Bunko `.txt` files (Shift-JIS) open as books; ruby marks, notes and the colophon are removed.
- Furigana: Settings → furigana: over words you don't know yet (default), over every word, or off. Inflected words get the reading of their kanji only (食べた → た over 食). The reading is also on the word sheet. Drawn with a custom text span that makes extra room above each line: never seen on a real phone, so line spacing may need adjusting.
- Weak kanji cards: import the KANJIDIC zip (Yomitan format) as a dictionary, then Settings → Japanese: cards for weak kanji. It takes the commonest of the 2500 kanji that your known words use at most twice and makes one card each through your normal card settings (AnkiDroid or tray): kanji as the word, meanings as the definition, readings, known words that use it as the sentence.
- Not on the phone: grammar spotting, kanji table, manga panels, game text, the local audio server, word-audio folder, pitch practice, Kitsunekko.
- A dictionary zip with a damaged checksum (the NHK file I tested has one) is refused: download it again.

## Limits

- Sound is cut from the video as it is (no re-encoding): AAC (most MP4 / MKV) and Opus / Vorbis (.webm). Other sound formats: the card is kept for the PC.
- The phone does not make subtitles (Whisper) and has no grammar colours (needs spaCy); words are coloured by their dictionary forms.
- The PC link is plain HTTP on your own network. Do not use it over the open internet; Tailscale is fine.
