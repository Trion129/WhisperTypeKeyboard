"""Stdlib-only transcript scoring shared by harness.py and score.py."""

from __future__ import annotations

import re

_PUNCT = re.compile(r"[^\w\s]|_", re.UNICODE)


def normalize_text(text: str) -> str:
    text = _PUNCT.sub(" ", text.lower())
    return " ".join(text.split())


def levenshtein(a: list, b: list) -> int:
    if not a:
        return len(b)
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def error_rates(reference: str, hypothesis: str) -> dict:
    ref_n = normalize_text(reference)
    hyp_n = normalize_text(hypothesis)
    if not ref_n:
        return {"wer": None, "cer": None, "ref_words": 0}
    ref_w, hyp_w = ref_n.split(), hyp_n.split()
    wer = levenshtein(ref_w, hyp_w) / len(ref_w)
    ref_c, hyp_c = list(ref_n.replace(" ", "")), list(hyp_n.replace(" ", ""))
    cer = levenshtein(ref_c, hyp_c) / max(len(ref_c), 1)
    return {"wer": round(wer, 4), "cer": round(cer, 4), "ref_words": len(ref_w)}


def percentile(values: list, p: float):
    if not values:
        return None
    s = sorted(values)
    k = min(len(s) - 1, max(0, round(p / 100.0 * (len(s) - 1))))
    return s[k]
