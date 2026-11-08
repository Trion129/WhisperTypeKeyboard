#!/usr/bin/env python3
"""Fetch real human speech clips with written references (per-dataset dirs).

The directories are shared with bench/real_speech.sh and cached:
  ~/.cache/whispertype-host/data/<dataset>/{*.wav,refs.tsv}

Datasets (all real recordings, exact reference text included upstream):
  es-all: FLEURS es_419, Multilingual LibriSpeech Spanish, VoxPopuli Spanish
  hi-all: FLEURS hi_in, IndicTTS Hindi (real speakers)

Usage: .venv/bin/python bench/fetch_real.py [--data-dir DIR]
(streaming; deps: soundfile numpy datasets)
"""

from __future__ import annotations

import argparse
import io
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
from datasets import Audio, load_dataset

SOURCES = {
    "fleurs-es": ("google/fleurs", "es_419", "test", "transcription"),
    "mls-es": ("facebook/multilingual_librispeech", "spanish", "test", "transcript"),
    "voxpopuli-es": ("facebook/voxpopuli", "es", "test", "raw_text"),
    "fleurs-hi": ("google/fleurs", "hi_in", "test", "transcription"),
    "indictts-hi": ("SPRINGLab/IndicTTS-Hindi", None, "train", "text"),
}

DATASETS = {
    "es-all": ["fleurs-es", "mls-es", "voxpopuli-es"],
    "hi-all": ["fleurs-hi", "indictts-hi"],
}

COUNTS = {
    "fleurs-es": 25,
    "mls-es": 25,
    "voxpopuli-es": 25,
    "fleurs-hi": 25,
    "indictts-hi": 25,
}


def resample(x, sr):
    if sr == 16000:
        return x
    n = int(len(x) * 16000 / sr)
    return np.interp(np.arange(n) * sr / 16000, np.arange(len(x)), x).astype(np.float32)


def fetch(source: str, out_dir: Path) -> list[tuple[str, str]]:
    path, name, split, text_col = SOURCES[source]
    ds = load_dataset(path, name, split=split, streaming=True, trust_remote_code=True)
    ds = ds.cast_column("audio", Audio(decode=False))
    refs = []
    for i, row in enumerate(ds):
        if len(refs) >= COUNTS[source]:
            break
        text = str(row.get(text_col) or "").strip().replace("\t", " ").replace("\n", " ")
        if not text:
            continue
        raw = row["audio"].get("bytes")
        if raw is None:
            with open(row["audio"]["path"], "rb") as f:
                raw = f.read()
        x, sr = sf.read(io.BytesIO(raw), dtype="float32")
        if x.ndim > 1:
            x = x.mean(axis=1)
        x = resample(x, sr)
        filename = f"{source}_{i:04d}.wav"
        sf.write(out_dir / filename, x, 16000, subtype="PCM_16")
        refs.append((filename, text))
        print(f"  {filename} {len(x)/16000:.1f}s", flush=True)
    return refs


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--data-dir",
        type=Path,
        default=Path.home() / ".cache/whispertype-host/data",
    )
    args = ap.parse_args()
    args.data_dir.mkdir(parents=True, exist_ok=True)
    for dataset, sources in DATASETS.items():
        out_dir = args.data_dir / dataset
        refs_path = out_dir / "refs.tsv"
        if refs_path.is_file():
            n = sum(1 for l in refs_path.read_text(encoding="utf-8").splitlines() if l.strip())
            print(f"{dataset}: already fetched ({n} clips)")
            if n:
                continue
        out_dir.mkdir(parents=True, exist_ok=True)
        refs_path.write_text("", encoding="utf-8")
        print(f"Fetching {dataset} ...")
        for source in sources:
            try:
                refs = fetch(source, out_dir)
            except Exception as e:  # one source failing must not stop the bench
                print(f"  {source} unavailable: {e}", file=sys.stderr)
                continue
            with refs_path.open("a", encoding="utf-8") as f:
                for n, t in refs:
                    f.write(f"{n}\t{t}\n")


if __name__ == "__main__":
    main()
