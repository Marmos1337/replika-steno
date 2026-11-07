"""A/B of audio pre-processing before the app pipeline, on clean and artificially noisy copies.
  python tools/preproc_ab.py <gtcrn_simple.onnx>
For each test dialog: clean, +noise at SNR 10 dB and 5 dB (pink noise + 50 Hz hum + far babble from
another recording), and quiet (-24 dB). Each variant goes through: none / hpf+norm / gtcrn denoise /
hpf+norm+gtcrn, then tools/mac_check.py (same models and rules as the app) and tools/eval.py.
Prints a WER / speaker-accuracy table. Noise is synthetic: it shows the direction, not field quality."""
import json, os, subprocess, sys, tempfile
from pathlib import Path
import numpy as np
import sherpa_onnx

ROOT = Path(__file__).resolve().parent.parent
T = ROOT / "testdata"
SR = 16000
CASES = [("ru_dialog_2voices.m4a", "ref_ru_dialog_2voices.json"), ("en_dialog_2voices.mp3", "ref_en_dialog_2voices.json")]
BABBLE = T / "long_mixed_6min.m4a"
rng = np.random.default_rng(7)


def load(p):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(p), "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"], check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


def pink(n):
    w = rng.standard_normal(n)
    f = np.fft.rfft(w); f[1:] /= np.sqrt(np.arange(1, len(f))); x = np.fft.irfft(f, n)
    return x / np.std(x)


def add_noise(x, snr_db, babble):
    n = len(x)
    noise = pink(n) + 0.5 * np.sin(2 * np.pi * 50 * np.arange(n) / SR) * 2
    b = babble[60 * SR:60 * SR + n] if len(babble) > 60 * SR + n else np.resize(babble, n)
    noise = noise / np.std(noise) + 0.7 * b / (np.std(b) + 1e-9)
    p_sig = np.mean(x[np.abs(x) > 0.01] ** 2) if np.any(np.abs(x) > 0.01) else np.mean(x ** 2)
    noise *= np.sqrt(p_sig / (10 ** (snr_db / 10)) / np.mean(noise ** 2))
    return (x + noise).astype(np.float32)


def hpf_norm(x):
    # 1st-order high-pass at ~80 Hz (removes hum/rumble) + RMS normalisation of speech to -20 dBFS, peak-limited
    a = np.exp(-2 * np.pi * 80 / SR); y = np.empty_like(x); prev_x = prev_y = 0.0
    for i, v in enumerate(x):
        prev_y = a * (prev_y + v - prev_x); prev_x = v; y[i] = prev_y
    act = y[np.abs(y) > 0.3 * np.max(np.abs(y))] if np.max(np.abs(y)) > 0 else y
    rms = np.sqrt(np.mean(act ** 2)) + 1e-9
    y = y * (10 ** (-20 / 20) / rms)
    peak = np.max(np.abs(y))
    return (y / peak * 0.95 if peak > 0.95 else y).astype(np.float32)


def denoiser(model):
    cfg = sherpa_onnx.OfflineSpeechDenoiserConfig(model=sherpa_onnx.OfflineSpeechDenoiserModelConfig(
        gtcrn=sherpa_onnx.OfflineSpeechDenoiserGtcrnModelConfig(model=model), num_threads=4))
    d = sherpa_onnx.OfflineSpeechDenoiser(cfg)
    return lambda x: np.asarray(d(x, SR).samples, dtype=np.float32)


def run(x, ref, env=None):
    with tempfile.TemporaryDirectory() as tmp:
        wav, out = f"{tmp}/a.wav", f"{tmp}/h.json"
        pcm = (np.clip(x, -1, 1) * 32767).astype("<i2").tobytes()
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "s16le", "-ar", str(SR), "-ac", "1", "-i", "-", wav], input=pcm, check=True)
        subprocess.run([sys.executable, str(ROOT / "tools/mac_check.py"), wav, "--out", out], check=True, capture_output=True, env={**os.environ, **(env or {})})
        r = json.loads(subprocess.check_output([sys.executable, str(ROOT / "tools/eval.py"), out, str(T / ref)]))
        return r["WER"], r["speaker_time_accuracy"], r["speakers_hyp"]


def main():
    dn = denoiser(sys.argv[1])
    babble = load(BABBLE)
    methods = {"none": lambda x: x, "hpf+norm": hpf_norm, "gtcrn": dn, "hpf+norm+gtcrn": lambda x: dn(hpf_norm(x)), "diar-norm": "1", "diar-quiet-gain": "auto"}
    only = os.environ.get("ONLY")
    if only: methods = {k: v for k, v in methods.items() if k in only.split(",")}
    rows = []
    for audio, ref in CASES:
        clean = load(T / audio)
        variants = {"clean": clean, "snr10": add_noise(clean, 10, babble), "snr5": add_noise(clean, 5, babble), "quiet-24dB": clean * 10 ** (-24 / 20)}
        for vname, x in variants.items():
            for mname, f in methods.items():
                w, acc, n = run(x, ref, {"DIAR_NORM": f}) if isinstance(f, str) else run(f(x), ref)
                rows.append((audio.split("_")[0], vname, mname, w, acc, n))
                print(f"{audio.split('_')[0]:3} {vname:11} {mname:15} WER {w*100:5.1f}%  spk {acc*100:5.1f}%  n={n}", flush=True)
    json.dump(rows, open(ROOT / f"artifacts/test-results/preproc_ab{os.environ.get('TAG', '')}.json", "w"), indent=1)


main()
