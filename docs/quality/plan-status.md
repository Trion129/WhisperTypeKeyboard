# ASR quality plan — execution status

Source plan: `~/plans/2026-09-27-whispertype-asr-quality-plan.md`.
This ledger tracks what is done, what is measured, and what stays
**unverified**. Updated 2026-10-03.

> Standing rule from the plan: the primary outcome is the user's perceived
> quality on their phone. Desktop numbers, CI and unit tests do not count
> as a phone-quality fix and must not be presented as one.

## What exists now (this branch)

| Plan step | Artifact | Status |
|---|---|---|
| 1.1 phone state | `docs/quality/phone-state-checklist.md` | ready, **awaiting phone** |
| 1.2 capture | `docs/quality/capture-protocol.md` | ready, **awaiting phone**; FLEURS stand-in fixtures in `bench/fixtures/` |
| 1.3 diagnostics | `AsrDiagnostics.kt` + trace hook in `SherpaWhisperEngine`, wired in `LocalAsrEngine` | implemented; adb-toggled, local-only, no text logged; **remove before release** |
| 1.4 paired baseline | `bench/harness.py`, `bench/fetch_models.sh`, `bench/fetch_fixtures.py`, `bench/README.md`; results in `bench/results/` | harness verified end-to-end |
| 1.4 on-pipeline arm | `PipelineReplayTest` (androidTest) on emulator | run below |
| 3.8 preprocessing tests | `WavReaderTest.kt` (15 fixture tests) + `AsrDiagnosticsTest.kt` (3) | passing (144 JVM tests green); release variant compiles |

`WavReaderTest` pins the exact preprocessing semantics the harness
mirrors: float channel averaging in `decodePcm`, linear-interpolation
resampling (truncating output length), and peak normalization applied
**after** resampling. Writing these tests caught two subtleties worth
knowing: normalization rescales every earlier hand-computed expectation,
and the channel mean is a float division, not an integer one.

## Measured so far (desktop, FLEURS fixtures — NOT the user's phone)

9 scripted clips (3× Hindi, 3× Spanish, 3× English), references in
`bench/fixtures/refs.tsv`, sherpa-onnx 1.13.4 (the app's pin), language
auto-detect. Mean WER across the 9 clips:

| arm | tiny model | small model |
|---|---|---|
| app (mirror of installed pipeline) | 0.594 | 0.292 |
| raw (no peak normalization) | 0.468 | 0.289 |
| app-nopad (no token-cap retry) | 0.463 | 0.334 |
| faster-whisper reference | 0.432 | 0.234 |

Per-utterance findings (small model):

- **Spanish and English are healthy** on this pipeline: WER 0.0–0.12.
- **Hindi fails in two distinct ways.** `hi_in_1718`: 75/90 tokens, no cap
  hit, WER 1.0 — the decode just fails under the cap (model/decoder
  quality). `hi_in_1766` (54/54) and `hi_in_1784` (82/82): exact token-cap
  hits; the existing pad retry improves them (nopad 0.775/0.847 → app
  0.575/0.667) but cannot reach the reference decoder (0.40/0.26).
- **faster-whisper shows zero truncations on identical weights** and beats
  the app arm on every affected-language clip — the decoder gap the plan
  describes, reproduced on the same samples with the same size.
- **Peak normalization is a real, model-dependent variable**, not a red
  herring: on tiny it produced a repetition collapse (WER 2.18 vs 1.0 raw
  on `hi_in_1766`); on small the difference disappears. The paired arms
  are what make that visible.
- Padded-retry decodes cost ~32 s p95 on this desktop for a 30 s window —
  the retry is not free.

These numbers reproduce failure *classes* and validate the harness; they
are not the phone baseline and are not Gate A evidence.

## On-pipeline replay (emulator `whisper-pixel6-api35`, x86_64)

`PipelineReplayTest` ran the same 9 fixtures through the branch build's
real `WavReader` + `SherpaWhisperEngine` (see
`bench/results/emulator-small.jsonl`). Against the desktop `app` mirror:

- Token counts identical on 7/9 clips (25 vs 26 and 81 vs 75 on the other
  two — single-token ONNX float variance across platforms).
- Cap-hit and pad-retry decisions identical on all 9 clips
  (`hi_in_1766` 54/54 and `hi_in_1784` 82/82 retried on both).
- Transcripts near-identical; the few differences are single characters
  or punctuation, not structure.

Conclusion: the harness `app` arm is a faithful proxy for the app's
pipeline, so desktop paired numbers can be trusted for *decisions about
decode quality*. What the emulator cannot answer: phone CPU timings (its
x86_64 runs were 4–47 s per clip) and anything about the real mic path —
both still need the S22 Ultra. Notable decode observation from the replay:
Hindi output is garbled at the character level (missing matras, one clip
drifting into Urdu script) even where no cap was hit.

## Gates

- **Gate A (state/capture vs decode): UNVERIFIED.** Requires the
  phone-state checklist output and paired phone recordings. No claim about
  the user's installed state has evidence yet.
- **Gate B (decoder/model candidate): NOT REACHED.** No candidate has been
  selected and nothing has been integrated. faster-whisper is a desktop
  reference only; whisper.cpp remains a candidate, not an approved
  dependency.

## Next actions (in order)

1. Run `docs/quality/phone-state-checklist.md` on the S22 Ultra (no
   reinstall, ~10 minutes) and fill in its fields.
2. Record the corpus per `docs/quality/capture-protocol.md` (≥20
   utterances per affected language, holdouts marked).
3. Rerun `bench/harness.py` on the phone wavs (they arrive in
   `Download/WhisperTypeDiagnostics/` when diagnostics is on) → this is
   the Gate A paired baseline. Decide Gate A from it.
4. Only if Gate A says the capture/state is sound and quality is still
   poor: Phase 2 candidate comparison on those same samples, with the
   on-device resource benchmark on the phone (time, RAM, battery, APK
   size), per plan steps 5–7.

## Stop conditions (restated from the plan)

If after a verified-correct pipeline the user still hears no improvement,
stop and return to the boundary where results diverge. No decoder swap,
bigger model, or preprocessing change ships as an unexplained "fix".
