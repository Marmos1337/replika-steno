"""Scores a live take: the preliminary live text and the final transcript, both against a reference.
  python3 tools/live_eval.py <recording.json pulled from the app> <ref.json>
recording.json is files/recordings/<id>/recording.json (adb shell run-as ru.replika.steno cat ...).
Prints WER of the live text (what the user saw while talking) and of the final transcript, boundary
repeats between neighbouring live lines (duplicates at fragment borders) and the stored live metrics."""
import json, subprocess, sys, tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))


def words(s):
    import re
    return [w for w in re.split(r"[^\w]+", s.lower().replace("ё", "е")) if w]


def as_doc(lines):
    return {"transcript": lines}


def evaluate(doc, ref_path, duration):
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(doc, f, ensure_ascii=False)
    out = subprocess.check_output([sys.executable, str(HERE / "eval.py"), f.name, ref_path, "--duration-ms", str(duration)])
    return json.loads(out)


def boundary_repeats(lines, n=2):
    """Neighbouring lines where the last n words of one equal the first n words of the next."""
    hits = []
    for a, b in zip(lines, lines[1:]):
        wa, wb = words(a["text"]), words(b["text"])
        if len(wa) >= n and len(wb) >= n and wa[-n:] == wb[:n]:
            hits.append((a["text"][-40:], b["text"][:40]))
    return hits


rec = json.load(open(sys.argv[1]))
ref = sys.argv[2]
dur = rec["durationMs"]
draft = rec.get("liveDraft") or []
live = [{"speaker_id": f"speaker_{l.get('speaker') or 0}", "start_ms": l["startMs"], "end_ms": max(l["endMs"], l["startMs"] + 1), "text": l["text"]} for l in draft]
final = [{"speaker_id": u["speakerId"], "start_ms": u["startMs"], "end_ms": u["endMs"], "text": u["rawText"]} for u in rec["utterances"]]
print(json.dumps({
    "duration_ms": dur,
    "live": evaluate(as_doc(live), ref, dur),
    "live_boundary_repeats": boundary_repeats(draft),
    "final": evaluate(as_doc(final), ref, dur),
    "live_metrics": rec.get("liveMetrics"),
}, ensure_ascii=False, indent=1))
