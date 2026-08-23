# Emoji Search and Suggestions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` to implement this plan task-by-task. Use independent agents only where file ownership does not overlap.

**Goal:** Add working full-catalog emoji search and word-triggered emoji candidates to WhisperType Keyboard.

**Architecture:** A pure search engine and local search session own emoji-query behavior. Typed suggestion items separate word commits from emoji replacement actions. `KeyboardController` routes local search keys before it obtains the host `InputConnection`.

**Tech Stack:** Kotlin, Android Views, JUnit 4, AndroidX Test, UiAutomator, GitHub Actions, API 35 emulator.

## Global constraints

- Do not implement any task before advisor approval of this plan.
- Create `feat/emoji-search-suggestions` in an isolated worktree before the first repository change.
- Preserve the unrelated `gradlew` modification in the current checkout.
- Keep all implementation and phone-playtest changes on the feature branch.
- Do not merge the feature branch before user phone-playtest approval.
- Search query input must not call the host `InputConnection`.
- Search must cover all emoji catalog groups.
- Emoji suggestions must use exact trigger words, not incomplete prefixes.
- An emoji suggestion tap must replace the trigger word.
- Preserve one trailing space during emoji replacement.
- Run JVM and emulator integration tests before GitHub builds the signed APK.
- Prefix shell commands with `rtk`.

---

## Execution prelude: create the approved worktree

**Files:** No source files change in this prelude.

- [ ] Read `skill://using-git-worktrees`.
- [ ] Create an isolated worktree and branch from the current committed `HEAD`:

```bash
rtk git worktree add .worktrees/emoji-search-suggestions -b feat/emoji-search-suggestions HEAD
```

Expected: Git creates `.worktrees/emoji-search-suggestions` and checks out `feat/emoji-search-suggestions` there.

- [ ] Make sure that the worktree is clean:

```bash
rtk git -C .worktrees/emoji-search-suggestions status --short
```

Expected: no output.

- [ ] Save the approved design and this plan in the worktree:

```text
docs/superpowers/specs/2026-08-23-emoji-search-suggestions-design.md
docs/superpowers/plans/2026-08-23-emoji-search-suggestions.md
```

- [ ] Commit only the two approved documents:

```bash
rtk git add docs/superpowers/specs/2026-08-23-emoji-search-suggestions-design.md docs/superpowers/plans/2026-08-23-emoji-search-suggestions.md
rtk git commit -m "docs: plan emoji search and suggestions"
```

Expected: one documentation commit on `feat/emoji-search-suggestions`.

---

### Task 1: Ranked full-catalog search engine

**Files:**
- Create: `app/src/main/java/me/trion/whispertype/ime/EmojiSearchEngine.kt`
- Modify: `app/src/main/java/me/trion/whispertype/ime/EmojiCatalog.kt`
- Create: `app/src/test/java/me/trion/whispertype/ime/EmojiSearchEngineTest.kt`
- Modify: `app/src/test/java/me/trion/whispertype/ime/EmojiCatalogTest.kt`

**Interfaces:**
- Consumes: `EmojiCatalog.items: List<EmojiItem>` and `EmojiItem` metadata.
- Produces: `EmojiSearchEngine.search(query: String): List<EmojiItem>`.
- Produces: `EmojiSearchEngine.suggestExact(trigger: String, limit: Int = 2): List<EmojiItem>`.
- Produces: `EmojiSearchEngine.DEFAULT_ALIASES: Map<String, List<String>>`.

- [ ] **Step 1: Write failing search tests**

Cover exact aliases, multi-word names, keywords, group-independent matches, prefixes, substrings, normalization, stable order, and no-match behavior.

```kotlin
class EmojiSearchEngineTest {
    private val catalog = EmojiCatalog(
        groups = listOf("Smileys & Emotion", "Symbols", "Flags"),
        items = listOf(
            EmojiItem("😂", "Smileys & Emotion", "face-smiling", "face with tears of joy", listOf("laugh", "joy")),
            EmojiItem("🤣", "Smileys & Emotion", "face-smiling", "rolling on the floor laughing", listOf("laugh", "lol")),
            EmojiItem("❤️", "Symbols", "heart", "red heart", listOf("love", "heart")),
            EmojiItem("🇮🇳", "Flags", "country-flag", "flag: India", listOf("flag", "India")),
        ),
    )
    private val engine = EmojiSearchEngine(catalog)

    @Test fun `alias exact match ranks first`() {
        assertEquals(listOf("😂", "🤣"), engine.search("haha").take(2).map { it.emoji })
    }

    @Test fun `multi word query matches normalized name`() {
        assertEquals("❤️", engine.search("  RED   HEART ").first().emoji)
    }

    @Test fun `search ignores active browse group`() {
        assertEquals("🇮🇳", engine.search("flag india").first().emoji)
    }

    @Test fun `exact keyword ranks before substring`() {
        assertEquals(listOf("😂", "🤣"), engine.search("laugh").take(2).map { it.emoji })
    }

    @Test fun `exact emoji query returns that emoji`() {
        assertEquals(listOf("❤️"), engine.search("❤️").map { it.emoji })
    }

    @Test fun `incomplete exact suggestion is empty`() {
        assertTrue(engine.suggestExact("ha").isEmpty())
    }

    @Test fun `empty and unknown queries are empty`() {
        assertTrue(engine.search("   ").isEmpty())
        assertTrue(engine.search("not-an-emoji-term").isEmpty())
    }
}
```

- [ ] **Step 2: Run the new test and make sure that it fails**

```bash
rtk test bash gradlew :app:testDebugUnitTest --tests '*EmojiSearchEngineTest' --no-daemon
```

Expected: compilation fails because `EmojiSearchEngine` does not exist.

- [ ] **Step 3: Implement normalized scored search**

Use one normalized representation for names, groups, subgroups, and keywords. Convert punctuation to spaces and collapse repeated whitespace.

```kotlin
class EmojiSearchEngine(
    private val catalog: EmojiCatalog,
    private val aliases: Map<String, List<String>> = DEFAULT_ALIASES,
) {
    fun search(query: String): List<EmojiItem> {
        val raw = query.trim()
        if (raw.isEmpty()) return emptyList()
        catalog.items.firstOrNull { it.emoji == raw }?.let { return listOf(it) }
        val needle = normalize(raw)
        if (needle.isEmpty()) return emptyList()
        val aliasRank = aliases[needle].orEmpty().withIndex().associate { it.value to it.index }
        return catalog.items.withIndex().mapNotNull { indexed ->
            val item = indexed.value
            val fields = listOf(item.name, item.group, item.subgroup) + item.keywords
            val normalized = fields.map(::normalize).filter { it.isNotEmpty() }
            val score = when {
                item.emoji in aliasRank -> aliasRank.getValue(item.emoji)
                normalized.any { it == needle } -> 100
                normalized.any { field -> field.split(' ').windowed(needle.split(' ').size).any { it.joinToString(" ") == needle } } -> 200
                normalized.any { it.startsWith(needle) || it.split(' ').any { token -> token.startsWith(needle) } } -> 300
                normalized.any { it.contains(needle) } -> 400
                else -> return@mapNotNull null
            }
            Triple(score, indexed.index, item)
        }.sortedWith(compareBy<Triple<Int, Int, EmojiItem>> { it.first }.thenBy { it.second })
            .map { it.third }
    }

    fun suggestExact(trigger: String, limit: Int = 2): List<EmojiItem> {
        if (limit <= 0) return emptyList()
        val needle = normalize(trigger)
        if (needle.isEmpty()) return emptyList()
        val allowed = aliases.containsKey(needle) || catalog.items.any { item ->
            normalize(item.name) == needle || item.keywords.any { normalize(it) == needle }
        }
        return if (allowed) search(needle).take(limit) else emptyList()
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    companion object {
        val DEFAULT_ALIASES: Map<String, List<String>> = linkedMapOf(
            "haha" to listOf("😂", "🤣"),
            "lol" to listOf("😂", "🤣"),
            "laugh" to listOf("😂", "🤣"),
            "cry" to listOf("😢", "😭"),
            "sad" to listOf("😢", "😭"),
            "love" to listOf("❤️", "😍"),
            "heart" to listOf("❤️", "😍"),
        )
    }
}
```

Remove `EmojiCatalog.search`. Move its name, keyword, empty-query, and exact-emoji cases into `EmojiSearchEngineTest`. Migrate every caller to `EmojiSearchEngine.search`.

- [ ] **Step 4: Run search and catalog tests**

```bash
rtk test bash gradlew :app:testDebugUnitTest --tests '*EmojiSearchEngineTest' --tests '*EmojiCatalogTest' --no-daemon
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit the search engine**

```bash
rtk git add app/src/main/java/me/trion/whispertype/ime/EmojiSearchEngine.kt app/src/main/java/me/trion/whispertype/ime/EmojiCatalog.kt app/src/test/java/me/trion/whispertype/ime/EmojiSearchEngineTest.kt app/src/test/java/me/trion/whispertype/ime/EmojiCatalogTest.kt
rtk git commit -m "feat(ime): add ranked emoji search"
```

---

### Task 2: Local emoji search session and key layout

**Files:**
- Create: `app/src/main/java/me/trion/whispertype/ime/EmojiSearchSession.kt`
- Modify: `app/src/main/java/me/trion/whispertype/ime/KeyboardLayout.kt`
- Create: `app/src/test/java/me/trion/whispertype/ime/EmojiSearchSessionTest.kt`
- Modify: `app/src/test/java/me/trion/whispertype/ime/KeyboardLayoutTest.kt`

**Interfaces:**
- Produces: `EmojiSearchSession.query: String`.
- Produces: `EmojiSearchSession.append(text: String): String`.
- Produces: `EmojiSearchSession.backspace(): String`.
- Produces: `EmojiSearchSession.clear(): String`.
- Produces: `KeyboardLayout.emojiSearch: List<List<KeyDef>>`.

- [ ] **Step 1: Write failing local-state tests**

```kotlin
class EmojiSearchSessionTest {
    @Test fun `letters and spaces build a local query`() {
        val session = EmojiSearchSession()
        session.append("red")
        session.append(" ")
        assertEquals("red heart", session.append("heart"))
    }

    @Test fun `backspace and clear only change query state`() {
        val session = EmojiSearchSession("cry")
        assertEquals("cr", session.backspace())
        assertEquals("", session.clear())
    }

    @Test fun `unsupported query characters are ignored`() {
        val session = EmojiSearchSession("ha")
        assertEquals("ha", session.append("😂"))
    }
}
```

Add layout assertions for three QWERTY rows, a backspace key, a local space key, `MODE_EMOJI`, and `MODE_ABC`.

- [ ] **Step 2: Run the tests and make sure that they fail**

```bash
rtk test bash gradlew :app:testDebugUnitTest --tests '*EmojiSearchSessionTest' --tests '*KeyboardLayoutTest' --no-daemon
```

Expected: compilation fails because the session and `emojiSearch` layout do not exist.

- [ ] **Step 3: Implement local query state**

```kotlin
class EmojiSearchSession(initialQuery: String = "") {
    var query: String = initialQuery
        private set

    fun append(text: String): String {
        if (text.all { it.isLetterOrDigit() || it == ' ' }) query += text
        return query
    }

    fun backspace(): String {
        if (query.isNotEmpty()) query = query.dropLast(1)
        return query
    }

    fun clear(): String {
        query = ""
        return query
    }
}
```

Add `KeyboardLayout.emojiSearch`. Create popup-free keys so long presses cannot call `commitPopupText` on the host editor:

```kotlin
private fun searchLetter(c: String): KeyDef = KeyDef(
    type = KeyType.CHAR,
    label = c,
    shiftLabel = c.uppercase(),
    code = c[0].code,
    popupLabels = emptyList(),
)

private val emojiSearchThird = listOf(
    *listOf("z", "x", "c", "v", "b", "n", "m").map(::searchLetter).toTypedArray(),
    KeyDef(KeyType.BACKSPACE, "⌫", weight = 1.4f, icon = true),
)

private val bottomEmojiSearch = listOf(
    KeyDef(KeyType.MODE_ABC, "ABC", weight = 1.4f),
    KeyDef(KeyType.MODE_EMOJI, "😀", weight = 1.2f),
    KeyDef(KeyType.SPACE, "space", weight = 5.0f, popupLabels = emptyList()),
)

val emojiSearch: List<List<KeyDef>> = listOf(
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map(::searchLetter),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").map(::searchLetter),
    emojiSearchThird,
    bottomEmojiSearch,
)
```

- [ ] **Step 4: Run the selected tests**

```bash
rtk test bash gradlew :app:testDebugUnitTest --tests '*EmojiSearchSessionTest' --tests '*KeyboardLayoutTest' --no-daemon
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit local search state and keys**

```bash
rtk git add app/src/main/java/me/trion/whispertype/ime/EmojiSearchSession.kt app/src/main/java/me/trion/whispertype/ime/KeyboardLayout.kt app/src/test/java/me/trion/whispertype/ime/EmojiSearchSessionTest.kt app/src/test/java/me/trion/whispertype/ime/KeyboardLayoutTest.kt
rtk git commit -m "feat(ime): add local emoji search input"
```

---

### Task 3: Typed emoji suggestions and replacement actions

**Files:**
- Create: `app/src/main/java/me/trion/whispertype/ime/EmojiSuggestionEngine.kt`
- Create: `app/src/main/java/me/trion/whispertype/ime/SuggestionItem.kt`
- Create: `app/src/test/java/me/trion/whispertype/ime/EmojiSuggestionEngineTest.kt`

**Interfaces:**
- Consumes: `EmojiSearchEngine.suggestExact`.
- Produces: `EmojiReplacement(deleteBeforeCursor: Int, commitText: String)`.
- Produces: `EmojiCandidate(item: EmojiItem, replacement: EmojiReplacement)`.
- Produces: `EmojiSuggestionEngine.suggest(textBeforeCursor: CharSequence, limit: Int = 2): List<EmojiCandidate>`.
- Produces: sealed `SuggestionItem.Word` and `SuggestionItem.Emoji`.
- Produces: `SuggestionComposer.compose(words, emojis, limit = 3)`.

- [ ] **Step 1: Write failing suggestion tests**

```kotlin
class EmojiSuggestionEngineTest {
    private fun testCatalog() = EmojiCatalog(
        groups = listOf("Smileys & Emotion", "Symbols"),
        items = listOf(
            EmojiItem("😂", "Smileys & Emotion", "face-smiling", "face with tears of joy", listOf("laugh", "joy")),
            EmojiItem("🤣", "Smileys & Emotion", "face-smiling", "rolling on the floor laughing", listOf("laugh", "lol")),
            EmojiItem("😢", "Smileys & Emotion", "face-concerned", "crying face", listOf("cry", "sad")),
            EmojiItem("😭", "Smileys & Emotion", "face-concerned", "loudly crying face", listOf("cry", "sad")),
            EmojiItem("❤️", "Symbols", "heart", "red heart", listOf("love", "heart")),
            EmojiItem("😍", "Smileys & Emotion", "face-affection", "smiling face with heart-eyes", listOf("love", "heart")),
        ),
    )

    private val search = EmojiSearchEngine(testCatalog())
    private val engine = EmojiSuggestionEngine(search)
    @Test fun `exact trigger returns two emoji candidates`() {
        assertEquals(listOf("😂", "🤣"), engine.suggest("haha").map { it.item.emoji })
    }

    @Test fun `partial trigger returns no emoji candidates`() {
        assertTrue(engine.suggest("ha").isEmpty())
    }

    @Test fun `replacement removes current word`() {
        assertEquals(EmojiReplacement(4, "😂"), engine.suggest("say haha").first().replacement)
    }

    @Test fun `replacement preserves one trailing space`() {
        assertEquals(EmojiReplacement(5, "😂 "), engine.suggest("say haha ").first().replacement)
    }

    @Test fun `two trailing spaces stop the trigger`() {
        assertTrue(engine.suggest("haha  ").isEmpty())
    }

    @Test fun `composer keeps one word slot`() {
        val result = SuggestionComposer.compose(
            words = listOf("hahaha", "hah"),
            emojis = engine.suggest("haha"),
        )
        assertEquals(3, result.size)
        assertTrue(result[0] is SuggestionItem.Emoji)
        assertTrue(result[1] is SuggestionItem.Emoji)
        assertEquals(SuggestionItem.Word("hahaha"), result[2])
    }
}
```

- [ ] **Step 2: Run the tests and make sure that they fail**

```bash
rtk test bash gradlew :app:testDebugUnitTest --tests '*EmojiSuggestionEngineTest' --no-daemon
```

Expected: compilation fails because the suggestion types do not exist.

- [ ] **Step 3: Implement trigger extraction and typed suggestions**

```kotlin
data class EmojiReplacement(val deleteBeforeCursor: Int, val commitText: String)
data class EmojiCandidate(val item: EmojiItem, val replacement: EmojiReplacement)

class EmojiSuggestionEngine(private val search: EmojiSearchEngine) {
    fun suggest(textBeforeCursor: CharSequence, limit: Int = 2): List<EmojiCandidate> {
        if (limit <= 0) return emptyList()
        val match = Regex("([\\p{L}]+)( ?)$").find(textBeforeCursor) ?: return emptyList()
        if (match.range.last != textBeforeCursor.length - 1) return emptyList()
        val word = match.groupValues[1]
        val suffix = match.groupValues[2]
        return search.suggestExact(word, limit).map { item ->
            EmojiCandidate(
                item = item,
                replacement = EmojiReplacement(
                    deleteBeforeCursor = word.length + suffix.length,
                    commitText = item.emoji + suffix,
                ),
            )
        }
    }
}

sealed interface SuggestionItem {
    data class Word(val text: String) : SuggestionItem
    data class Emoji(val candidate: EmojiCandidate) : SuggestionItem
}

object SuggestionComposer {
    fun compose(
        words: List<String>,
        emojis: List<EmojiCandidate>,
        limit: Int = 3,
    ): List<SuggestionItem> {
        if (limit <= 0) return emptyList()
        val emojiItems = emojis.take(minOf(2, limit)).map { SuggestionItem.Emoji(it) }
        val wordItems = words.take(limit - emojiItems.size).map { SuggestionItem.Word(it) }
        return emojiItems + wordItems
    }
}
```

- [ ] **Step 4: Run suggestion tests**

```bash
rtk test bash gradlew :app:testDebugUnitTest --tests '*EmojiSuggestionEngineTest' --tests '*SuggestionEngineTest' --no-daemon
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit typed suggestions**

```bash
rtk git add app/src/main/java/me/trion/whispertype/ime/EmojiSuggestionEngine.kt app/src/main/java/me/trion/whispertype/ime/SuggestionItem.kt app/src/test/java/me/trion/whispertype/ime/EmojiSuggestionEngineTest.kt
rtk git commit -m "feat(ime): add emoji suggestion actions"
```

---

### Task 4: Emoji panel renderer and search routing

**Files:**
- Create: `app/src/main/java/me/trion/whispertype/ime/EmojiPanelView.kt`
- Modify: `app/src/main/java/me/trion/whispertype/ime/KeyboardController.kt`
- Create: `app/src/main/res/values/ids.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `EmojiSearchSession`, `EmojiSearchEngine`, `KeyboardLayout.emojiSearch`.
- Produces: `EmojiPanelView.renderBrowse(...)` and `EmojiPanelView.renderSearch(...)`.
- Maintains: `EmojiPanelMode.BROWSE` and `EmojiPanelMode.SEARCH` in `KeyboardController`.

- [ ] **Step 1: Add stable view IDs and strings**

Create `ids.xml`:

```xml
<resources>
    <item name="emoji_search_entry" type="id" />
    <item name="emoji_search_query" type="id" />
    <item name="emoji_search_back" type="id" />
    <item name="emoji_search_clear" type="id" />
    <item name="emoji_result_grid" type="id" />
</resources>
```

Add strings for search back, clear, and no results. Use these strings for accessibility descriptions.

- [ ] **Step 2: Extract emoji rendering from `KeyboardController`**

Create `EmojiPanelView`. It owns dynamic browse and search views, but it does not own query state or commit text.

```kotlin
class EmojiPanelView(
    private val context: Context,
    private val host: FrameLayout,
) {
    fun renderBrowse(
        catalog: EmojiCatalog,
        activeGroup: String,
        recents: List<EmojiItem>,
        onSearch: () -> Unit,
        onGroup: (String) -> Unit,
        onEmoji: (EmojiItem) -> Unit,
        onTones: (View, List<String>) -> Unit,
    )

    fun renderSearch(
        query: String,
        results: List<EmojiItem>,
        onBack: () -> Unit,
        onClear: () -> Unit,
        onEmoji: (EmojiItem) -> Unit,
        onTones: (View, List<String>) -> Unit,
    )
}
```

Set these stable selectors:

```kotlin
searchEntry.id = R.id.emoji_search_entry
queryView.id = R.id.emoji_search_query
backView.id = R.id.emoji_search_back
clearView.id = R.id.emoji_search_clear
grid.id = R.id.emoji_result_grid
emojiCell.contentDescription = item.name
```

- [ ] **Step 3: Add controller search state**

Add these fields and the panel-mode enum:

```kotlin
private enum class EmojiPanelMode { BROWSE, SEARCH }

private val emojiSearchSession = EmojiSearchSession()
private val emojiSearchEngine by lazy { EmojiSearchEngine(catalog()) }
private val emojiSuggestionEngine by lazy { EmojiSuggestionEngine(emojiSearchEngine) }
private val emojiPanelView = EmojiPanelView(context, panelHost)
private var emojiPanelMode = EmojiPanelMode.BROWSE
private var activeEmojiGroup = "recents"
```

- [ ] **Step 4: Route local keys before host connection lookup**

At the start of `onKey`, call `routeEmojiSearchKey`. Do this before `inputConnectionProvider()`.

```kotlin
private fun onKey(key: KeyDef) {
    if (routeEmojiSearchKey(key)) return
    val ic = inputConnectionProvider() ?: return
    // Existing normal editor routing.
}

private fun routeEmojiSearchKey(key: KeyDef): Boolean {
    if (panel != Panel.EMOJI || emojiPanelMode != EmojiPanelMode.SEARCH) return false
    when (key.type) {
        KeyType.CHAR -> emojiSearchSession.append(key.label)
        KeyType.SPACE -> emojiSearchSession.append(" ")
        KeyType.BACKSPACE -> emojiSearchSession.backspace()
        KeyType.MODE_EMOJI -> emojiPanelMode = EmojiPanelMode.BROWSE
        KeyType.MODE_ABC -> {
            mode = KeyboardLayout.Mode.LETTERS
            closePanel()
        }
        else -> return true
    }
    rebuildKeys()
    return true
}
```
- [ ] **Step 5: Show QWERTY rows only in search mode**

Change `deleteOnce` so the search branch calls `EmojiSearchSession.backspace()`. Remove `emojiQuery` and `emojiFilter`.

Update `bindBackspace` for the complete gesture. Search mode can repeat local backspace, but it must skip `deleteWord()` and `refreshSuggestions()` on `ACTION_UP`.

```kotlin
val searchBackspace = panel == Panel.EMOJI && emojiPanelMode == EmojiPanelMode.SEARCH
if (event.action == MotionEvent.ACTION_UP && !searchBackspace && downX - event.x > 48f) {
    deleteWord()
}
if (!searchBackspace) refreshSuggestions()
```

In `onFinishInput`, clear the search session, select `BROWSE`, close the panel, and restore `LETTERS`. This gives each editor session a deterministic initial state.

In `rebuildKeys`, select `KeyboardLayout.emojiSearch` while search mode is active. Show rows 1 through 3 in that state.

```kotlin
val emojiSearchActive = panel == Panel.EMOJI && emojiPanelMode == EmojiPanelMode.SEARCH
val layout = if (emojiSearchActive) KeyboardLayout.emojiSearch else KeyboardLayout.rowsFor(mode)
val hideTypingRows = panel != Panel.NONE && !emojiSearchActive
```

Set `panelHost` to the fixed `SEARCH_PANEL_HEIGHT_DP = 112dp` in search mode and restore 162dp when the panel closes. The search geometry is bounded and must not expand for an unbounded emoji grid:

- `EmojiPanelView.renderSearch` uses a fixed 36dp query header.
- The emoji results use the remaining weighted `ScrollView` result viewport.
- The local keyboard keeps three fixed 48dp QWERTY rows plus a fixed 48dp utility row.
- The host editor remains fully above the IME after opening search and after entering the query. The integration assertion requires positive host-field visible height, `visibleBounds.bottom <= device.displayHeight`, and `visibleBounds.bottom <= keyboard_root.visibleBounds.top`.

Assign deterministic key descriptions in normal and emoji-search modes:

```kotlin
view.contentDescription = when {
    emojiSearchActive && key.type == KeyType.CHAR -> "emoji-search-key-${key.label}"
    emojiSearchActive && key.type == KeyType.SPACE -> "emoji-search-space"
    emojiSearchActive && key.type == KeyType.BACKSPACE -> "emoji-search-backspace"
    key.type == KeyType.CHAR -> "key-${key.label}"
    key.type == KeyType.SPACE -> "key-space"
    else -> view.contentDescription
}
```

- [ ] **Step 6: Connect renderer callbacks**

Browse search tap sets `emojiPanelMode = SEARCH`, clears the session, and rebuilds.

Search result tap calls `commitPopupText`, records recents when allowed, clears the session, and stays in emoji mode.

Panel exit and input-session finish clear the session.

- [ ] **Step 7: Run focused JVM tests and compile the debug APK**

```bash
rtk test bash gradlew :app:testDebugUnitTest --tests '*EmojiSearch*' --tests '*KeyboardLayoutTest' --no-daemon
rtk test bash gradlew :app:assembleDebug --no-daemon
```

Expected: selected tests pass and the debug APK compiles.

- [ ] **Step 8: Commit search UI and routing**

```bash
rtk git add app/src/main/java/me/trion/whispertype/ime/EmojiPanelView.kt app/src/main/java/me/trion/whispertype/ime/KeyboardController.kt app/src/main/res/values/ids.xml app/src/main/res/values/strings.xml
rtk git commit -m "fix(ime): route emoji search locally"
```

---

### Task 5: Normal suggestion-strip integration

**Files:**
- Modify: `app/src/main/java/me/trion/whispertype/ime/KeyboardController.kt`
- Modify: `app/src/test/java/me/trion/whispertype/ime/EmojiSuggestionEngineTest.kt`

**Interfaces:**
- Consumes: `EmojiSuggestionEngine.suggest` and `SuggestionComposer.compose`.
- Changes: `lastSuggestions` from `List<String>` to `List<SuggestionItem>`.
- Produces: `applySuggestion(item: SuggestionItem)`.

- [ ] **Step 1: Add behavior tests for composer ordering and replacement data**

Add these assertions to `EmojiSuggestionEngineTest`:

```kotlin
@Test fun `reviewed aliases keep two emoji slots and one word slot`() {
    val expected = mapOf(
        "cry" to listOf("😢", "😭"),
        "sad" to listOf("😢", "😭"),
        "love" to listOf("❤️", "😍"),
        "heart" to listOf("❤️", "😍"),
    )
    expected.forEach { (trigger, emojis) ->
        val candidates = engine.suggest(trigger)
        assertEquals(emojis, candidates.map { it.item.emoji })
        val composed = SuggestionComposer.compose(listOf("word"), candidates)
        assertEquals(3, composed.size)
        assertEquals(SuggestionItem.Word("word"), composed.last())
    }
}
```
- [ ] **Step 2: Run the focused tests before controller changes**

```bash
rtk test bash gradlew :app:testDebugUnitTest --tests '*EmojiSuggestionEngineTest' --no-daemon
```

Expected: the new pure tests pass. They define the controller integration contract.

- [ ] **Step 3: Build typed strip items**

In `refreshSuggestions`, read one editor context string and compose the two candidate sources.

```kotlin
val before = inputConnectionProvider()?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
val prefix = currentWord(before)
val words = engine().suggest(prefix, unigramStore.snapshot(), 3)
val emojis = emojiSuggestionEngine.suggest(before, 2)
lastSuggestions = SuggestionComposer.compose(words, emojis, 3)
```

Change `currentWord` to accept the already-read context string. Do not call the `InputConnection` twice.

Render `SuggestionItem.Word` with its text. Render `SuggestionItem.Emoji` with `candidate.item.emoji` and `candidate.item.name` as its content description.

- [ ] **Step 4: Apply typed actions**

```kotlin
private fun applySuggestion(item: SuggestionItem) {
    val ic = inputConnectionProvider() ?: return
    when (item) {
        is SuggestionItem.Word -> {
            val prefix = currentWord(ic.getTextBeforeCursor(48, 0)?.toString().orEmpty())
            if (prefix.isNotEmpty()) ic.deleteSurroundingText(prefix.length, 0)
            ic.commitText(item.text, 1)
            if (!isPrivate()) unigramStore.learn(item.text)
        }
        is SuggestionItem.Emoji -> {
            val replacement = item.candidate.replacement
            ic.deleteSurroundingText(replacement.deleteBeforeCursor, 0)
            ic.commitText(replacement.commitText, 1)
            if (!isPrivate()) rememberEmoji(item.candidate.item.emoji)
        }
    }
    refreshSuggestions()
}
```

Do not call `unigramStore.learn` for `SuggestionItem.Emoji`.

- [ ] **Step 5: Run suggestion tests and compile**

```bash
rtk test bash gradlew :app:testDebugUnitTest --tests '*SuggestionEngineTest' --tests '*EmojiSuggestionEngineTest' --no-daemon
rtk test bash gradlew :app:assembleDebug --no-daemon
```

Expected: all selected tests pass and the APK compiles.

- [ ] **Step 6: Commit strip integration**

```bash
rtk git add app/src/main/java/me/trion/whispertype/ime/KeyboardController.kt app/src/test/java/me/trion/whispertype/ime/EmojiSuggestionEngineTest.kt
rtk git commit -m "feat(ime): show emoji word suggestions"
```

---

### Task 6: Real IME integration suite

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/androidTest/java/me/trion/whispertype/ime/EmojiKeyboardIntegrationTest.kt`

**Interfaces:**
- Consumes stable resource IDs and content descriptions from Tasks 4 and 5.
- Runs against `SetupActivity` and `WhisperKeyboardService` on an Android emulator.

- [ ] **Step 1: Configure Android instrumentation**

Add this runner:

```kotlin
defaultConfig {
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}
```

Add these dependencies:

```kotlin
androidTestImplementation("androidx.test:core-ktx:1.6.1")
androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
androidTestImplementation("androidx.test:rules:1.6.1")
androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
```

- [ ] **Step 2: Write the end-to-end tests**

Use `UiDevice`, resource selectors, and content descriptions. Do not use screen coordinates.

```kotlin
@RunWith(AndroidJUnit4::class)
class EmojiKeyboardIntegrationTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @get:Rule
    val activityRule = ActivityScenarioRule(SetupActivity::class.java)

    @Before fun selectIme() {
        device.executeShellCommand("ime reset")
        device.executeShellCommand("ime enable me.trion.whispertype/.ime.WhisperKeyboardService")
        device.executeShellCommand("ime set me.trion.whispertype/.ime.WhisperKeyboardService")
        activityRule.scenario.onActivity { activity ->
            activity.findViewById<EditText>(R.id.test_input).apply {
                setText("")
                requestFocus()
            }
        }
        device.wait(Until.hasObject(By.res("me.trion.whispertype", "keyboard_root")), 5_000)
        device.findObject(By.text("ABC"))?.click()
    }

    @Test fun searchQueryStaysLocalAndResultCommits() {
        device.findObject(By.res("me.trion.whispertype", "btn_emoji_strip")).click()
        device.findObject(By.res("me.trion.whispertype", "emoji_search_entry")).click()
        "red".forEach { device.findObject(By.desc("emoji-search-key-$it")).click() }
        device.findObject(By.desc("emoji-search-space")).click()
        "heart".forEach { device.findObject(By.desc("emoji-search-key-$it")).click() }

        assertEquals(
            "red heart",
            device.findObject(By.res("me.trion.whispertype", "emoji_search_query")).text,
        )
        assertEquals("", device.findObject(By.res("me.trion.whispertype", "test_input")).text)

        device.findObject(By.desc("red heart")).click()
        assertEquals("❤️", device.findObject(By.res("me.trion.whispertype", "test_input")).text)
    }

    @Test fun normalSuggestionReplacesTriggerWord() {
        "haha".forEach { device.findObject(By.desc("key-$it")).click() }
        device.wait(Until.hasObject(By.text("😂")), 2_000)
        assertTrue(device.hasObject(By.text("🤣")))
        device.findObject(By.text("😂")).click()
        assertEquals("😂", device.findObject(By.res("me.trion.whispertype", "test_input")).text)
    }

    @Test fun suggestionReplacementPreservesOneTrailingSpace() {
        "haha".forEach { device.findObject(By.desc("key-$it")).click() }
        device.findObject(By.desc("key-space")).click()
        device.wait(Until.hasObject(By.text("😂")), 2_000)
        device.findObject(By.text("😂")).click()
        assertEquals("😂 ", device.findObject(By.res("me.trion.whispertype", "test_input")).text)
    }

    @Test fun searchBackspaceChangesOnlyTheLocalQuery() {
        device.findObject(By.res("me.trion.whispertype", "btn_emoji_strip")).click()
        device.findObject(By.res("me.trion.whispertype", "emoji_search_entry")).click()
        "cryx".forEach { device.findObject(By.desc("emoji-search-key-$it")).click() }
        device.findObject(By.desc("emoji-search-backspace")).click()
        assertEquals(
            "cry",
            device.findObject(By.res("me.trion.whispertype", "emoji_search_query")).text,
        )
        assertEquals("", device.findObject(By.res("me.trion.whispertype", "test_input")).text)
    }
}
```

- [ ] **Step 3: Run the emulator suite locally**

Use an available API-35 emulator:

```bash
rtk test bash gradlew :app:connectedDebugAndroidTest --no-daemon
```

Expected: all `EmojiKeyboardIntegrationTest` cases pass.

- [ ] **Step 4: Commit the integration suite**

```bash
rtk git add app/build.gradle.kts app/src/androidTest/java/me/trion/whispertype/ime/EmojiKeyboardIntegrationTest.kt
rtk git commit -m "test(ime): cover emoji search and suggestions"
```

---

### Task 7: GitHub emulator gate and APK artifact

**Files:**
- Modify: `.github/workflows/build.yml`

**Interfaces:**
- Produces: `instrumented` job on API 35.
- Changes: signed `build` job requires `instrumented` success.
- Preserves: `WhisperTypeKeyboard-release` artifact name.

- [ ] **Step 1: Add the emulator job**

```yaml
  instrumented:
    name: IME integration tests
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: android-actions/setup-android@v3
      - name: Grant Gradle wrapper permission
        run: chmod +x gradlew
      - uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 35
          arch: x86_64
          profile: pixel_6
          disable-animations: true
          script: ./gradlew :app:connectedDebugAndroidTest --no-daemon
      - name: Upload integration reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: emoji-integration-reports
          path: |
            app/build/reports/androidTests/connected/
            app/build/outputs/androidTest-results/connected/
          if-no-files-found: ignore
```

Add `needs: instrumented` to the existing `build` job.

- [ ] **Step 2: Run local workflow-equivalent checks**

```bash
rtk test bash gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
rtk test bash gradlew :app:connectedDebugAndroidTest --no-daemon
```

Expected: unit tests, lint, debug assembly, and emulator tests pass.

- [ ] **Step 3: Commit the CI gate**

```bash
rtk git add .github/workflows/build.yml
rtk git commit -m "ci: gate emoji APK on emulator tests"
```

---

### Task 8: Final branch verification and GitHub build

**Files:** No source changes unless a verification error exposes a source defect.

- [ ] **Step 1: Run the complete local unit suite**

```bash
rtk test bash gradlew :app:testDebugUnitTest --no-daemon
```

Expected: zero failed tests.

- [ ] **Step 2: Run Android lint and debug assembly**

```bash
rtk test bash gradlew :app:lintDebug :app:assembleDebug --no-daemon
```

Expected: both tasks complete with exit code 0.

- [ ] **Step 3: Run the complete emulator suite**

```bash
rtk test bash gradlew :app:connectedDebugAndroidTest --no-daemon
```

Expected: zero failed instrumented tests.

- [ ] **Step 4: Review only the feature-branch changes**

```bash
rtk git status --short
rtk git diff main...HEAD --stat
```

Expected: a clean worktree and only emoji-search, suggestion, test, CI, and approved documentation changes.

- [ ] **Step 5: Push the feature branch**

```bash
rtk git push -u origin feat/emoji-search-suggestions
```

Expected: the remote feature branch exists. `main` is unchanged.

- [ ] **Step 6: Dispatch the branch build**

```bash
rtk gh workflow run build.yml --ref feat/emoji-search-suggestions
rtk gh run list --workflow build.yml --branch feat/emoji-search-suggestions --limit 1
```

Capture the new run ID. Then wait for completion:

```bash
rtk gh run watch RUN_ID --exit-status
```

Expected: the workflow succeeds. It uploads `WhisperTypeKeyboard-release`.

- [ ] **Step 7: Report phone-install details**

Provide the Actions run URL, commit SHA, artifact name, and APK filename. Identify the universal APK when device ABI is unknown. Do not merge the branch.

---

## Parallel execution map

The user requested subagents. Use this dependency graph after advisor approval:

1. Task 1 and Task 2 can run in parallel. Their files do not overlap.
2. Task 3 starts after Task 1 because it consumes `EmojiSearchEngine`.
3. Task 4 starts after Tasks 1 and 2.
4. Task 5 starts after Tasks 3 and 4 because it modifies `KeyboardController.kt` after Task 4.
5. Task 6 starts after Tasks 4 and 5.
6. Task 7 starts after Task 6.
7. The main agent performs Task 8 and all final verification.

Each implementation subagent must skip project-wide tests, lint, formatting, pushes, and GitHub workflow dispatch. The main agent performs those actions once after integration.
