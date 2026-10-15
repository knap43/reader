# Reader

An EPUB and PDF reader for Android, in Kotlin and Jetpack Compose with Material 3: dark tones, amber `#e1a34f` accent.

## What it does

- **Library.** A shelf with real covers. The book you're reading is pinned under *Continue reading*. Add books with the picker, or open an EPUB or PDF from any file manager with *Open with → Reader*. Long-press a book to mark it as read or remove it. Books past 98% are marked as read on their own; a checkmark shows on their cover, and the *All / Unread / Read* chips filter the shelf.
- **EPUB**, rendered with [Readium](https://github.com/readium/kotlin-toolkit):
  - Tap the left or right edge to turn pages; tap the middle to show the controls.
  - Table of contents, and a progress slider that jumps anywhere in the book.
  - Page themes: Dusk, Night, Sepia, Paper.
  - Text size, typeface (original, serif, sans) and line spacing.
  - Optional sliding page turns and hyphenation.
  - Select a word and tap *Define* for an offline English dictionary (WordNet 3.0).
  - Book-wide search, bookmarks and highlights; tap a highlight to copy, define or remove it.
- **PDF**, rendered with [PDFium](https://github.com/johngray1965/PdfiumAndroidKt):
  - Continuous scroll.
  - Pinch to zoom; double-tap to zoom or reset.
  - Go to page, and a warm night mode that inverts pages.
  - Long-press and drag to select text, then highlight, define or copy it.
  - Book-wide search, bookmarks, and the PDF's own outline as contents.
- Contents, bookmarks and highlights share one tabbed sheet. Highlights export as Markdown, shared or saved to a file.
- Optional full screen: hide the status and navigation bars (in the EPUB settings sheet, or the full-screen button in the PDF reader). Swipe from an edge to see them.
- Your position is saved as you read and restored when you reopen a book.

Books are copied into the app's private storage when added, so the originals can move or disappear without breaking the library.

## Dictionary

`app/src/main/assets/dictionary/en.db` is built from [WordNet 3.0](https://wordnet.princeton.edu/) by `tools/build_dictionary.py`; its license is in `app/src/main/assets/dictionary/LICENSE-WordNet`.

## Build

Requirements: JDK 17 and the Android SDK, platform 35.

```sh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Stack: AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01, Readium 3.0.3, PdfiumAndroid 1.0.35; min SDK 26, target SDK 35.
