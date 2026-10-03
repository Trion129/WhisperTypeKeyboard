# Speech capture protocol (plan step 1.2)

Consented, private, paired speech recordings with written references, for
measuring dictation quality before and after any change. Read fully before
recording.

## Consent, privacy, retention

- Only the user's own voice, reading **non-sensitive scripted phrases**.
  Nothing spontaneous or personal.
- All audio stays on the phone (`Download/WhisperTypeDiagnostics/`) and on
  the local workstation (`bench/fixtures-phone/`, `bench/results/`). No
  automatic upload, no telemetry, no cloud transcription.
- Copying recordings from the phone to the workstation happens only by
  explicit user action (`adb pull` / USB).
- Deleting the corpus: delete `Download/WhisperTypeDiagnostics/` on the
  phone, `bench/fixtures-phone/` and `bench/results/` on the workstation.
  That is the whole retention footprint.
- Sharing outside the machine requires separate explicit opt-in per file;
  transcripts of scripted text may be shared, audio only with opt-in.

## What to record

Per affected language (start with **Hindi** and **Spanish**; keep
**English** as a working-language control), at least **20 utterances**:

- 4 short (< 3 s, one phrase), 8 medium (5–15 s), 4 long (20–30 s — the
  limiter stops at 30 s), 4 with a deliberate mid-sentence pause.
- Of each batch: at least 2 quiet/whispered, 2 with background noise
  (fan/TV), 1 far-from-mic, 1 with accented or borrowed words.
- Prefer sentences from newspapers/books the user picks (scripted, not
  private content).

**Holdout:** every 4th utterance (indices 4, 8, 12, 16, 20) is held out —
never used to tune or select anything, only to confirm winners. Mark them
in `refs.tsv` with a `#holdout` suffix comment or a separate `holdout.tsv`.

## How to record

Two sources, both useful:

1. **App path (required):** enable the diagnostics path
   (`adb shell settings put global whispertype_diagnostics 1`), then
   dictate each phrase through the keyboard exactly as usual. Each
   dictation drops the app's own mic-captured wav (16 kHz mono PCM16,
   post-`AudioRecord`, pre-normalization) into
   `Download/WhisperTypeDiagnostics/`. This is the audio the app really
   sees — the only source that counts for Gate A.
2. **Reference recorder (optional):** the phone's voice recorder app, for
   audibly checking what the mic captured vs what the app wrote.

## References

While recording, write the exact intended text of each phrase into
`bench/fixtures-phone/refs.tsv`, one per line:

```
<filename>.wav<TAB>exact scripted text in that language
```

Record conditions that could matter (noise, distance) in
`bench/fixtures-phone/NOTES.md` per file. Do not transcribe what the app
*output* into refs — only what was *said*.

## Naming

`<lang>_s<NN>.wav` (e.g. `hi_s07.wav`), sequential in recording order;
the holdout rule applies to the index. Language codes follow
`ModelCatalog.languageOptions` (`hi`, `es`, `en`).
