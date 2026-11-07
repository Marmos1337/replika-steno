"""A/B of overlap / interruption handling on top of the app pipeline (tools/mac_check.py mirror).
  python tools/overlap_ab.py <pyannote segmentation model.onnx> [variant,variant...]

Test set: testdata/{ru,en}_overlap{1,2}.m4a (tools/make_overlap_test.py: next speaker starts 0.5-1.5 s
before the previous one ends, plus short interjections) and the original dialogs as a no-regression check.
Every variant changes only diarization (and, for overlapping turns, which audio the recognizer gets);
models, clustering and recognition are the app's. Prints and writes artifacts/test-results/overlap_ab.json:
WER, speaker-time accuracy (tools/eval.py), DER with the same speaker mapping, speakers found and the
time of diarization + recognition on this Mac (RTF, models already loaded)."""
import importlib.util
import itertools
import json
import os
import sys
import time
from pathlib import Path

import numpy as np
import sherpa_onnx

ROOT = Path(__file__).resolve().parent.parent
T = ROOT / "testdata"
SR = 16000


def _mod(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "tools" / f"{name}.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


mc = _mod("mac_check")
ev = _mod("eval")
M = mc.M

CASES = [
    ("ru_overlap1.m4a", "ref_ru_overlap1.json", "overlap"),
    ("ru_overlap2.m4a", "ref_ru_overlap2.json", "overlap"),
    ("en_overlap1.m4a", "ref_en_overlap1.json", "overlap"),
    ("en_overlap2.m4a", "ref_en_overlap2.json", "overlap"),
    ("ru_dialog_2voices.m4a", "ref_ru_dialog_2voices.json", "original"),
    ("en_dialog_2voices.mp3", "ref_en_dialog_2voices.json", "original"),
    ("long_mixed_6min.m4a", "ref_long_mixed_6min.json", "original"),
]

THR = 0.6
JOIN_GAP = 600
MIN_MS = 250
INTERJECTION_MS = 1000  # an other-speaker turn shorter than this does not stop a same-speaker merge
SHORT_EMB_MS = 1000     # turns shorter than this are assigned to the nearest cluster, not clustered


class Models:
    def __init__(self, seg_model):
        self.ext = sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=f"{M}/diar/titanet_small.onnx", num_threads=4))
        self.asr = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(model=f"{M}/asr/model.int8.onnx", tokens=f"{M}/asr/tokens.txt", num_threads=4)
        self.asr_en = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(model=f"{M}/asr_en/model.int8.onnx", tokens=f"{M}/asr_en/tokens.txt", num_threads=4)
        self.seg_model = seg_model
        self._diar = {}

    def embed(self, x, a, b):
        st = self.ext.create_stream(); st.accept_waveform(SR, x[a:b]); st.input_finished()
        return np.array(self.ext.compute(st))

    def pyannote(self, threshold, min_off):
        key = (threshold, min_off)
        if key not in self._diar:
            cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
                segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
                    pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(model=self.seg_model), num_threads=4),
                embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=f"{M}/diar/titanet_small.onnx", num_threads=4),
                clustering=sherpa_onnx.FastClusteringConfig(threshold=threshold),
                min_duration_on=0.2, min_duration_off=min_off)
            self._diar[key] = sherpa_onnx.OfflineSpeakerDiarization(cfg)
        return self._diar[key]

    def recognize(self, x, a_ms, b_ms):
        seg = x[a_ms * SR // 1000:b_ms * SR // 1000]
        st = self.asr.create_stream(); st.accept_waveform(SR, seg); self.asr.decode_stream(st)
        t = st.result.text.strip()
        letters = [c for c in t if c.isalpha()]
        if letters and sum(c.isascii() for c in letters) / len(letters) > 0.5:
            st = self.asr_en.create_stream(); st.accept_waveform(SR, seg); self.asr_en.decode_stream(st); t = st.result.text.strip()
        return t


# ---------- turn post-processing ----------

def normalize_overlap(turns, dur):
    """Like Segments.normalize, but turns of different speakers may overlap. A turn joins the last turn of
    its own speaker when the pause is < JOIN_GAP and only short interjections of others started since."""
    out = []
    for a, b, c, f in sorted(((max(0, a), min(dur, b), c, f) for a, b, c, f in turns)):
        if b - a < MIN_MS:
            continue
        li = next((i for i in range(len(out) - 1, -1, -1) if out[i][2] == c), None)
        if li is not None and a - out[li][1] < JOIN_GAP and all(o[1] - o[0] < INTERJECTION_MS for o in out[li + 1:]):
            pa, pb, pc, pf = out[li]; out[li] = (pa, max(pb, b), c, min(pf, f)); continue
        if li is not None and a < out[li][1]:  # same speaker overlapping itself after an interruption: continue after it
            a = out[li][1]
            if b - a < MIN_MS:
                continue
        out.append((a, b, c, f))
    return sorted(out)


def asr_ranges(turns, mode):
    """Which audio the recognizer gets for each turn. full: the whole turn. own: a turn that starts while an
    earlier turn of another speaker is still going is recognized from that turn's end (when >= 1 s is left),
    so the shared stretch is transcribed once; interjections inside another turn keep their own range."""
    if mode == "full":
        return [(a, b) for a, b, _, _ in turns]
    res = []
    for i, (a, b, c, _) in enumerate(turns):
        start = a
        for pa, pb, pc, _ in turns[:i]:
            if pc != c and pa <= a < pb < b and b - pb >= 1000:
                start = max(start, pb)
        res.append((start, b))
    return res


# ---------- diarization variants ----------

def phrases_ms(ph):
    return [(a * 1000 // SR, b * 1000 // SR) for a, b in ph]


def cluster_turns(md, x, segs):
    """Embeds [a, b) ms ranges, clusters them like the app (avg linkage, cos 0.6). Returns (a, b, cluster, conf)."""
    if not segs:
        return []
    E = np.array([md.embed(x, a * SR // 1000, b * SR // 1000) for a, b in segs])
    lab, conf = mc.cluster(E, THR)
    return [(a, b, int(l), float(f)) for (a, b), l, f in zip(segs, lab, conf)]


def v_base(md, x, vad=(0.25, 12)):
    ph = phrases_ms(mc.vad_phrases(x, *vad))
    return mc.normalize(cluster_turns(md, x, ph), len(x) * 1000 // SR)


def v_uniform(md, x, win=1500, hop=750):
    """VAD phrases chopped into overlapping windows; each 10 ms frame takes the label of the windows covering it."""
    ph = phrases_ms(mc.vad_phrases(x))
    wins, owner = [], []
    for k, (a, b) in enumerate(ph):
        if b - a <= win * 1.5:
            wins.append((a, b)); owner.append(k); continue
        s = a
        while s + win < b:
            wins.append((s, s + win)); owner.append(k); s += hop
        wins.append((max(a, b - win), b)); owner.append(k)
    turns = cluster_turns(md, x, wins)
    out = []
    for k, (a, b) in enumerate(ph):
        mine = [t for t, o in zip(turns, owner) if o == k]
        frames = []
        for f in range(a, b, 10):
            votes = {}
            for ta, tb, c, conf in mine:
                if ta <= f < tb:
                    votes[c] = votes.get(c, 0) + 1 - abs((f - (ta + tb) / 2) / (tb - ta))
            frames.append(max(votes, key=votes.get) if votes else mine[0][2])
        s = a
        for i in range(1, len(frames) + 1):
            if i == len(frames) or frames[i] != frames[i - 1]:
                e = a + i * 10 if i < len(frames) else b
                confs = [t[3] for t in mine if t[2] == frames[i - 1]]
                out.append((s, e, frames[i - 1], min(confs)))
                s = e
    return mc.normalize(out, len(x) * 1000 // SR)


def pyannote_segments(md, x, threshold=0.6, min_off=0.3):
    r = md.pyannote(threshold, min_off).process(x).sort_by_start_time()
    return [(int(s.start * 1000), int(s.end * 1000), s.speaker) for s in r]


def v_pyannote(md, x, threshold=0.6):
    return normalize_overlap([(a, b, c, 1.0) for a, b, c in pyannote_segments(md, x, threshold)], len(x) * 1000 // SR)


def assign_short(md, x, turns_short, labelled):
    """Short turns are too short for a reliable voice print to form a cluster; each goes to the nearest centroid."""
    if not labelled:
        return []
    cents = {}
    for (a, b, c, _), e in labelled:
        cents.setdefault(c, []).append(e / np.linalg.norm(e))
    C = {c: np.mean(v, axis=0) / np.linalg.norm(np.mean(v, axis=0)) for c, v in cents.items()}
    out = []
    for a, b, e in turns_short:
        e = e / np.linalg.norm(e)
        c, sim = max(((c, float(e @ v)) for c, v in C.items()), key=lambda p: p[1])
        out.append((a, b, c, sim))
    return out


def v_hybrid(md, x, threshold=0.6, short_assign=True):
    """pyannote segmentation only says WHERE turns change and where two people talk at once; boundaries are the
    union of its segments and our VAD phrases, and WHO speaks is decided by TitaNet + the app's clustering."""
    dur = len(x) * 1000 // SR
    ph = phrases_ms(mc.vad_phrases(x))
    pseg = pyannote_segments(md, x, threshold)
    cand = []
    for a, b, _ in pseg:
        for pa, pb in ph:
            s, e = max(a, pa), min(b, pb)
            if e - s >= MIN_MS:
                cand.append((s, e))
    # VAD speech that pyannote missed keeps its phrase
    for pa, pb in ph:
        covered = sum(max(0, min(pb, e) - max(pa, s)) for s, e in cand)
        if covered < 0.5 * (pb - pa):
            cand.append((pa, pb))
    cand = sorted(set(cand))
    # voice print from the part of the turn nobody else covers, when there is enough of it
    items = []
    for i, (a, b) in enumerate(cand):
        others = [(s, e) for j, (s, e) in enumerate(cand) if j != i and s < b and e > a]
        clean = [(a, b)]
        for s, e in others:
            nxt = []
            for ca, cb in clean:
                if e <= ca or s >= cb:
                    nxt.append((ca, cb)); continue
                if s > ca: nxt.append((ca, s))
                if e < cb: nxt.append((e, cb))
            clean = nxt
        ca, cb = max(clean, key=lambda r: r[1] - r[0]) if clean else (a, b)
        ea, eb = (ca, cb) if cb - ca >= 500 else (a, b)
        items.append((a, b, md.embed(x, ea * SR // 1000, eb * SR // 1000)))
    long_items = [it for it in items if it[1] - it[0] >= SHORT_EMB_MS] if short_assign else items
    if not long_items:
        long_items = items
    short_items = [it for it in items if it not in long_items]
    E = np.array([e for _, _, e in long_items])
    lab, conf = mc.cluster(E, THR)
    labelled = [((a, b, int(l), float(f)), e) for (a, b, e), l, f in zip(long_items, lab, conf)]
    turns = [t for t, _ in labelled] + assign_short(md, x, short_items, labelled)
    return normalize_overlap(turns, dur)


def subtract(rng, cuts):
    """[a, b) minus every range in cuts, as a list of ranges."""
    out = [rng]
    for s, e in cuts:
        nxt = []
        for a, b in out:
            if e <= a or s >= b:
                nxt.append((a, b)); continue
            if s > a: nxt.append((a, s))
            if e < b: nxt.append((e, b))
        out = nxt
    return out


def v_overlay(md, x, threshold=0.6, min_piece=300, clean_min=500, overlap=True, interjections=False):
    """The app's VAD phrases stay as they are; pyannote segmentation is consulted only inside phrases where it
    sees two or more speakers. Such a phrase becomes one piece per pyannote speaker (pieces may overlap).
    A piece's voice print comes from its clean stretch (nobody else talking per pyannote). Pieces with at
    least clean_min of clean speech join the app's clustering together with the untouched phrases, so a
    split survives only when the voice prints really differ (same speaker -> same cluster -> merged back).
    Pieces without enough clean speech cannot be checked: they go back to the speaker of the piece they
    overlap most (or the nearest one), unless interjections=True, which trusts pyannote's label for them."""
    dur = len(x) * 1000 // SR
    ph = phrases_ms(mc.vad_phrases(x))
    G = pyannote_segments(md, x, threshold)
    pieces = []  # (a, b, pyannote label or None, clean range or None, phrase index)
    for k, (pa, pb) in enumerate(ph):
        inter = [(max(a, pa), min(b, pb), l) for a, b, l in G if min(b, pb) - max(a, pa) >= min_piece]
        labels = sorted({l for _, _, l in inter})
        if len(labels) < 2:
            pieces.append((pa, pb, labels[0] if labels else None, (pa, pb), k))
            continue
        first = min(a for a, _, _ in inter); last = max(b for _, b, _ in inter)
        for a, b, l in inter:
            a = pa if a == first else a  # keep the VAD padding at the phrase edges
            b = pb if b == last else b
            others = [(s, e) for s, e, l2 in G if l2 != l]
            clean = [r for r in subtract((a, b), others) if r[1] - r[0] >= clean_min]
            pieces.append((a, b, l, max(clean, key=lambda r: r[1] - r[0]) if clean else None, k))
    core = [p for p in pieces if p[3] is not None]
    rest = [p for p in pieces if p[3] is None]
    E = [md.embed(x, c[0] * SR // 1000, c[1] * SR // 1000) for _, _, _, c, _ in core]
    lab, conf = mc.cluster(np.array(E), THR)
    turns = [(a, b, int(c), float(f)) for (a, b, _, _, _), c, f in zip(core, lab, conf)]
    votes = {}
    for (a, b, l, _, _), c in zip(core, lab):
        if l is not None:
            votes.setdefault(l, {}).setdefault(int(c), 0); votes[l][int(c)] += b - a
    label_cluster = {l: max(v, key=v.get) for l, v in votes.items()}
    for a, b, l, _, k in rest:
        same = [(t, p) for t, p in zip(turns, core) if p[4] == k]
        host = max(same, key=lambda tp: (min(b, tp[0][1]) - max(a, tp[0][0]), -abs(a - tp[0][0])))[0] if same else None
        if interjections and l in label_cluster and (host is None or label_cluster[l] != host[2]):
            turns.append((a, b, label_cluster[l], 0.0))
        elif host is not None:
            turns.append((a, b, host[2], host[3]))
    return normalize_overlap(turns, dur) if overlap else mc.normalize(turns, dur)


VARIANTS = {
    "base": (lambda md, x: v_base(md, x), "full"),
    "vad-sil0.1-max4": (lambda md, x: v_base(md, x, (0.1, 4)), "full"),
    "uniform-1.5s": (lambda md, x: v_uniform(md, x), "full"),
    "pyannote-only": (lambda md, x: v_pyannote(md, x), "full"),
    "pyannote-first": (lambda md, x: v_hybrid(md, x), "own"),
    "overlay-trim": (lambda md, x: v_overlay(md, x, overlap=False), "full"),
    "overlay-full": (lambda md, x: v_overlay(md, x), "full"),
    "overlay-own": (lambda md, x: v_overlay(md, x), "own"),
    "overlay-own+interj": (lambda md, x: v_overlay(md, x, interjections=True), "own"),
}


# ---------- scoring ----------

def der(ref, hyp):
    """Frame-level (10 ms) diarization error: (missed + false alarm + confused speaker time) / reference speaker
    time, with the same best one-to-one mapping as eval.speaker_acc. Overlap counts per speaker."""
    end = max([u["end_ms"] for u in ref + hyp] + [0]) // 10 + 1
    rs = sorted({u["speaker_id"] for u in ref}); hs = sorted({u["speaker_id"] for u in hyp})
    def act(us, ids):
        A = np.zeros((len(ids), end), bool)
        for u in us:
            A[ids.index(u["speaker_id"]), u["start_ms"] // 10:u["end_ms"] // 10] = True
        return A
    R, H = act(ref, rs), act(hyp, hs)
    best, perm_best = -1, ()
    for perm in itertools.permutations(range(len(hs)), min(len(rs), len(hs))):
        s = sum(int((R[i] & H[j]).sum()) for i, j in enumerate(perm))
        if s > best:
            best, perm_best = s, perm
    nr, nh = R.sum(0), H.sum(0)
    correct = np.zeros(end, int)
    for i, j in enumerate(perm_best):
        correct += R[i] & H[j]
    miss = np.maximum(0, nr - nh).sum(); fa = np.maximum(0, nh - nr).sum(); conf = (np.minimum(nr, nh) - correct).sum()
    return float((miss + fa + conf) / max(1, nr.sum()))


def run_case(md, x, variant):
    diar, mode = VARIANTS[variant]
    t0 = time.time()
    turns = diar(md, x)
    t1 = time.time()
    ids, res = {}, []
    for (a, b, c, f), (ra, rb) in zip(turns, asr_ranges(turns, mode)):
        t = md.recognize(x, ra, rb)
        if t:
            res.append({"speaker_id": ids.setdefault(c, f"speaker_{len(ids) + 1}"), "start_ms": int(a), "end_ms": int(b), "text": t})
    return res, t1 - t0, time.time() - t1


def main():
    md = Models(sys.argv[1])
    names = sys.argv[2].split(",") if len(sys.argv) > 2 else list(VARIANTS)
    only = os.environ.get("CASES")
    cases = [c for c in CASES if not only or any(o in c[0] for o in only.split(","))]
    out_path = ROOT / "artifacts/test-results/overlap_ab.json"
    rows = json.loads(out_path.read_text())["rows"] if out_path.exists() and os.environ.get("APPEND") else []
    hyp_dir = ROOT / "artifacts/test-results/exports/overlap_ab"
    hyp_dir.mkdir(parents=True, exist_ok=True)
    for audio, ref_name, group in cases:
        x = mc.load(str(T / audio)); dur = len(x) * 1000 // SR
        ref = json.loads((T / ref_name).read_text())["transcript"]
        for v in names:
            hyp, td, ta = run_case(md, x, v)
            errs = ev.check({"transcript": hyp}, dur)
            w = ev.wer(sum((ev.words(u["text"]) for u in ref), []), sum((ev.words(u["text"]) for u in hyp), []))
            acc, _, nh = ev.speaker_acc(ref, hyp) if hyp else (0.0, 0, 0)
            row = {"file": audio, "group": group, "variant": v, "WER": round(w, 4), "speaker_time_accuracy": round(acc, 4),
                   "DER": round(der(ref, hyp), 4), "speakers_ref": len({u["speaker_id"] for u in ref}), "speakers_hyp": nh,
                   "utterances": len(hyp), "schema_ok": not errs, "diar_s": round(td, 2), "asr_s": round(ta, 2),
                   "RTF": round((td + ta) / (dur / 1000), 3)}
            rows = [r for r in rows if not (r["file"] == audio and r["variant"] == v)] + [row]
            (hyp_dir / f"{Path(audio).stem}__{v}.json").write_text(json.dumps({"transcript": hyp}, ensure_ascii=False, indent=1))
            print(f"{audio:24} {v:16} WER {w*100:5.1f}%  spk {acc*100:5.1f}%  DER {row['DER']*100:5.1f}%  n={nh}  utts={len(hyp):3}  RTF {row['RTF']:.3f}", flush=True)
    summary = {}
    for v in dict.fromkeys(r["variant"] for r in rows):
        for g in ("overlap", "original"):
            rr = [r for r in rows if r["variant"] == v and r["group"] == g]
            if rr:
                summary.setdefault(v, {})[g] = {k: round(float(np.mean([r[k] for r in rr])), 4) for k in ("WER", "speaker_time_accuracy", "DER", "RTF")}
    out_path.write_text(json.dumps({"note": "tools/overlap_ab.py; Mac M5, sherpa-onnx 2.0.1 Python; RTF excludes model loading",
                                    "summary": summary, "rows": rows}, ensure_ascii=False, indent=1))
    for v, s in summary.items():
        print(f"{v:16} " + "  ".join(f"{g}: WER {d['WER']*100:5.1f}% spk {d['speaker_time_accuracy']*100:5.1f}% DER {d['DER']*100:5.1f}% RTF {d['RTF']:.3f}" for g, d in s.items()))


if __name__ == "__main__":
    main()
