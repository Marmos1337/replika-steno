"""Reference run of the app pipeline on a Mac: same models, same settings, same clustering and merge
rules as the Kotlin code (VadDiarizer + Clustering + Segments.normalize + GigaAM per turn).
Usage: python mac_check.py <audio> [threshold]   (needs: pip install sherpa-onnx numpy; ffmpeg)"""
import json, subprocess, sys, time
import numpy as np
import sherpa_onnx

M = __file__.rsplit("/tools/", 1)[0] + "/src/mobile/android/app/src/main/assets/models"
SR = 16000
PAD_MS = int(__import__("os").environ.get("PAD_MS", "400"))

def load(p):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", p, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"], check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()

def vad_phrases(x, min_silence=0.25, max_speech=12):
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = f"{M}/diar/silero_vad.onnx"
    cfg.silero_vad.threshold = 0.5
    cfg.silero_vad.min_silence_duration = min_silence
    cfg.silero_vad.min_speech_duration = 0.25
    cfg.silero_vad.max_speech_duration = max_speech
    cfg.sample_rate = SR
    vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=max(60, len(x) // SR + 10))
    out = []
    def drain():
        while not vad.empty():
            s = vad.front; out.append((s.start, s.start + len(s.samples))); vad.pop()
    for i in range(0, len(x), 512):
        vad.accept_waveform(x[i:i + 512]); drain()
    vad.flush(); drain()
    out = [(a, min(b, len(x))) for a, b in out if b - a >= SR // 4]
    pad = int(PAD_MS * SR / 1000)
    res = []
    for i, (a, b) in enumerate(out):
        prev_end = out[i - 1][1] if i > 0 else 0
        next_start = out[i + 1][0] if i + 1 < len(out) else len(x)
        res.append((max(0, a - min(pad, max(0, a - prev_end) // 2)), min(len(x), b + min(pad, max(0, next_start - b) // 2))))
    return res

def diar_norm(x):
    """1st-order high-pass at 80 Hz + gain so the loudest 10 % of 30 ms frames sit at -20 dBFS RMS, peak-limited."""
    a = np.exp(-2 * np.pi * 80 / SR)
    from itertools import accumulate
    y = np.empty_like(x); px = py = 0.0
    for i, v in enumerate(x):
        py = a * (py + v - px); px = v; y[i] = py
    n = 480; frames = y[: len(y) // n * n].reshape(-1, n)
    if len(frames) == 0: return y
    rms = np.sqrt(np.mean(frames ** 2, axis=1))
    ref = np.mean(np.sort(rms)[-max(1, len(rms) // 10):]) + 1e-9
    y = y * min(10 ** (-20 / 20) / ref, 100.0)
    peak = np.max(np.abs(y))
    return (y * (0.95 / peak) if peak > 0.95 else y).astype(np.float32)

def quiet_gain(x, quiet_db=-28.0, target_db=-20.0, max_gain_db=30.0):
    """Mirror of VadDiarizer.quietGain: only a quiet recording (loudest 10 % of 30 ms frames below
    quiet_db) is raised towards target_db, for speech detection and voice embeddings only."""
    n = 480; frames = x[: len(x) // n * n].reshape(-1, n)
    if len(frames) == 0: return x
    rms = np.sqrt(np.mean(frames.astype(np.float64) ** 2, axis=1))
    ref_db = 20 * np.log10(np.mean(np.sort(rms)[-max(1, len(rms) // 10):]) + 1e-9)
    if ref_db >= quiet_db: return x
    g = 10 ** (min(target_db - ref_db, max_gain_db) / 20)
    return np.clip(x * g, -1, 1).astype(np.float32)

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
    path = sys.argv[1]; thr = float(sys.argv[2]) if len(sys.argv) > 2 and not sys.argv[2].startswith("--") else 0.6
    x = load(path); dur = len(x) * 1000 // SR
    t0 = time.time()
    # DIAR_NORM=1: speech detection and voice embeddings see a high-passed, loudness-normalised copy;
    # the recognizer keeps the original audio (mirrors DiarAudio in the app)
    mode = __import__("os").environ.get("DIAR_NORM")
    xd = diar_norm(x) if mode == "1" else quiet_gain(x) if mode == "auto" else x
    ph = vad_phrases(xd)
    ext = sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=f"{M}/diar/titanet_small.onnx", num_threads=4))
    E = []
    for a, b in ph:
        st = ext.create_stream(); st.accept_waveform(SR, xd[a:b]); st.input_finished(); E.append(np.array(ext.compute(st)))
    turns = []
    if ph:
        lab, conf = cluster(np.array(E), thr)
        turns = normalize([(a * 1000 // SR, b * 1000 // SR, int(l), float(f)) for (a, b), l, f in zip(ph, lab, conf)], dur)
    t1 = time.time()
    asr = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(model=f"{M}/asr/model.int8.onnx", tokens=f"{M}/asr/tokens.txt", num_threads=4)
    asr_en = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(model=f"{M}/asr_en/model.int8.onnx", tokens=f"{M}/asr_en/tokens.txt", num_threads=4)
    def latin(s):
        l = [c for c in s if c.isalpha()]
        return sum(c.isascii() for c in l) / len(l) if l else 0
    ids = {}; res = []
    for a, b, c, f in turns:
        st = asr.create_stream(); st.accept_waveform(SR, x[a * SR // 1000:b * SR // 1000]); asr.decode_stream(st)
        t = st.result.text.strip()
        if latin(t) > 0.5:
            st = asr_en.create_stream(); st.accept_waveform(SR, x[a * SR // 1000:b * SR // 1000]); asr_en.decode_stream(st); t = st.result.text.strip()
        if t: res.append({"speaker_id": ids.setdefault(c, f"speaker_{len(ids) + 1}"), "start_ms": a, "end_ms": b, "text": t, "_conf": round(f, 3)})
    t2 = time.time()
    print(f"# audio {dur/1000:.1f}s  diar {t1-t0:.2f}s  asr {t2-t1:.2f}s  speakers {len(ids)}  utterances {len(res)}  phrases {len(ph)}")
    for r in res: print(f"  {r['speaker_id']} {r['start_ms']:>6}-{r['end_ms']:>6} c={r['_conf']:.2f}  {r['text']}")
    if "--out" in sys.argv:
        json.dump({"transcript": [{k: v for k, v in r.items() if not k.startswith("_")} for r in res]},
                  open(sys.argv[sys.argv.index("--out") + 1], "w"), ensure_ascii=False, indent=1)

if __name__ == "__main__":
    main()
