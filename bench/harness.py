#!/usr/bin/env python3
"""Paired ASR quality harness for WhisperType (plan step 1.4).

Replays the SAME wav files through several decoders so their errors can be
compared per utterance:

  app    - faithful mirror of the installed Android pipeline:
           PCM16 decode -> WavReader.preprocess (linear resample + peak
           normalization, see app/src/main/java/me/trion/whispertype/voice/WavReader.kt)
           -> sherpa-onnx greedy_search with the app's settings
           (SherpaWhisperEngine.kt) including its pad-to-30s token-cap retry.
  raw    - identical, except the peak normalization step is skipped. Isolates
           what normalization alone does to the transcript.
  app-nopad - app arm with the token-cap retry disabled; shows what the retry
           contributes.
  faster - faster-whisper (full OpenAI decoding: beam search + temperature
           fallback) on the SAME normalized samples, same model size/weights.
           Quality reference only, NOT an app dependency.
  whispercpp - ggml-org/whisper.cpp CLI (the on-device candidate runtime,
           plan step 5) on the same normalized samples, written out as a
           16 kHz mono PCM16 wav. Desktop timing is not phone timing.

Everything is local: audio, references and transcripts stay on this machine.
Only audio metrics and token counts are logged; transcripts are written to
the output directory for manual inspection, never uploaded.

Usage (see bench/README.md):
  uv venv --python 3.12 .venv && uv pip install sherpa-onnx==<app version> \
      soundfile faster-whisper numpy
  python bench/harness.py --wav-dir fixtures/ --references fixtures/refs.tsv \
      --model small --sherpa-model-dir /tmp/whisper-check/models/small \
      --language hi --arms app,raw,faster --out results/small-hi
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import struct
import subprocess
import tempfile
import time
import wave
from pathlib import Path

import numpy as np
import soundfile as sf

from scoring import error_rates, percentile

TARGET_SAMPLE_RATE = 16_000
# sherpa clamps input to 2950 feature frames (10 ms hops = 29.5 s) and warns
# once input reaches that; see offline-recognizer-whisper-impl.h
# (`max_num_frames - 50`).
MAX_FRAMES = 2950
SUPPORTED_BITS = (8, 16)


def window_samples(sample_rate: int) -> int:
    # One frame under MAX_FRAMES: longest input sherpa takes without warning.
    return (MAX_FRAMES - 1) * sample_rate // 100


# --------------------------------------------------------------------------
# Android pipeline mirror (WavReader.kt + AudioRecorder.kt)
# --------------------------------------------------------------------------

def decode_wav_bytes(data: bytes) -> tuple[np.ndarray, int]:
    """Mirror of WavReader.read's parsing + decodePcm: returns float mono
    samples (pre-normalization) and the file's sample rate."""
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise ValueError("Not a valid RIFF/WAVE file")
    pos = 12
    sample_rate = 0
    bits_per_sample = 16
    channels = 1
    pcm: bytes | None = None
    while pos + 8 <= len(data):
        chunk_id = data[pos:pos + 4]
        (chunk_size,) = struct.unpack_from("<I", data, pos + 4)
        body = data[pos + 8:pos + 8 + chunk_size]
        if chunk_id == b"fmt ":
            audio_format, channels, sample_rate, _byte_rate, _align, bits_per_sample = \
                struct.unpack_from("<HHIIHH", body, 0)
            if audio_format != 1:
                raise ValueError(f"Only PCM WAV supported (format={audio_format})")
        elif chunk_id == b"data":
            pcm = body
        pos += 8 + chunk_size + (chunk_size & 1)
    if pcm is None:
        raise ValueError("No data chunk found")
    if sample_rate <= 0:
        raise ValueError("No fmt chunk found")
    return decode_pcm16(pcm, bits_per_sample, channels), sample_rate


def decode_pcm16(data: bytes, bits_per_sample: int, channels: int) -> np.ndarray:
    """Mirror of WavReader.decodePcm: average channel int values, then scale."""
    bytes_per_sample = bits_per_sample // 8
    total_frames = len(data) // (bytes_per_sample * channels)
    if bits_per_sample not in SUPPORTED_BITS:
        raise ValueError(f"Unsupported bits per sample: {bits_per_sample}")
    if channels == 1:
        if bits_per_sample == 16:
            ints = np.frombuffer(data[:total_frames * 2], dtype="<i2").astype(np.int64)
            return (ints.astype(np.float32) / 32768.0).astype(np.float32)
        ints = (np.frombuffer(data[:total_frames], dtype=np.uint8).astype(np.int64) - 128)
        return (ints.astype(np.float32) / 128.0).astype(np.float32)
    frames = np.frombuffer(
        data[:total_frames * bytes_per_sample * channels],
        dtype=np.int16 if bits_per_sample == 16 else np.uint8,
    )
    if bits_per_sample == 8:
        frames = frames.astype(np.int64) - 128
    else:
        frames = frames.astype(np.int64)
    frames = frames.reshape(total_frames, channels)
    # Kotlin WavReader.decodePcm accumulates into a Double and divides by
    # channels in float: a plain mean.
    mono = frames.mean(axis=1)
    scale = 128.0 if bits_per_sample == 8 else 32768.0
    return (mono.astype(np.float32) / scale).astype(np.float32)


def resample_linear(input_samples: np.ndarray, source_rate: int) -> np.ndarray:
    """Mirror of WavReader.resample: nearest-frame linear interpolation with
    truncating output length."""
    if source_rate == TARGET_SAMPLE_RATE:
        return input_samples
    output_size = int(input_samples.size * TARGET_SAMPLE_RATE / source_rate)
    output = np.empty(output_size, dtype=np.float32)
    ratio = source_rate / TARGET_SAMPLE_RATE
    last = input_samples.size - 1
    for i in range(output_size):
        source = i * ratio
        left = min(max(int(source), 0), last)
        right = min(left + 1, last)
        fraction = source - left
        output[i] = input_samples[left] * (1.0 - fraction) + input_samples[right] * fraction
    return output


def preprocess_app(samples: np.ndarray, sample_rate: int, normalize: bool = True) -> np.ndarray:
    """Mirror of WavReader.preprocess (normalize=False skips only the peak
    division, which is the one step the `raw` arm removes)."""
    if samples.size == 0:
        return samples
    resampled = resample_linear(samples, sample_rate)
    if not normalize:
        return resampled
    peak = float(np.max(np.abs(resampled))) if resampled.size else 0.0
    if peak == 0.0:
        return resampled
    return (resampled / peak).astype(np.float32)


def audio_metrics(samples: np.ndarray, sample_rate: int) -> dict:
    """Capture-quality metrics from plan step 1.3. No text involved."""
    if samples.size == 0:
        return {"duration_s": 0.0, "rms": 0.0, "peak": 0.0, "clipping_ratio": 0.0,
                "silence_ratio": 1.0, "first_voiced_s": None, "last_voiced_s": None}
    peak = float(np.max(np.abs(samples)))
    rms = float(np.sqrt(np.mean(np.square(samples, dtype=np.float64))))
    # A PCM16 sample is "clipped" when within 3 LSB of full scale.
    clipping = float(np.mean(np.abs(samples) >= (32767 - 2) / 32768.0))
    silence = float(np.mean(np.abs(samples) < 1e-4))
    voiced = np.abs(samples) > 0.01
    if voiced.any():
        idx = np.flatnonzero(voiced)
        first_s, last_s = float(idx[0] / sample_rate), float(idx[-1] / sample_rate)
    else:
        first_s, last_s = None, None
    return {
        "duration_s": round(samples.size / sample_rate, 3),
        "rms": round(rms, 6),
        "peak": round(peak, 6),
        "clipping_ratio": round(clipping, 6),
        "silence_ratio": round(silence, 6),
        "first_voiced_s": round(first_s, 3) if first_s is not None else None,
        "last_voiced_s": round(last_s, 3) if last_s is not None else None,
    }


# --------------------------------------------------------------------------
# sherpa arm: the app's SherpaWhisperEngine.transcribe, verbatim
# --------------------------------------------------------------------------

class SherpaArm:
    def __init__(self, model_dir: Path, model_id: str, language: str, num_threads: int = 2):
        import sherpa_onnx
        self.language = language  # "" mirrors ModelCatalog.AUTO_LANGUAGE
        # Mirrors SherpaWhisperEngine's OfflineRecognizerConfig exactly:
        # greedy_search, 2 threads, cpu provider, no tail_paddings (-1).
        self.recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=str(model_dir / f"{model_id}-encoder.int8.onnx"),
            decoder=str(model_dir / f"{model_id}-decoder.int8.onnx"),
            tokens=str(model_dir / f"{model_id}-tokens.txt"),
            language=language,
            task="transcribe",
            num_threads=num_threads,
            decoding_method="greedy_search",
            provider="cpu",
        )

    @staticmethod
    def decoder_token_cap(sample_count: int, sample_rate: int) -> int:
        # SherpaWhisperEngine.decoderTokenCap: round-half-up fbank frames,
        # clamped to MAX_FRAMES, times 6 tokens per 100 frames.
        frames = min((sample_count * 100 + sample_rate // 2) // sample_rate, MAX_FRAMES)
        return frames * 6 // 100

    def _decode(self, samples: np.ndarray, sample_rate: int):
        stream = self.recognizer.create_stream()
        try:
            stream.accept_waveform(sample_rate, samples)
            t0 = time.perf_counter()
            self.recognizer.decode_stream(stream)
            elapsed_ms = (time.perf_counter() - t0) * 1000.0
            result = stream.result
            text = result.text or ""
            tokens = list(result.tokens or [])
            return text, tokens, elapsed_ms
        finally:
            del stream

    def transcribe(self, samples: np.ndarray, sample_rate: int, retry_enabled: bool = True):
        """Mirror of SherpaWhisperEngine.transcribe including the retry."""
        first_text, first_tokens, first_ms = self._decode(samples, sample_rate)
        cap = self.decoder_token_cap(samples.size, sample_rate)
        hit_cap = bool(first_tokens) and samples.size < window_samples(sample_rate) \
            and len(first_tokens) >= cap
        total_ms = first_ms
        final_text, final_tokens = first_text, first_tokens
        if hit_cap and retry_enabled:
            window = window_samples(sample_rate)
            padded = samples if samples.size >= window else np.pad(
                samples, (0, window - samples.size)).astype(np.float32)
            retry_text, retry_tokens, retry_ms = self._decode(padded, sample_rate)
            total_ms += retry_ms
            # SherpaWhisperEngine.preferLonger: a capped first pass is kept
            # unless the padded retry produced strictly more tokens.
            if len(retry_tokens) > len(first_tokens):
                final_text, final_tokens = retry_text, retry_tokens
        return {
            "text": final_text.strip(),
            "first_pass_tokens": len(first_tokens),
            "token_cap": cap,
            "hit_token_cap": hit_cap,
            "final_tokens": len(final_tokens),
            "decode_ms": round(total_ms, 1),
        }


# --------------------------------------------------------------------------
# faster-whisper reference arm
# --------------------------------------------------------------------------

class FasterArm:
    """Full Whisper decoding (beam + temperature fallback) on the same
    normalized samples. Reference for what the model can do; not the app."""

    def __init__(self, size: str, language: str):
        from faster_whisper import WhisperModel
        self.model = WhisperModel(
            f"Systran/faster-whisper-{size}", device="cpu", compute_type="int8"
        )
        self.language = language or None

    def transcribe(self, samples: np.ndarray, sample_rate: int):
        assert sample_rate == TARGET_SAMPLE_RATE
        t0 = time.perf_counter()
        segments, info = self.model.transcribe(
            samples,
            language=self.language,
            task="transcribe",
            beam_size=5,
            vad_filter=False,
        )
        text = "".join(seg.text for seg in segments)
        elapsed_ms = (time.perf_counter() - t0) * 1000.0
        return {
            "text": text.strip(),
            "detected_language": info.language,
            "language_probability": round(info.language_probability, 3),
            "decode_ms": round(elapsed_ms, 1),
        }


# --------------------------------------------------------------------------
# whisper.cpp candidate arm (plan step 5: the on-device runtime candidate)
# --------------------------------------------------------------------------

def write_pcm16_wav(path: Path, samples: np.ndarray, sample_rate: int) -> None:
    """Whisper.cpp's CLI reads 16 kHz mono 16-bit PCM only."""
    clipped = np.clip(samples, -1.0, 1.0)
    pcm = (clipped * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        w.writeframes(pcm.tobytes())


class WhisperCppArm:
    """Official ggml-org/whisper.cpp CLI on the same normalized samples.

    Candidate on-device runtime: full Whisper decode loop, beam search with
    best-of fallback, no sherpa-style token cap. Runs on a PCM16 wav
    round-trip (the CLI's input format), which is quantization noise far
    below the model's own variance. Desktop timing is not phone timing."""

    def __init__(self, cli: Path, model: Path, language: str,
                 beam_size: int = 5, best_of: int = 5, threads: int = 8):
        self.cli = cli
        self.model = model
        self.language = language or "auto"
        self.beam_size = beam_size
        self.best_of = best_of
        self.threads = threads
        self.tmp = Path(tempfile.mkdtemp(prefix="whispercpp-"))
        self._n = 0

    def transcribe(self, samples: np.ndarray, sample_rate: int):
        assert sample_rate == TARGET_SAMPLE_RATE
        wav_path = self.tmp / f"clip{self._n:04d}.wav"
        self._n += 1
        write_pcm16_wav(wav_path, samples, sample_rate)
        cmd = [
            str(self.cli),
            "-m", str(self.model),
            "-f", str(wav_path),
            "-l", self.language,
            "-t", str(self.threads),
            "-bs", str(self.beam_size),
            "-bo", str(self.best_of),
            "-nt",   # no timestamps
            "-np",   # no prints other than the transcript
        ]
        t0 = time.perf_counter()
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=900)
        elapsed_ms = (time.perf_counter() - t0) * 1000.0
        if proc.returncode != 0:
            raise RuntimeError(
                f"whisper.cpp failed ({proc.returncode}): {proc.stderr[-500:]}"
            )
        return {
            "text": proc.stdout.strip(),
            "decode_ms": round(elapsed_ms, 1),
        }


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------

ARMS = ("app", "raw", "app-nopad", "faster", "whispercpp")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--wav-dir", required=True, type=Path)
    ap.add_argument("--references", type=Path, default=None,
                    help="TSV: <wav filename>\\t<reference text>")
    ap.add_argument("--model", required=True,
                    help="sherpa model id, e.g. tiny, small, base.en")
    ap.add_argument("--sherpa-model-dir", required=True, type=Path)
    ap.add_argument("--language", default="",
                    help="language code forced into every arm ('' = auto)")
    ap.add_argument("--arms", default="app,raw,faster",
                    help=f"comma list from {ARMS}")
    ap.add_argument("--faster-size", default=None,
                    help="faster-whisper size (defaults to --model minus .en)")
    ap.add_argument("--whispercpp-cli", type=Path, default=None,
                    help="path to whisper.cpp's whisper-cli binary")
    ap.add_argument("--whispercpp-model", type=Path, default=None,
                    help="path to the ggml model (e.g. ggml-small-q8_0.bin)")
    ap.add_argument("--whispercpp-threads", type=int, default=8)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--limit", type=int, default=None)
    args = ap.parse_args()

    arms = [a.strip() for a in args.arms.split(",") if a.strip()]
    unknown = [a for a in arms if a not in ARMS]
    if unknown:
        raise SystemExit(f"Unknown arms: {unknown}; pick from {ARMS}")

    wavs = sorted(args.wav_dir.glob("*.wav"))
    if args.limit:
        wavs = wavs[:args.limit]
    if not wavs:
        raise SystemExit(f"No wav files in {args.wav_dir}")

    refs = {}
    if args.references:
        for line in args.references.read_text(encoding="utf-8").splitlines():
            if line.strip():
                name, _, text = line.partition("\t")
                refs[name.strip()] = text.strip()

    faster_size = args.faster_size or args.model.removesuffix(".en")
    sherpa = sherpa_faster = wcpp = None
    if "faster" in arms:
        sherpa_faster = FasterArm(faster_size, args.language)
    if "whispercpp" in arms:
        if not args.whispercpp_cli or not args.whispercpp_model:
            raise SystemExit("whispercpp arm needs --whispercpp-cli and --whispercpp-model")
        wcpp = WhisperCppArm(args.whispercpp_cli, args.whispercpp_model,
                             args.language, threads=args.whispercpp_threads)
    if any(a in arms for a in ("app", "raw", "app-nopad")):
        sherpa = SherpaArm(args.sherpa_model_dir, args.model, args.language)

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "texts").mkdir(exist_ok=True)
    jsonl_path = args.out / "results.jsonl"

    rows = []
    with jsonl_path.open("w", encoding="utf-8") as jsonl:
        for wav in wavs:
            raw_bytes = wav.read_bytes()
            decoded, file_rate = decode_wav_bytes(raw_bytes)
            app_samples = preprocess_app(decoded, file_rate, normalize=True)
            raw_samples = preprocess_app(decoded, file_rate, normalize=False)
            reference = refs.get(wav.name, "")

            row = {
                "file": wav.name,
                "sample_rate_in_file": file_rate,
                "audio": audio_metrics(decoded, file_rate),
                "has_reference": bool(reference),
                "arms": {},
            }

            for arm in arms:
                if arm == "faster":
                    res = sherpa_faster.transcribe(app_samples, TARGET_SAMPLE_RATE)
                elif arm == "whispercpp":
                    res = wcpp.transcribe(app_samples, TARGET_SAMPLE_RATE)
                elif arm == "raw":
                    res = sherpa.transcribe(raw_samples, TARGET_SAMPLE_RATE)
                elif arm == "app-nopad":
                    res = sherpa.transcribe(app_samples, TARGET_SAMPLE_RATE,
                                            retry_enabled=False)
                else:
                    res = sherpa.transcribe(app_samples, TARGET_SAMPLE_RATE)
                if reference:
                    res.update(error_rates(reference, res["text"]))
                row["arms"][arm] = res
                (args.out / "texts" / f"{wav.stem}.{arm}.txt").write_text(
                    res["text"], encoding="utf-8")

            jsonl.write(json.dumps(row, ensure_ascii=False) + "\n")
            jsonl.flush()
            rows.append(row)
            wers = " ".join(
                f"{a}={'-' if row['arms'][a].get('wer') is None else row['arms'][a]['wer']}"
                for a in arms
            )
            print(f"{wav.name}: {wers}")

    report = build_report(rows, arms, args, faster_size)
    (args.out / "report.md").write_text(report, encoding="utf-8")
    print(f"\nWrote {args.out/'report.md'} and {jsonl_path}")


def build_report(rows: list, arms: list, args, faster_size: str) -> str:
    lines = [
        "# Paired ASR baseline",
        "",
        f"- model: `{args.model}` (faster-whisper reference size: `{faster_size}`)",
        f"- forced language: `{args.language or '(auto)'}`",
        f"- utterances: {len(rows)}"
        + (f" (with references: {sum(1 for r in rows if r['has_reference'])})"),
        f"- wav dir: {args.wav_dir}",
        "",
        "## Per-utterance WER",
        "",
    ]
    header = "| file | s | " + " | ".join(f"{a} WER" for a in arms) + " | trunc(app) |"
    lines += [header, "|" + "---|" * (len(arms) + 3)]
    for r in rows:
        wers = []
        for a in arms:
            w = r["arms"][a].get("wer")
            wers.append("-" if w is None else f"{w:.2f}")
        app_arm = r["arms"].get("app") or r["arms"].get("app-nopad") or {}
        trunc = "CAP" if app_arm.get("hit_token_cap") else ""
        lines.append(
            f"| {r['file']} | {r['audio']['duration_s']} | "
            + " | ".join(wers) + f" | {trunc} |"
        )

    lines += ["", "## Aggregates", "",
              "| arm | mean WER | mean CER | truncations | median ms | p95 ms |", "|---|---|---|---|---|---|"]
    for a in arms:
        scored = [r["arms"][a] for r in rows if r["arms"][a].get("wer") is not None]
        wers = [s["wer"] for s in scored]
        cers = [s["cer"] for s in scored if s.get("cer") is not None]
        times = [s["decode_ms"] for s in (r["arms"][a] for r in rows)]
        truncs = sum(1 for r in rows if r["arms"][a].get("hit_token_cap"))
        mean_wer = f"{sum(wers)/len(wers):.3f}" if wers else "-"
        mean_cer = f"{sum(cers)/len(cers):.3f}" if cers else "-"
        med = percentile(times, 50)
        p95 = percentile(times, 95)
        lines.append(f"| {a} | {mean_wer} | {mean_cer} | {truncs} | {med} | {p95} |")

    worst = sorted(
        (r for r in rows if r["arms"].get("app", {}).get("wer") is not None),
        key=lambda r: r["arms"]["app"]["wer"], reverse=True,
    )[:5]
    if worst:
        lines += ["", "## Worst app-arm utterances (inspect locally, do not share text)", ""]
        for r in worst:
            lines.append(f"### {r['file']} (WER {r['arms']['app']['wer']:.2f})")
            for a in arms:
                t = r["arms"][a].get("text", "")
                lines.append(f"- {a}: `{t[:200]}`")
            lines.append("")

    lines += [
        "## Notes",
        "",
        "- `app` mirrors SherpaWhisperEngine (greedy_search, 2 threads,",
        "  pad-to-30s retry when the first pass hits sherpa's 6 tokens/s cap).",
        "- `raw` is identical without WavReader's peak normalization.",
        "- `faster` is the full-decoder reference on the same normalized",
        "  samples; desktop timing says nothing about phone timing.",
        "- `whispercpp` is ggml-org/whisper.cpp (beam 5, best-of 5) on the",
        "  same normalized samples through a PCM16 wav round-trip — the",
        "  on-device candidate runtime, not (yet) an app dependency.",
        "- Decode times here are NOT phone-representative.",
        "",
    ]
    return "\n".join(lines)


if __name__ == "__main__":
    main()
