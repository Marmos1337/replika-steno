"""Builds test dialogs with overlapping speech from the per-turn parts of the labelled test files.

  python tools/make_overlap_test.py

For ru_dialog_2voices and en_dialog_2voices (turns in testdata/parts, references in ref_*.json):
  - every next turn starts 0.5-1.5 s BEFORE the previous one ends (no pause between speakers);
  - short interjections of the other speaker (0.3-0.8 s: the first word of another turn of that voice,
    e.g. «Хорошо.», «Отлично.», «Okay,», «Sure,») are mixed over the middle of long turns.
Two seeds per language give testdata/{ru,en}_overlap{1,2}.m4a and ref_{ru,en}_overlap{1,2}.json.
References keep every turn whole, so turns overlap in time; that is what the speaker-time metric
then expects from a diarizer that handles simultaneous speech.
"""
import json
import subprocess
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
T = ROOT / "testdata"
P = T / "parts"
SR = 16000
CASES = {
    "ru": ("ref_ru_dialog_2voices.json", [f"{i:02d}.wav" for i in range(1, 9)]),
    "en": ("ref_en_dialog_2voices.json", [f"e{i}.wav" for i in range(1, 5)]),
}
# interjections per language: (part file, text of its first word group)
INTERJECTIONS = {
    "ru": [("03.wav", "Хорошо."), ("07.wav", "Отлично."), ("02.wav", "Добрый день."), ("04.wav", "Думаю,")],
    "en": [("e3.wav", "Okay,"), ("e2.wav", "Almost."), ("e4.wav", "Sure,")],
}
LONG_TURN_S = {"ru": 5.0, "en": 3.0}


def load(p: Path) -> np.ndarray:
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(p), "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"], check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


def first_word(x: np.ndarray) -> np.ndarray | None:
    """Speech from the first onset up to the first pause of >= 150 ms (20 ms frames, -35 dB of peak)."""
    n = SR // 50
    rms = np.sqrt(np.mean(x[: len(x) // n * n].reshape(-1, n) ** 2, axis=1))
    on = rms > rms.max() * 10 ** (-35 / 20)
    start = int(np.argmax(on))
    quiet = 0
    for i in range(start, len(on)):
        quiet = quiet + 1 if not on[i] else 0
        if quiet >= 8:
            end = i - quiet + 1
            seg = x[start * n:end * n + n]
            return seg if 0.3 * SR <= len(seg) <= 0.8 * SR else None
    return None


def build(lang: str, seed: int) -> None:
    rng = np.random.default_rng(seed)
    ref_name, parts = CASES[lang]
    turns = json.loads((T / ref_name).read_text())["transcript"]
    audio = [load(P / p) for p in parts]
    speaker_of_part = {p: t["speaker_id"] for p, t in zip(parts, turns)}
    bits = []
    for part, text in INTERJECTIONS[lang]:
        seg = first_word(load(P / part))
        if seg is not None:
            bits.append((speaker_of_part[part], seg, text))
    placed = []  # (start sample, signal, speaker, text)
    t = 0
    for k, (u, x) in enumerate(zip(turns, audio)):
        if k > 0:
            t = placed_end - int(rng.uniform(0.5, 1.5) * SR)
        placed.append((t, x, u["speaker_id"], u["text"]))
        placed_end = t + len(x)
    # interjections of the other voice over the middle of long turns (every other long turn, by seed)
    longs = [p for p in placed if len(p[1]) >= LONG_TURN_S[lang] * SR]
    for i, (s, x, spk, _) in enumerate(longs):
        if (i + seed) % 2 and len(longs) > 1:
            continue
        cand = [b for b in bits if b[0] != spk]
        if not cand:
            continue
        bspk, seg, text = cand[int(rng.integers(len(cand)))]
        at = s + int(len(x) * rng.uniform(0.4, 0.6)) - len(seg) // 2
        placed.append((at, seg, bspk, text))
    total = max(s + len(x) for s, x, _, _ in placed)
    mix = np.zeros(total, dtype=np.float32)
    for s, x, _, _ in placed:
        mix[s:s + len(x)] += x
    peak = float(np.max(np.abs(mix)))
    if peak > 0.95:
        mix *= 0.95 / peak
    name = f"{lang}_overlap{seed}"
    out = T / f"{name}.m4a"
    pcm = (np.clip(mix, -1, 1) * 32767).astype("<i2").tobytes()
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "s16le", "-ar", str(SR), "-ac", "1", "-i", "-", "-c:a", "aac", "-b:a", "64k", str(out)], input=pcm, check=True)
    ref = [{"speaker_id": spk, "start_ms": s * 1000 // SR, "end_ms": (s + len(x)) * 1000 // SR, "text": text}
           for s, x, spk, text in sorted(placed, key=lambda p: p[0])]
    (T / f"ref_{name}.json").write_text(json.dumps({"transcript": ref}, ensure_ascii=False, indent=1))
    ov = sum(max(0, min(a["end_ms"], b["end_ms"]) - max(a["start_ms"], b["start_ms"])) for i, a in enumerate(ref) for b in ref[i + 1:])
    print(f"{out.name}: {total / SR:.1f} s, {len(ref)} turns ({len(ref) - len(turns)} interjections), overlapped speech {ov / 1000:.1f} s")


if __name__ == "__main__":
    for lang in CASES:
        for seed in (1, 2):
            build(lang, seed)
