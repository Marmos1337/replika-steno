"""Builds testdata/long_live_16min.m4a + its reference by concatenating the labelled test files with pauses.

Used for the >=15 min live-transcription run (queue growth, boundary losses/duplicates, final JSON).
Speaker ids: the same source file reuses its ids (same voices); different files get their own prefix,
so speaker-time accuracy on this file is only indicative (the synthetic voices may repeat across files).
"""
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
T = ROOT / "testdata"
# (audio, reference, speaker prefix) - pauses of GAP_S seconds between parts, one long silence in the middle
PARTS = [
    ("long_mixed_6min.m4a", "ref_long_mixed_6min.json", "m"),
    ("ru_dialog_2voices.m4a", "ref_ru_dialog_2voices.json", "r"),
    ("SILENCE", None, None),
    ("en_dialog_2voices.mp3", "ref_en_dialog_2voices.json", "e"),
    ("ru_dialog_2voices.m4a", "ref_ru_dialog_2voices.json", "r"),
    ("long_mixed_6min.m4a", "ref_long_mixed_6min.json", "m"),
    ("ru_dialog_2voices.m4a", "ref_ru_dialog_2voices.json", "r"),
]
GAP_S = 3
SILENCE_S = 20
SR = 16000


def duration_ms(path: Path) -> int:
    out = subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)])
    return round(float(out) * 1000)


def main(out_name: str = "long_live_16min") -> None:
    inputs, filters, refs, ids = [], [], [], {}
    t = 0
    n = 0
    for audio, ref, prefix in PARTS:
        if audio == "SILENCE":
            inputs += ["-f", "lavfi", "-t", str(SILENCE_S), "-i", f"anullsrc=r={SR}:cl=mono"]
            t += SILENCE_S * 1000
        else:
            inputs += ["-i", str(T / audio)]
            for u in json.loads((T / ref).read_text())["transcript"]:
                key = f"{prefix}:{u['speaker_id']}"
                ids.setdefault(key, f"speaker_{len(ids) + 1}")
                refs.append({**u, "speaker_id": ids[key], "start_ms": u["start_ms"] + t, "end_ms": u["end_ms"] + t})
            t += duration_ms(T / audio)
        filters.append(f"[{n}:a]aresample={SR},aformat=channel_layouts=mono[a{n}]")
        n += 1
        inputs += ["-f", "lavfi", "-t", str(GAP_S), "-i", f"anullsrc=r={SR}:cl=mono"]
        filters.append(f"[{n}:a]aformat=channel_layouts=mono[a{n}]")
        n += 1
        t += GAP_S * 1000
    graph = ";".join(filters) + ";" + "".join(f"[a{i}]" for i in range(n)) + f"concat=n={n}:v=0:a=1[out]"
    out = T / f"{out_name}.m4a"
    subprocess.check_call(["ffmpeg", "-v", "error", "-y", *inputs, "-filter_complex", graph, "-map", "[out]", "-c:a", "aac", "-b:a", "64k", str(out)])
    (T / f"ref_{out_name}.json").write_text(json.dumps({"transcript": refs}, ensure_ascii=False, indent=1))
    print(out, duration_ms(out), "ms,", len(refs), "utterances,", len(ids), "speaker ids; reference ends at", t, "ms")


if __name__ == "__main__":
    main(*sys.argv[1:])
