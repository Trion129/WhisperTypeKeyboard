#!/usr/bin/env python3
"""Generate app/src/main/assets/emoji/catalog.json for WhisperType IME.

Sources:
  - https://unicode.org/Public/emoji/15.1/emoji-test.txt  (UTS #51 test data: groups,
    subgroups, short names, status, tone variants)
  - CLDR English annotations (en.xml) for extra search keywords.

Output shape (consumed by me.trion.whispertype.ime.EmojiCatalog):
  {"groups": [9 fixed names], "items": [{emoji, group, subgroup, name, keywords,
  tones?}]}

Rules implemented:
  - fully-qualified emoji only; the 9 display groups only (the "component" group is
    dropped).
  - Entries whose short name is a skin-tone variant of a base are skipped; instead the
    base item gets "tones" = [base, light, medium-light, medium, medium-dark, dark].
  - keywords = short-name words (len >= 2, lowercased, punctuation stripped) followed
    by CLDR annotation tts words (deduped, order preserved).
  - Valid UTF-8 JSON, one item per line, well under 2 MB.

Usage:
  python3 scripts/generate_emoji_catalog.py
  python3 scripts/generate_emoji_catalog.py --emoji-test /path/emoji-test.txt \
      --annotations /path/en.xml
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

EMOJI_TEST_URL = "https://unicode.org/Public/emoji/15.1/emoji-test.txt"
ANNOTATIONS_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr/release-45/common/annotations/en.xml"
)
GROUPS = [
    "Smileys & Emotion",
    "People & Body",
    "Animals & Nature",
    "Food & Drink",
    "Travel & Places",
    "Activities",
    "Objects",
    "Symbols",
    "Flags",
]
TONE_LABELS = [
    "light skin tone",
    "medium-light skin tone",
    "medium skin tone",
    "medium-dark skin tone",
    "dark skin tone",
]
LINE_RE = re.compile(
    r"^([0-9A-F ]+)\s*;\s*fully-qualified\s*#\s+(?P<emoji>\S+)\s+E\d+\.\d+\s+(?P<name>.+?)\s*$"
)
VERSION_SUFFIX_RE = re.compile(r"\s+E\d+\.\d+$")
WORD_RE = re.compile(r"[^a-z0-9]+")


def download(url: str, dest: Path) -> Path:
    if dest.exists():
        return dest
    with urllib.request.urlopen(url, timeout=60) as resp:
        dest.write_bytes(resp.read())
    return dest


def parse_emoji_test(path: Path):
    """Yield (group, subgroup, emoji, name) for fully-qualified entries."""
    group = subgroup = None
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith("# group:"):
            group = line[len("# group:") :].strip()
            subgroup = None
        elif line.startswith("# subgroup:"):
            subgroup = line[len("# subgroup:") :].strip()
        elif not line or line.startswith("#"):
            continue
        else:
            m = LINE_RE.match(line)
            if not m:
                continue
            emoji = m.group("emoji")
            name = VERSION_SUFFIX_RE.sub("", m.group("name")).strip()
            yield group, subgroup, emoji, name


def parse_annotations(path: Path) -> dict[str, list[str]]:
    """Map emoji -> tts keywords (the part after '|' in CLDR annotations)."""
    out: dict[str, list[str]] = {}
    root = ET.parse(path).getroot()
    for ann in root.iter("annotation"):
        if ann.get("type") == "tts":
            continue
        text = (ann.text or "").strip()
        if "|" not in text:
            continue
        keywords = [
            w
            for part in text.split("|", 1)[1].split(",")
            for w in (part.strip().lower(),)
            if w
        ]
        if keywords:
            out[ann.get("cp", "")] = keywords
    return out


def keywords_for(name: str, ann_words: list[str]) -> list[str]:
    """Short-name words (len >= 2) then annotation tts words, deduped in order."""
    seen: set[str] = set()
    out: list[str] = []
    for token in name.split() + (ann_words or []):
        word = WORD_RE.sub("", token.lower())
        if len(word) >= 2 and word not in seen:
            seen.add(word)
            out.append(word)
    return out


def build_items(emoji_entries, annotations: dict[str, list[str]]) -> list[dict]:
    # name -> emoji for tone-variant lookups
    fq_by_name: dict[str, str] = {}
    entries: list[tuple[str, str, str, str]] = []
    for group, subgroup, emoji, name in emoji_entries:
        if group not in GROUPS:
            continue
        entries.append((group, subgroup, emoji, name))
        fq_by_name.setdefault(name, emoji)

    # Tone-capable bases: names with fully-qualified variants ending in a tone label
    # (emoji-test uses both "waving hand: light skin tone" and
    # "couple with heart: man, man, light skin tone" naming styles).
    tone_map: set[str] = set()
    for _, _, _, name in entries:
        for t in TONE_LABELS:
            if name.endswith(": " + t) or name.endswith(", " + t):
                base = name[: -(len(t) + 2)]
                if base in fq_by_name:
                    tone_map.add(base)
                break

    # Standard tones: [base, light, medium-light, medium, medium-dark, dark].
    tones_by_base: dict[str, list[str]] = {}
    for base_name in tone_map:
        variants: list[str | None] = []
        for t in TONE_LABELS:
            v = fq_by_name.get(base_name + ": " + t)
            if v is None:
                v = fq_by_name.get(base_name + ", " + t)
            variants.append(v)
        if all(variants):
            tones_by_base[base_name] = [fq_by_name[base_name]] + variants

    items: list[dict] = []
    for group, subgroup, emoji, name in entries:
        if any(t in name for t in TONE_LABELS):
            continue  # skin-tone variant -> lives in the base item's tones
        item: dict = {
            "emoji": emoji,
            "group": group,
            "subgroup": subgroup,
            "name": name,
            "keywords": keywords_for(name, annotations.get(emoji)),
        }
        tones = tones_by_base.get(name)
        if tones:
            item["tones"] = tones
        items.append(item)
    return items


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--emoji-test", type=Path, default=None)
    ap.add_argument("--annotations", type=Path, default=None)
    args = ap.parse_args()

    repo = Path(__file__).resolve().parent.parent
    out = repo / "app" / "src" / "main" / "assets" / "emoji" / "catalog.json"

    tmp = Path(tempfile.gettempdir()) / "whispertype-emoji"
    tmp.mkdir(parents=True, exist_ok=True)
    emoji_test = args.emoji_test or download(EMOJI_TEST_URL, tmp / "emoji-test-15.1.txt")
    annotations = args.annotations or download(ANNOTATIONS_URL, tmp / "cldr-en-45.xml")

    entries = list(parse_emoji_test(emoji_test))
    ann = parse_annotations(annotations)
    items = build_items(entries, ann)

    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as f:
        f.write('{"groups": ')
        f.write(json.dumps(GROUPS, ensure_ascii=False))
        f.write(',\n"items": [')
        for i, item in enumerate(items):
            f.write(",\n" if i else "\n")
            f.write(json.dumps(item, ensure_ascii=False, separators=(",", ":")))
        f.write("\n]\n}\n")

    counts: dict[str, int] = {}
    for item in items:
        counts[item["group"]] = counts.get(item["group"], 0) + 1
    print(f"wrote {out} ({out.stat().st_size / 1024:.0f} KiB, {len(items)} items)")
    for g in GROUPS:
        print(f"  {g}: {counts.get(g, 0)}")
    tone_count = sum(1 for it in items if "tones" in it)
    print(f"  tone-capable items: {tone_count}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
