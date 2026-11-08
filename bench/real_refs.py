#!/usr/bin/env python3
"""Reference decoders for bench/real_speech.sh (whole clip, no VAD).

  python3 bench/real_refs.py canary <src> <tgt> <clips_dir> <out.jsonl>
  python3 bench/real_refs.py whispercpp <lang|auto> <clips_dir> <out.jsonl>

WHISPERTYPE_GGML selects the whisper.cpp ggml model (ggml-small.bin by name).
Deps: pywhispercpp soundfile (whispercpp) or sherpa-onnx soundfile (canary).
"""
import json
import os
import sys
import time
from pathlib import Path

import soundfile as sf

CACHE = Path(os.environ.get("WHISPERTYPE_HOST_CACHE", Path.home() / ".cache/whispertype-host"))


def decode(kind, args):
    global dec
    if kind == "whispercpp":
        from pywhispercpp.model import Model
        lang = "" if args[0] == "auto" else args[0]
        ggml = Path(os.environ.get("WHISPERTYPE_GGML", CACHE / "ggml-small.bin"))
        model = Model(str(ggml), n_threads=8, language=lang, print_progress=False)
        return lambda x: " ".join(s.text.strip() for s in model.transcribe(x))
    if kind == "canary":
        import sherpa_onnx
        src, tgt = args[0], args[1]
        c = CACHE / "models/canary-180m-flash"
        # Transcription, never translation: same language in and out.
        rec = sherpa_onnx.OfflineRecognizer.from_nemo_canary(
            encoder=str(c / "encoder.int8.onnx"), decoder=str(c / "decoder.int8.onnx"),
            tokens=str(c / "tokens.txt"), src_lang=src, tgt_lang=tgt, num_threads=4)
        def dec(x):
            s = rec.create_stream(); s.accept_waveform(16000, x); rec.decode_stream(s)
            return s.result.text.strip()
        return dec
    raise SystemExit(kind)


def main():
    kind, *args = sys.argv[1:]
    clips, out = args[-2], args[-1]
    dec = decode(kind, args)
    with open(out, "w", encoding="utf-8") as f:
        for w in sorted(Path(clips).glob("*.wav")):
            x, sr = sf.read(w, dtype="float32")
            if sr != 16000:
                raise SystemExit(f"{w.name}: expected 16 kHz")
            t = time.time()
            text = dec(x)
            f.write(json.dumps({"file": w.name, "text": text, "ms": int((time.time() - t) * 1000)},
                               ensure_ascii=False) + "\n")
            f.flush()
            print(w.name, text[:80], flush=True)


if __name__ == "__main__":
    main()
