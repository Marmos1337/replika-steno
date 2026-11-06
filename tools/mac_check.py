"""Reference run of the app pipeline on a Mac: same models, same settings, same clustering and merge
rules as the Kotlin code (VadDiarizer + Clustering + Segments.normalize + GigaAM per turn).
Usage: python mac_check.py <audio> [threshold]   (needs: pip install sherpa-onnx numpy; ffmpeg)"""
import json, subprocess, sys, time
import numpy as np
import sherpa_onnx

M = __file__.rsplit("/tools/", 1)[0] + "/app/src/main/assets/models"
SR = 16000

def load(p):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", p, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"], check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()

def vad_phrases(x):
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = f"{M}/diar/silero_vad.onnx"
    cfg.silero_vad.threshold = 0.5
    cfg.silero_vad.min_silence_duration = 0.25
    cfg.silero_vad.min_speech_duration = 0.25
    cfg.silero_vad.max_speech_duration = 12
    cfg.sample_rate = SR
    vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=max(60, len(x) // SR + 10))
    out = []
    def drain():
        while not vad.empty():
            s = vad.front; out.append((s.start, s.start + len(s.samples))); vad.pop()
    for i in range(0, len(x), 512):
        vad.accept_waveform(x[i:i + 512]); drain()
    vad.flush(); drain()
    return [(a, min(b, len(x))) for a, b in out if b - a >= SR // 4]

def cluster(E, thr):
    E = E / np.linalg.norm(E, axis=1, keepdims=True)
    n = len(E); D = 1 - E @ E.T; size = np.ones(n); alive = np.ones(n, bool); owner = np.arange(n)
    np.fill_diagonal(D, np.inf)
    while True:
        Dm = np.where(alive[:, None] & alive[None, :], D, np.inf)
        i, j = np.unravel_index(np.argmin(Dm), Dm.shape)
        if not np.isfinite(Dm[i, j]) or Dm[i, j] > thr: break
        i, j = min(i, j), max(i, j)
        new = (D[i] * size[i] + D[j] * size[j]) / (size[i] + size[j])
        D[i, :] = new; D[:, i] = new; D[i, i] = np.inf
        size[i] += size[j]; alive[j] = False; owner[owner == j] = i
    mp = {}; lab = np.array([mp.setdefault(o, len(mp)) for o in owner])
    C = np.zeros((len(mp), E.shape[1]))
    for p in range(n): C[lab[p]] += E[p]
    C /= np.linalg.norm(C, axis=1, keepdims=True)
    return lab, np.array([E[p] @ C[lab[p]] for p in range(n)])

def normalize(turns, dur, min_ms=250, gap=600):
    out = []
    for a, b, c, conf in sorted(((max(0, a), min(dur, b), c, f) for a, b, c, f in turns)):
        if b - a < min_ms: continue
        if out and out[-1][2] == c and a - out[-1][1] < gap:
            pa, pb, pc, pf = out[-1]; out[-1] = (pa, max(pb, b), c, min(pf, conf)); continue
        if out and a < out[-1][1]:
            a = out[-1][1]
            if b - a < min_ms: continue
        out.append((a, b, c, conf))
    return out

def main():
    path = sys.argv[1]; thr = float(sys.argv[2]) if len(sys.argv) > 2 else 0.6
    x = load(path); dur = len(x) * 1000 // SR
    t0 = time.time()
    ph = vad_phrases(x)
    ext = sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=f"{M}/diar/titanet_small.onnx", num_threads=4))
    E = []
    for a, b in ph:
        st = ext.create_stream(); st.accept_waveform(SR, x[a:b]); st.input_finished(); E.append(np.array(ext.compute(st)))
    turns = []
    if ph:
        lab, conf = cluster(np.array(E), thr)
        turns = normalize([(a * 1000 // SR, b * 1000 // SR, int(l), float(f)) for (a, b), l, f in zip(ph, lab, conf)], dur)
    t1 = time.time()
    asr = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(model=f"{M}/asr/model.int8.onnx", tokens=f"{M}/asr/tokens.txt", num_threads=4)
    ids = {}; res = []
    for a, b, c, f in turns:
        st = asr.create_stream(); st.accept_waveform(SR, x[a * SR // 1000:b * SR // 1000]); asr.decode_stream(st)
        t = st.result.text.strip()
        if t: res.append({"speaker_id": ids.setdefault(c, f"speaker_{len(ids) + 1}"), "start_ms": a, "end_ms": b, "text": t, "_conf": round(f, 3)})
    t2 = time.time()
    print(f"# audio {dur/1000:.1f}s  diar {t1-t0:.2f}s  asr {t2-t1:.2f}s  speakers {len(ids)}  utterances {len(res)}  phrases {len(ph)}")
    for r in res: print(f"  {r['speaker_id']} {r['start_ms']:>6}-{r['end_ms']:>6} c={r['_conf']:.2f}  {r['text']}")

main()
