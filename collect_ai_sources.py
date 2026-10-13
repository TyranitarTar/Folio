"""Collect exact native sources and license texts for the distributed neural speech runtime."""
from pathlib import Path
import concurrent.futures
import hashlib
import json
import re
import shutil
import urllib.request
import zipfile
from bootstrap_ai import CACHE, ROOT, extract

SOURCE = CACHE / "source/sherpa-onnx-1.13.8"
DEPS = CACHE / "deps"
DEPS.mkdir(exist_ok=True)

def fetch_dependency(name):
    cmake = (SOURCE / f"cmake/{name}.cmake").read_text()
    url = re.search(r'set\([^\s]+_URL\s+"(https://github.com/[^"\s]+)"', cmake)[1]
    expected = re.search(r'SHA256=([a-f0-9]{64})', cmake)[1]
    suffix = ".zip" if url.endswith(".zip") else ".tar.gz"
    path = DEPS / (name + suffix)
    if not path.exists():
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Folio-build/0.3"}), timeout=120) as response, path.open("wb") as out:
            shutil.copyfileobj(response, out)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    assert digest == expected, name
    destination = CACHE / "source"
    if suffix == ".zip":
        with zipfile.ZipFile(path) as archive:
            for member in archive.infolist():
                assert (destination / member.filename).resolve().is_relative_to(destination.resolve())
            archive.extractall(destination)
    else:
        extract(path, destination)
    print("Verified native source", name, flush=True)
    return {"name": name, "url": url, "sha256": digest}

if __name__ == "__main__":
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        dependencies = list(pool.map(fetch_dependency, ["kaldi-native-fbank", "kaldi-decoder", "simple-sentencepiece", "json", "piper-phonemize"]))
    # Kaldi fbank includes KissFFT from its pinned subproject.
    fbank = next((CACHE / "source").glob("kaldi-native-fbank-*"))
    cmake = (fbank / "cmake/kissfft.cmake").read_text()
    url = re.search(r'set\(kissfft_URL\s+"([^"]+)"', cmake)[1]
    expected = re.search(r'SHA256=([a-f0-9]{64})', cmake)[1]
    kissfft = DEPS / "kissfft.zip"
    if not kissfft.exists():
        with urllib.request.urlopen(url, timeout=120) as response, kissfft.open("wb") as out:
            shutil.copyfileobj(response, out)
    assert hashlib.sha256(kissfft.read_bytes()).hexdigest() == expected
    with zipfile.ZipFile(kissfft) as archive:
        for member in archive.infolist():
            assert (CACHE / "source" / member.filename).resolve().is_relative_to((CACHE / "source").resolve())
        archive.extractall(CACHE / "source")
    dependencies.append({"name": "kissfft", "url": url, "sha256": expected})
    ort = DEPS / "onnxruntime-v1.28.2.tar.gz"
    url = "https://codeload.github.com/microsoft/onnxruntime/tar.gz/refs/tags/v1.28.2"
    if not ort.exists():
        with urllib.request.urlopen(url, timeout=120) as response, ort.open("wb") as out:
            shutil.copyfileobj(response, out)
    extract(ort, CACHE / "source")
    dependencies.append({"name": "onnxruntime", "url": url, "sha256": hashlib.sha256(ort.read_bytes()).hexdigest()})
    (CACHE / "native-dependencies.json").write_text(json.dumps(dependencies, indent=2))
    espeak = CACHE / "source/espeak-ng-ed530aa113046142eb5115cf2fc9157854d0ffe1"
    shutil.copy2(espeak / "COPYING", ROOT / "LICENSE")
    credits = ROOT / "app/src/main/assets/AI_VOICE_LICENSES.txt"
    paragraphs = ["Folio v0.5 local AI speech. Folio application code: GPL-3.0-or-later.\n"
        "Kokoro 82M v1.0 model: Apache-2.0. sherpa-onnx v1.13.8: Apache-2.0.\n"
        "Supertonic 3 by Supertone: model weights OpenRAIL-M; conversion code MIT.\n"
        "ONNX Runtime: MIT. eSpeak NG phonemizer: GPL-3.0-or-later.\n"
        "Corresponding native source and build scripts accompany the APK in Folio-native-source-0.5.0.zip.\n"]
    paragraphs.append("\nFOLIO / ESPEAK NG GPL LICENSE\n" + (ROOT / "LICENSE").read_text())
    paragraphs.append("\nKOKORO MODEL LICENSE\n" + (ROOT / "app/src/main/assets/kokoro/LICENSE").read_text())
    paragraphs.append("\nSUPERTONIC 3 MODEL WEIGHTS LICENSE (OpenRAIL-M)\n" +
        (ROOT / "app/src/main/assets/supertonic/LICENSE").read_text())
    paragraphs.append("\nSUPERTONIC 3 CONVERSION LICENSE (MIT)\n" +
        (ROOT / "app/src/main/assets/supertonic/CONVERSION-LICENSE").read_text())
    for directory in (CACHE / "source").iterdir():
        if not directory.is_dir() or directory == espeak:
            continue
        for name in ("LICENSE", "LICENSE.txt", "COPYING", "LICENSE.md", "COPYING.txt"):
            license = directory / name
            if license.exists():
                paragraphs.append("\n" + directory.name + " LICENSE\n" + license.read_text(errors="replace"))
                break
    # Runtime's license is permissive and independent of the GPL phonemizer.
    url = "https://raw.githubusercontent.com/microsoft/onnxruntime/v1.28.2/LICENSE"
    with urllib.request.urlopen(url, timeout=60) as response:
        paragraphs.append("\nONNX RUNTIME LICENSE\n" + response.read().decode())
    paragraphs.append("\nONNX RUNTIME THIRD-PARTY NOTICES\n" +
        (CACHE / "source/onnxruntime-1.28.2/ThirdPartyNotices.txt").read_text(encoding="utf-8"))
    credits.write_text("\n".join(paragraphs), encoding="utf-8")
    print("Licenses saved", flush=True)
