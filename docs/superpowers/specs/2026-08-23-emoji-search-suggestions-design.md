# Emoji Search and Suggestions Design

Date: 2026-08-23
Status: Approved design. Implementation blocked until plan advisor approval.

## Goal

Correct emoji search in WhisperType Keyboard. Add emoji candidates to the normal suggestion strip. Keep all playtest changes on one feature branch.

## Current problem

The emoji search label is a passive `TextView`. It cannot accept input.

Emoji mode also removes the letter and backspace keys. The remaining space key writes to the host editor through `currentInputConnection`.

The search query therefore has no valid input path. A partial patch to the visible field does not correct this routing problem.

## Decisions

Use explicit local search state. Do not use an embedded `EditText` or a separate activity.

Use typed suggestion items. A suggestion is either a word or an emoji.

When the user taps an emoji suggestion, replace the trigger word. Preserve one trailing space when it exists.

Use `feat/emoji-search-suggestions` for all implementation and phone-playtest changes. Do not merge the branch before phone-playtest approval.

## Full-catalog search

The emoji panel has `Browse` and `Search` states.

The user enters `Search` by tapping the search row. A back action returns to `Browse` without closing emoji mode.

Search mode contains these controls:

- A local query row with a clear action.
- A scrollable emoji result grid.
- Local QWERTY letter rows.
- A backspace key.
- A space key for multi-word queries.
- An `ABC` action that exits emoji mode.

`EmojiSearchSession` owns the query. Letter, space, clear, and backspace events update this session.

Search events do not call the host `InputConnection`. Only an emoji result tap commits text to the host editor.

Search covers all catalog groups. It does not depend on the active browse category.

### Bounded search geometry

Search mode uses a bounded panel rather than an unbounded emoji grid:

- `KeyboardController` fixes the search panel height at 112dp.
- `EmojiPanelView.renderSearch` uses a fixed 36dp query header.
- The emoji results use the remaining weighted `ScrollView` viewport.
- The local keyboard keeps three fixed 48dp QWERTY rows plus a fixed 48dp utility row.

The host editor must remain fully above the IME after opening emoji search and after entering a search query. The integration suite uses the stable `test_input` and `keyboard_root` selectors, then reads the full `EditText` geometry through `ActivityScenario` (`getLocationOnScreen` plus measured `height`) so clipped `visibleBounds` cannot hide overlap. It requires positive full-view height, a nonnegative top, and full-view bottom no greater than the visible top of `keyboard_root`; it also requires the supplementary visible bottom to be no greater than the display height.

The search engine normalizes case and whitespace. It ranks results in this order:

1. Exact name, keyword, or alias matches.
2. Word-prefix matches.
3. Substring matches.
4. Catalog order for equal scores.

Queries such as `heart`, `red heart`, `laugh`, and `flag india` must return relevant results.

After an emoji commit, the keyboard clears the query and stays in emoji mode. The keyboard records the emoji in recents when private mode is off.

## Normal-keyboard emoji suggestions

The normal suggestion strip uses typed items:

- `WordSuggestion`
- `EmojiSuggestion`

The emoji suggestion engine examines the current word. It also examines the previous word after one trailing space.

The engine uses exact aliases and strong catalog keyword matches. It does not return emoji candidates for incomplete prefixes.

An emoji match can use two of the three suggestion slots. A normal word candidate uses the remaining slot.

The first alias set includes these mappings:

- `haha`, `lol`, `laugh` to `😂` and `🤣`.
- `cry`, `sad` to `😢` and `😭`.
- `love`, `heart` to `❤️` and `😍`.

The implementation can include more reviewed aliases in the same tested mapping.

An emoji tap replaces the trigger word:

- `haha` becomes `😂`.
- `haha ` becomes `😂 `.

A word tap keeps the current autocomplete behavior. Emoji suggestions do not update unigram learning.

## Components

### `EmojiSearchSession`

This pure Kotlin component owns the query and produces search-state changes. It has no Android dependencies.

### `EmojiCatalog`

This component supplies normalized, scored search across names, keywords, aliases, and groups. It keeps deterministic catalog-order ties.

### `EmojiSuggestionEngine`

This pure Kotlin component maps an editor token to at most two emoji candidates. It reuses catalog search and the reviewed alias map.

### Suggestion model

A typed model separates word commits from emoji replacement actions. This removes string-based branching from the view code.

### `KeyboardController`

The controller selects the input destination. Search-mode input updates `EmojiSearchSession`. Normal-mode input updates the host editor.

The controller applies typed suggestion actions. It does not contain search ranking or alias rules.

## Error and state handling

An empty query shows no filtered result set and keeps the search screen ready for input.

A query with no matches shows an empty result state. It does not modify the host editor.

A missing or invalid emoji catalog returns no results. It does not crash the IME.

Panel exit clears transient search state. An input-session change also clears transient search state.

Private mode prevents recents and unigram writes. It does not disable local search or emoji candidates.

## Unit tests

Add JVM tests for these contracts:

1. Full-catalog search across all groups.
2. Exact, prefix, substring, keyword, alias, case, and whitespace behavior.
3. Stable result ranking.
4. Local letter, space, backspace, clear, and reset actions.
5. No host commit action from query events.
6. Exact alias matching with no partial-token match.
7. Two emoji candidates plus one word candidate.
8. Trigger detection before and after one trailing space.
9. Replacement of `haha` with `😂`.
10. Replacement of `haha ` with `😂 `.
11. Correct UTF-16 deletion length.
12. No unigram update for emoji replacement.

## Android integration tests

Add a UiAutomator suite under `app/src/androidTest`.

The suite uses the existing `SetupActivity` test field and the actual WhisperType IME.

The suite performs these scenarios:

1. Select WhisperType as the emulator IME.
2. Open emoji search.
3. Enter `red heart` with local IME keys.
4. Make sure that the host field stays empty during query input.
5. Tap the filtered heart result.
6. Make sure that the host field contains the selected emoji.
7. Return to normal letters and enter `haha`.
8. Make sure that `😂` and `🤣` appear in the suggestion strip.
9. Tap `😂` and make sure that it replaces `haha`.
10. Repeat the replacement with one trailing space.

Dynamic IME views receive stable resource IDs or content descriptions. The suite does not use screen coordinates.

## GitHub build

Update `.github/workflows/build.yml` with an API-35 emulator job. The job installs and selects WhisperType before it starts the integration suite.

The signed release job depends on the JVM and emulator tests. Upload instrumented-test reports when a test fails.

After local verification, push `feat/emoji-search-suggestions`. Dispatch `build.yml` with that branch as the ref.

Wait for the GitHub Actions run. Then report the run URL, commit SHA, artifact name, and correct phone APK filename.

## Playtest workflow

Keep all playtest fixes on `feat/emoji-search-suggestions`.

For each phone finding:

1. Reproduce the behavior.
2. Add or update a regression test.
3. Correct the source.
4. Run the focused local checks.
5. Push a new branch commit.
6. Dispatch a new GitHub APK build.

Do not merge the branch until the user approves the phone playtest.

## Approval gates

1. The user approves this design.
2. The implementation plan receives advisor approval.
3. Only then create the feature branch and start implementation.
4. The user approves phone behavior before merge work starts.
