# Daily-Driver IME Implementation Plan

> **For agentic workers:** Implement task-by-task. Checkbox syntax for tracking. Work from the repo root. Do not run project-wide formatters. Do not run `./gradlew assemble*`. Unit tests only: `./gradlew :app:testDebugUnitTest`.

**Goal:** Ship the daily-driver IME: limiter, popups, typing essentials, emoji picker, clipboard history, autocomplete.

**Architecture:** Pure Kotlin modules + existing View IME. `KeyboardController` is the only `InputConnection` owner.

**Tech Stack:** Kotlin, JUnit 4, Android minSdk 26, no new Gradle deps.

## Global Constraints

- Package: `me.trion.whispertype`
- Tests: `app/src/test/java/me/trion/whispertype/...` JUnit 4, `org.junit.Assert.*`, backtick test names like `ModelCatalogTest`
- No Android framework types in pure modules (so unit tests run on the JVM)
- No new network calls
- Do not commit unrelated dirty files (`gradlew`, `.kotlin/`)
- Skip linters / full APK builds
- TDD for pure modules: failing test, then impl

## File map

Create:

- `app/src/main/java/me/trion/whispertype/ime/RecordingLimiter.kt`
- `app/src/main/java/me/trion/whispertype/ime/KeyPopupCatalog.kt`
- `app/src/main/java/me/trion/whispertype/ime/TypingRules.kt`
- `app/src/main/java/me/trion/whispertype/ime/ClipboardStore.kt`
- `app/src/main/java/me/trion/whispertype/ime/SuggestionEngine.kt`
- `app/src/main/java/me/trion/whispertype/ime/EmojiCatalog.kt`
- `app/src/main/java/me/trion/whispertype/ime/UnigramStore.kt`
- `app/src/test/java/me/trion/whispertype/ime/*Test.kt`
- `app/src/main/assets/dictionary/en.txt`
- `app/src/main/assets/emoji/catalog.json`
- `scripts/generate_emoji_catalog.py` (optional generator; APK uses the JSON)

Modify:

- `KeyboardLayout.kt`, `KeyboardController.kt`, `WhisperKeyboardService.kt`
- `keyboard_view.xml`, `activity_settings.xml`, `strings.xml`, `colors.xml`
- `Prefs.kt`, `SettingsActivity.kt`
- `AudioRecorder.kt`
- `README.md`

---

### Task 1: RecordingLimiter

**Files:** create `RecordingLimiter.kt` + `RecordingLimiterTest.kt`

```kotlin
package me.trion.whispertype.ime

enum class RecordingPhase { LISTENING, WARN, LIMIT }

data class RecordingTick(
    val elapsedMs: Long,
    val remainingMs: Long,
    val phase: RecordingPhase,
)

class RecordingLimiter(
    val maxMs: Long = 30_000,
    val warnMs: Long = 25_000,
) {
    fun onBytes(pcmBytes: Int, sampleRate: Int = 16_000): RecordingTick {
        val elapsed = if (sampleRate <= 0) 0L else (pcmBytes.toLong() * 1000L) / (sampleRate.toLong() * 2L)
        val remaining = (maxMs - elapsed).coerceAtLeast(0L)
        val phase = when {
            elapsed >= maxMs -> RecordingPhase.LIMIT
            elapsed >= warnMs -> RecordingPhase.WARN
            else -> RecordingPhase.LISTENING
        }
        return RecordingTick(elapsedMs = elapsed.coerceAtMost(maxMs), remainingMs = remaining, phase = phase)
    }
}
```

Tests:

- 0 bytes → LISTENING, elapsed 0, remaining 30000
- 16 kHz 16-bit: 16_000 * 2 bytes = 1s
- 24.9s → LISTENING; 25.0s → WARN; 30.0s → LIMIT remaining 0
- elapsed never exceeds maxMs
- sampleRate 0 → elapsed 0, does not crash

---

### Task 2: KeyPopupCatalog

**Files:** create `KeyPopupCatalog.kt` + `KeyPopupCatalogTest.kt`

```kotlin
object KeyPopupCatalog {
    fun popupsFor(base: String): List<String>
    fun popupsFor(base: String, uppercase: Boolean): List<String>
}
```

Exact maps from the spec. `popupsFor("a", true)` uppercases each entry except `ß`.
Unknown key → empty list.
Digits:

- 0 → ⁰
- 1 → ¹ ½ ⅓ ¼
- 2 → ² ⅔
- 3 → ³ ¾
- 4 → ⁴
- 5 → ⁵
- 6 → ⁶
- 7 → ⁷
- 8 → ⁸
- 9 → ⁹

---

### Task 3: TypingRules

**Files:** create `TypingRules.kt` + `TypingRulesTest.kt`

Pure functions. No Android.

```kotlin
object TypingRules {
    fun shouldAutoPeriod(textBeforeCursor: String, variation: FieldVariation): Boolean
    fun shouldSentenceCap(textBeforeCursor: String, variation: FieldVariation): Boolean
    fun previousWordLength(textBeforeCursor: String): Int
}

enum class FieldVariation { NORMAL, URI, EMAIL, PASSWORD, NUMBER }
```

`shouldAutoPeriod`: true iff variation is NORMAL, text is non-empty, last char is letter or digit.
`shouldSentenceCap`: true iff variation is NORMAL and (text is empty/blank OR trimmed text ends with `.` `?` `!`).
`previousWordLength`: count of trailing whitespace + the word before it. `hello world` → 5 (`world`). `hello ` → 6 (space + world). `hello` → 5. empty → 0.

Double-space timing stays in the controller (500ms). This module only decides *whether* the replacement is legal.

---

### Task 4: ClipboardStore

**Files:** create `ClipboardStore.kt` + `ClipboardStoreTest.kt`

Exact API from the spec. Persist JSON array:

```json
[{"id":"...","text":"...","createdAtMs":1}]
```

Rules: cap 20 newest first; blank → Ignored; same as current head → Ignored; `text.length > 8192` → Ignored; missing/corrupt file → empty snapshot; `delete` unknown id is no-op; `clear` empties file.

No org.json if it complicates JVM tests — hand-roll a tiny encoder/decoder for this shape (escape `\`, `"`, newlines).

---

### Task 5: SuggestionEngine + UnigramStore

**Files:** `SuggestionEngine.kt`, `UnigramStore.kt`, tests.

```kotlin
class SuggestionEngine(private val wordlist: List<String>) {
    fun suggest(prefix: String, learned: Map<String, Int>, limit: Int = 3): List<String>
}

class UnigramStore(private val file: File) {
    fun snapshot(): Map<String, Int>
    fun learn(word: String): Map<String, Int>
    fun clear()
}
```

Suggest:

- prefix blank → empty
- case-insensitive prefix; return words in the case of `prefix` if prefix has an uppercase letter, else lowercase
- candidates = wordlist matches ∪ learned keys that start with prefix (letters only, min length 2 to learn)
- rank: learned count desc, then wordlist index asc (learned-only words sort after listed words with equal count), then alphabetical
- unique, max `limit`

UnigramStore JSON object `{"hello":3}`. Cap 2000. Overflow drops a lowest-count key (alphabetical tie-break). Ignore learn of length < 2 or non-letter.

---

### Task 6: EmojiCatalog

**Files:** `EmojiCatalog.kt`, `EmojiCatalogTest.kt`, `assets/emoji/catalog.json`

JSON:

```json
{
  "groups": ["Smileys & Emotion", "..."],
  "items": [
    {"emoji":"😀","group":"Smileys & Emotion","subgroup":"face-smiling","name":"grinning face","keywords":["face","smile"],"tones":["😀","😀","😀","😀","😀","😀"]}
  ]
}
```

`tones` omitted when not tone-capable. When present, 6 Fitzpatrick variants (the base with 🏻🏼🏽🏾🏿 — if the base itself has no tone, list the five tones plus the yellow base as index 0 = untinted).

`EmojiCatalog.parse(json: String)` returns groups + items. `search(query)` filters name/keywords. `recents(ids, cap=24)` looks up emoji strings in catalog order of the id list.

Ship a real catalog covering all 9 groups with at least 80 items per large group and all common flags. Prefer generating from a compact embedded list in the test/generator rather than a stub of 29.

---

### Task 7: Prefs + settings UI

Add `incognito`, `doubleSpacePeriod`, `sentenceCaps` to `Prefs` (defaults false/true/true).
Add switches + strings. Wire `SettingsActivity` like haptic.

---

### Task 8: Layout chrome + KeyboardLayout

- `keyboard_view.xml`: replace status-only bar with a strip container (`strip_container`) that can host suggestion chips *or* status text. Add `cursor_bar` (gone by default). Add `panel_host` between chrome and rows for emoji/clipboard overlays (gone by default). Add `row0` for the number row (gone in non-letter modes).
- `KeyType.GLOBE`
- LETTERS: 5 rows (digits + existing 4). Globe on every mode bottom row, left of `?123`/`ABC`.
- Fill `popupLabels` from `KeyPopupCatalog` for letter/digit/symbol keys.

---

### Task 9: Controller + service wiring

Wire limiter, popups slide-to-select, typing rules, suggestion strip, emoji panel, clipboard panel, cursor bar, globe, incognito.
`AudioRecorder` exposes `pcmByteCount` / listener.
Clipboard listener on start/finish input view.

---

### Task 10: Dictionary asset + README

`assets/dictionary/en.txt` — one lowercase word per line, most common first, ≥3000 words.
README feature table updated.
