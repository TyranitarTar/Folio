"""Collect binary WAV previews and timing JSON without PowerShell binary redirection."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parent
output = root.parent / "output"
adb = root / "toolchain/sdk/platform-tools/adb.exe"
os.environ["ANDROID_USER_HOME"] = str(root / "toolchain/android-user")
for source, target in (("ai-bf_emma-sample.wav", "Folio-0.5.0-Emma-preview.wav"),
                       ("ai-af_heart-sample.wav", "Folio-0.5.0-Heart-preview.wav"),
                       ("ai-validation.json", "Folio-0.5.0-AI-test-evidence.json"),
                       ("supertonic-en-sample.wav", "Folio-0.5.0-Supertonic-English-preview.wav"),
                       ("supertonic-da-sample.wav", "Folio-0.5.0-Supertonic-Danish-preview.wav"),
                       ("supertonic-validation.json", "Folio-0.5.0-Supertonic-test-evidence.json"),
                       ("kokoro-performance.json", "Folio-0.5.0-Kokoro-performance.json"),
                       ("continuous-speech-validation.json", "Folio-0.5.0-continuous-speech-evidence.json"),
                       ("read-from-here-validation.json", "Folio-0.5.0-read-from-here-evidence.json"),
                       ("home-v5-details.png", "Folio-0.5.0-home-v5-details.png"),
                       ("home-v5-library.png", "Folio-0.5.0-home-v5-library.png"),
                       ("home-v5-shelf.png", "Folio-0.5.0-home-v5-shelf.png")):
    result = subprocess.run([str(adb), "exec-out", "run-as", "app.folio.reader.debug", "cat", "files/" + source], check=True, capture_output=True)
    (output / target).write_bytes(result.stdout)
    print(target, len(result.stdout))
