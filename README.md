# Reader

An EPUB and PDF reader for Android, in Kotlin and Jetpack Compose with Material 3: dark tones, amber `#e1a34f` accent.

## What it does

- **Library.** A shelf with real covers. The book you're reading is pinned under *Continue reading*. Add books with the picker, or open an EPUB or PDF from any file manager with *Open with → Reader*. Long-press a book to remove it.
- **EPUB**, rendered with [Readium](https://github.com/readium/kotlin-toolkit):
  - Tap the left or right edge to turn pages; tap the middle to show the controls.
  - Table of contents, and a progress slider that jumps anywhere in the book.
  - Page themes: Dusk, Night, Sepia, Paper.
  - Text size, typeface (original, serif, sans) and line spacing.
  - Optional sliding page turns.
- **PDF**, rendered with Android's `PdfRenderer`:
  - Continuous scroll.
  - Pinch to zoom; double-tap to zoom or reset.
  - Go to page, and a warm night mode that inverts pages.
- Optional full screen: hide the status and navigation bars (in the EPUB settings sheet, or the full-screen button in the PDF reader). Swipe from an edge to see them.
- Your position is saved as you read and restored when you reopen a book.

Books are copied into the app's private storage when added, so the originals can move or disappear without breaking the library.

## Build

Requirements: JDK 17 and the Android SDK, platform 35.

```sh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Stack: AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01, Readium 3.0.3; min SDK 26, target SDK 35.
