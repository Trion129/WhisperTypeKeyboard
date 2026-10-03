#!/usr/bin/env python3
"""Fetch consent-free scripted speech fixtures with written references.

Pulls a handful of FLEURS clips per language (studio-quality scripted
sentences, CC-BY) as 16 kHz mono PCM16 wavs plus a refs.tsv for
bench/harness.py. This is a stand-in for the on-phone capture protocol
(docs/quality/capture-protocol.md): same-language, same-reference paired
comparisons, but not the app's own AudioRecord path.

Usage:
  .venv/bin/python bench/fetch_fixtures.py --out fixtures/ \
      --langs hi_in,es_419,en_us --per-lang 3
"""

from __future__ import annotations

import argparse
import io
from pathlib import Path

import soundfile as sf
from datasets import Audio, load_dataset


def fetch_language(lang: str, count: int, out_dir: Path) -> list[tuple[str, str]]:
    ds = load_dataset("google/fleurs", lang, split="test", streaming=True)
    ds = ds.cast_column("audio", Audio(decode=False))
    rows: list[tuple[str, str]] = []
    for row in ds:
        if len(rows) >= count:
            break
        raw = row["audio"]["bytes"]
        samples, sr = sf.read(io.BytesIO(raw), dtype="float32")
        name = f"{lang}_{row['id']}.wav"
        # FLEURS ships 16 kHz mono; encode as PCM16 like AudioRecorder does.
        sf.write(out_dir / name, samples, sr, subtype="PCM_16")
        rows.append((name, row["transcription"].strip()))
        print(f"  {name}: {samples.shape[0] / sr:.1f}s")
    return rows


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--langs", default="hi_in,es_419,en_us")
    ap.add_argument("--per-lang", type=int, default=3)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    refs: list[tuple[str, str]] = []
    for lang in args.langs.split(","):
        lang = lang.strip()
        if not lang:
            continue
        print(f"Fetching {lang} ...")
        refs += fetch_language(lang, args.per_lang, args.out)

    refs_path = args.out / "refs.tsv"
    with refs_path.open("w", encoding="utf-8") as f:
        for name, text in refs:
            f.write(f"{name}\t{text}\n")
    print(f"Wrote {refs_path} ({len(refs)} utterances)")


if __name__ == "__main__":
    main()
