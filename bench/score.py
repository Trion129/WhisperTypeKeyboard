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

    print(f"{'file':<28} {'dur s':>6} {'ms':>7} {'tok/cap':>9} {'lang':>5} "
          f"{'flags':>9} {'CER':>6} {'WER':>6}  text")
    cers, wers, times, cap_hits, loss_rows = [], [], [], 0, 0
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
        tok = (f"{r['first_pass_tokens']}/{r['token_cap']}"
               if "first_pass_tokens" in r and "token_cap" in r else "-")
        lang = (r.get("detected_language") or "-")[:5]
        loss = int(r.get("empty_tokens") or 0)
        loss_rows += 1 if loss else 0
        flag = " ".join(f for f in (
            "CAP-HIT" if hit else "",
            "RETRY" if r.get("used_retry") else "",
            "PAD-UP" if r.get("padded_up_front") else "",
            "SPLIT" if r.get("split_decodes") else "",
            "BYTE-LOSS" if loss else "",
        ) if f)
        snippet = text if len(text) <= 60 else text[:57] + "..."
        dur = r.get("duration_s")
        print(f"{r.get('file', '?')[:28]:<28} "
              f"{(f'{dur:.1f}' if dur is not None else '-'):>6} "
              f"{r.get('transcribe_ms', '-'):>7} {tok:>9} {lang:>5} "
              f"{flag:>9} "
              f"{fmt(rates['cer']):>6} {fmt(rates['wer']):>6}  {snippet}")

    print()
    cap_rows = sum("hit_token_cap" in r for r in rows)
    print(f"files: {len(rows)}  scored: {len(cers)}  cap hits: {cap_hits} ({cap_rows} Whisper traces)")
    padded_rows = sum(1 for r in rows if r.get("padded_up_front"))
    retried_rows = sum(1 for r in rows if r.get("used_retry"))
    if padded_rows or retried_rows:
        print(f"padded up front: {padded_rows}  retried: {retried_rows}")
    if loss_rows:
        print(f"BYTE-LOSS rows: {loss_rows} (runtime dropped byte tokens; upgrade sherpa-onnx)")
    if cers:
        print(f"mean CER: {sum(cers) / len(cers):.3f}  mean WER: {sum(wers) / len(wers):.3f}")
    if times:
        print(f"transcribe_ms median: {percentile(times, 50)}  p95: {percentile(times, 95)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
