"""Scores an exported transcript against a reference in the same JSON schema.
  python3 tools/eval.py <hyp.json> <ref.json> [--duration-ms N]
Prints: schema check, WER (lowercase, no punctuation, ё=е), and speaker accuracy = share of reference
speech time whose hypothesis speaker matches after the best one-to-one speaker mapping."""
import itertools, json, re, sys

def words(s):
    return [w for w in re.split(r"[^\w]+", s.lower().replace("ё", "е")) if w]

def wer(ref, hyp):
    d = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        prev, d[0] = d[0], i
        for j, h in enumerate(hyp, 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r != h))
            prev, d[j] = d[j], cur
    return d[len(hyp)] / max(1, len(ref))

def check(doc, dur):
    errs = []
    if set(doc) != {"transcript"}: errs.append("top-level keys")
    for i, u in enumerate(doc["transcript"]):
        if set(u) != {"speaker_id", "start_ms", "end_ms", "text"}: errs.append(f"[{i}] keys {sorted(u)}")
        if not all(isinstance(u[k], int) and not isinstance(u[k], bool) for k in ("start_ms", "end_ms")): errs.append(f"[{i}] times not int")
        if not (0 <= u["start_ms"] < u["end_ms"]): errs.append(f"[{i}] bad range")
        if dur and u["end_ms"] > dur: errs.append(f"[{i}] past duration")
        if not isinstance(u["text"], str) or not re.fullmatch(r"speaker_\d+", u["speaker_id"]): errs.append(f"[{i}] types")
    return errs

def speaker_acc(ref, hyp):
    rs = sorted({u["speaker_id"] for u in ref}); hs = sorted({u["speaker_id"] for u in hyp})
    def overlap(a, b): return max(0, min(a["end_ms"], b["end_ms"]) - max(a["start_ms"], b["start_ms"]))
    M = {(r, h): sum(overlap(a, b) for a in ref if a["speaker_id"] == r for b in hyp if b["speaker_id"] == h) for r in rs for h in hs}
    total = sum(u["end_ms"] - u["start_ms"] for u in ref)
    best = 0
    for perm in itertools.permutations(hs, min(len(rs), len(hs))):
        best = max(best, sum(M[(r, h)] for r, h in zip(rs, perm)))
    return best / total, len(rs), len(hs)

hyp = json.load(open(sys.argv[1])); ref = json.load(open(sys.argv[2]))
dur = int(sys.argv[sys.argv.index("--duration-ms") + 1]) if "--duration-ms" in sys.argv else None
errs = check(hyp, dur)
H, Rf = hyp["transcript"], ref["transcript"]
w = wer(sum((words(u["text"]) for u in Rf), []), sum((words(u["text"]) for u in H), []))
acc, nr, nh = speaker_acc(Rf, H)
print(json.dumps({"schema_ok": not errs, "schema_errors": errs[:5], "utterances": len(H), "WER": round(w, 4),
                  "speaker_time_accuracy": round(acc, 4), "speakers_ref": nr, "speakers_hyp": nh}, ensure_ascii=False))
