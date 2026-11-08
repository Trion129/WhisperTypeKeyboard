#!/usr/bin/env python3
"""Score app-pipeline + whisper.cpp reference results against refs.tsv.

  python3 bench/real_speech_score.py refs.tsv <app.jsonl> [ref.jsonl]

Prints per file: error rate (WER for spaced scripts, CER for Devanagari),
repeat runaways, U+FFFD characters, out-of-script rows, and the worst clips.
stdlib only.
"""
import json
import re
import statistics
import sys
import unicodedata

DEVA = re.compile(r"[ऀ-ॿ]")


def norm(s):
    s = unicodedata.normalize("NFC", s).lower()
    s = re.sub(r"[^\w\s]", " ", s, flags=re.UNICODE)
    return s.split()


def err(ref, hyp):
    """WER for spaced scripts, CER for Devanagari refs."""
    r, h = norm(ref), norm(hyp)
    if not DEVA.search(ref):
        return _edit(r, h) / max(1, len(r))
    r = [c for c in "".join(r)]
    h = [c for c in "".join(h)]
    return _edit(r, h) / max(1, len(r))


def _edit(a, b):
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def has_loop(text):
    w = text.split()
    for n in range(1, 5):
        for i in range(len(w) - 3 * n + 1):
            if w[i:i + n] == w[i + n:i + 2 * n] == w[i + 2 * n:i + 3 * n]:
                return True
    return bool(re.search(r"(.{1,6})\1{5,}", text))


def score(refs, rows, label):
    errs, meta = [], []
    for r in rows:
        ref = refs[r["file"]].strip()
        hyp = r["text"]
        e = err(ref, hyp)
        errs.append(e)
        meta.append((e, r["file"], hyp, has_loop(hyp), hyp.count("\ufffd"),
                     bool(hyp.strip()), hyp.strip() and not DEVA.search(hyp)))
    if not errs:
        return
    mean = statistics.mean(errs)
    median = statistics.median(errs)
    loops = sum(1 for m in meta if m[3])
    fffd = sum(m[4] for m in meta)
    empty = sum(1 for m in meta if not m[5])
    foreign = sum(1 for m in meta if m[6])
    print(f"{label:8s} n={len(rows):3d} err={mean:.3f} median={median:.3f} loops={loops} "
          f"fffd={fffd} empty={empty} outOfScript={foreign}")
    for e, name, hyp, loop, _, _, _ in sorted(meta, reverse=True)[:5]:
        flag = " LOOP" if loop else ""
        print(f"  {e:.2f} {name}{flag}: {hyp[:110]}")


def main():
    refs = dict(
        l.split("\t", 1) for l in open(sys.argv[1], encoding="utf-8").read().splitlines() if "\t" in l
    )
    for i, path in enumerate((x for x in sys.argv[2:] if x), start=0):
        with open(path, encoding="utf-8") as f:
            rows = [json.loads(l) for l in f if l.strip()]
        label = "app" if i == 0 else f"ref{i}"
        score(refs, rows, label)


if __name__ == "__main__":
    main()
