#!/usr/bin/env python3
"""Score a host/emulator replay JSONL against references (stdlib only).

Usage: score.py RESULTS.jsonl [--refs refs.tsv]
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from scoring import error_rates, percentile  # noqa: E402


def load_refs(path: Path) -> dict:
    refs = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or "\t" not in line:
            continue
        name, ref = line.split("\t", 1)
        refs[name.strip()] = ref.strip()
    return refs


def fmt(v) -> str:
    return "-" if v is None else f"{v:.3f}"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("results", type=Path)
    ap.add_argument("--refs", type=Path)
    args = ap.parse_args()

    refs = load_refs(args.refs) if args.refs else {}
    rows = [
        json.loads(line)
        for line in args.results.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]

    print(f"{'file':<28} {'dur s':>6} {'ms':>7} {'tok/cap':>9} {'cap':>7} "
          f"{'CER':>6} {'WER':>6}  text")
    cers, wers, times, cap_hits = [], [], [], 0
    for r in rows:
        text = r.get("text") or ""
        ref = refs.get(r.get("file", ""))
        rates = error_rates(ref, text) if ref is not None else {"cer": None, "wer": None}
        if rates["cer"] is not None:
            cers.append(rates["cer"])
            wers.append(rates["wer"])
        hit = bool(r.get("hit_token_cap"))
        cap_hits += hit
        if r.get("transcribe_ms") is not None:
            times.append(r["transcribe_ms"])
        tok = f"{r.get('first_pass_tokens', '?')}/{r.get('token_cap', '?')}"
        snippet = text if len(text) <= 60 else text[:57] + "..."
        dur = r.get("duration_s")
        print(f"{r.get('file', '?')[:28]:<28} "
              f"{(f'{dur:.1f}' if dur is not None else '-'):>6} "
              f"{r.get('transcribe_ms', '-'):>7} {tok:>9} "
              f"{('CAP-HIT' if hit else ''):>7} "
              f"{fmt(rates['cer']):>6} {fmt(rates['wer']):>6}  {snippet}")

    print()
    print(f"files: {len(rows)}  scored: {len(cers)}  cap hits: {cap_hits}")
    if cers:
        print(f"mean CER: {sum(cers) / len(cers):.3f}  mean WER: {sum(wers) / len(wers):.3f}")
    if times:
        print(f"transcribe_ms median: {percentile(times, 50)}  p95: {percentile(times, 95)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
