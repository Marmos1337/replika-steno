"""Prototype of VAD -> per-phrase speaker embedding -> agglomerative clustering.
python vad_diar_proto.py <audio> <embedding.onnx> <threshold>"""
import subprocess, sys
import numpy as np
import sherpa_onnx

C = __file__.rsplit("/tools/", 1)[0] + "/models-cache"
SR = 16000

def load(p):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", p, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"], check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()

def vad_segments(x):
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = f"{C}/silero_vad.onnx"
    cfg.silero_vad.min_silence_duration = 0.25
    cfg.silero_vad.min_speech_duration = 0.25
    cfg.silero_vad.max_speech_duration = 12
    cfg.silero_vad.threshold = 0.5
    cfg.sample_rate = SR
    vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=600)
    w = 512; out = []
    for i in range(0, len(x), w):
        vad.accept_waveform(x[i:i + w])
        while not vad.empty():
            s = vad.front; out.append((s.start / SR, (s.start + len(s.samples)) / SR)); vad.pop()
    vad.flush()
    while not vad.empty():
        s = vad.front; out.append((s.start / SR, (s.start + len(s.samples)) / SR)); vad.pop()
    return out

def cluster(E, thr):
    # average-linkage agglomerative clustering on cosine distance
    E = E / np.linalg.norm(E, axis=1, keepdims=True)
    groups = [[i] for i in range(len(E))]
    while len(groups) > 1:
        best, bi, bj = 9, -1, -1
        for i in range(len(groups)):
            for j in range(i + 1, len(groups)):
                d = 1 - float(np.mean(E[groups[i]] @ E[groups[j]].T))
                if d < best: best, bi, bj = d, i, j
        if best > thr: break
        groups[bi] += groups[bj]; del groups[bj]
    lab = np.zeros(len(E), int)
    for g, idx in enumerate(groups): lab[idx] = g
    return lab

def main():
    x = load(sys.argv[1]); model = sys.argv[2]; thr = float(sys.argv[3])
    ext = sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=model, num_threads=4))
    segs = vad_segments(x)
    E = []
    for a, b in segs:
        st = ext.create_stream(); st.accept_waveform(SR, x[int(a * SR):int(b * SR)]); st.input_finished()
        E.append(np.array(ext.compute(st)))
    lab = cluster(np.array(E), thr)
    print(" ".join(f"{a:.1f}-{b:.1f}:{l}" for (a, b), l in zip(segs, lab)))

main()
