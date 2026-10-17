"""Offline PCM16 WAV edge-energy diagnostics; never trims or rewrites input audio.

Quiet energy windows are not a voice activity detector. Use the report to locate
candidate padding, then listen before changing production waveform boundaries.
"""

import argparse
import array
import hashlib
import json
import math
import pathlib
import sys
import wave


def analyze(path):
    with wave.open(str(path), "rb") as source:
        if (source.getnchannels(), source.getsampwidth(), source.getcomptype()) != (1, 2, "NONE"):
            raise ValueError(f"Expected uncompressed mono PCM16 WAV: {path}")
        rate = source.getframerate()
        frames = source.getnframes()
        samples = array.array("h", source.readframes(frames))
    if not frames or len(samples) != frames:
        raise ValueError(f"Empty or incomplete WAV: {path}")
    if sys.byteorder != "little":
        samples.byteswap()
    window = max(1, round(rate * 0.020))
    rms = []
    for start in range(0, frames, window):
        block = samples[start:start + window]
        rms.append(math.sqrt(sum(float(value) ** 2 for value in block) / len(block)) / 32768)
    edges = {}
    for threshold in (-45, -50, -55):
        active = [index for index, value in enumerate(rms) if value > 10 ** (threshold / 20)]
        edges[str(threshold)] = None if not active else {
            "leadingQuietSeconds": round(active[0] * window / rate, 4),
            "trailingQuietSeconds": round(max(0, frames - (active[-1] + 1) * window) / rate, 4),
        }
    return {
        "file": path.name,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "sampleRate": rate,
        "samples": frames,
        "seconds": round(frames / rate, 4),
        "quietEdgesByThresholdDbfs": edges,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", type=pathlib.Path, nargs="+", help="WAV files or directories of WAV files")
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    files = sorted({file for path in args.inputs for file in
                    (sorted(path.glob("*.wav")) if path.is_dir() else [path])})
    if not files:
        parser.error("No WAV inputs found")
    report = {
        "method": "20 ms RMS windows; absolute dBFS thresholds; PCM16 quantization",
        "limitation": "Energy thresholds do not identify speech. Quiet consonants, breath and noise can affect boundaries. No audio is changed.",
        "clips": [analyze(path) for path in files],
    }
    result = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(result, encoding="utf-8")
        print(f"Analyzed {len(files)} WAV files; report: {args.output}")
    else:
        print(result, end="")


if __name__ == "__main__":
    main()
