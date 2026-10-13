"""Fetch pinned AI voice artifacts, verify release hashes, and install offline APK assets."""
from pathlib import Path
import concurrent.futures
import hashlib
import json
import shutil
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / "reference/ai"
CACHE.mkdir(parents=True, exist_ok=True)
TAG = "v1.13.8"
ARTIFACTS = {
    "runtime.tar.bz2": (f"https://github.com/k2-fsa/sherpa-onnx/releases/download/{TAG}/sherpa-onnx-{TAG}-android.tar.bz2", "2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44"),
    "model.tar.bz2": ("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2", "c5f7e2d2caf082bc1d20fb70334a61d99d20b484500aad32e7cf84c128ea3298"),
    "sherpa-source.tar.gz": (f"https://codeload.github.com/k2-fsa/sherpa-onnx/tar.gz/refs/tags/{TAG}", "b0374cc56dbc186d442ae73d5de743bb092470b640c4c50ce7b029044c0c4fa8"),
    "espeak-source.zip": ("https://github.com/csukuangfj/espeak-ng/archive/ed530aa113046142eb5115cf2fc9157854d0ffe1.zip", "e4e262cbe34f7fe21f91f1ba3397f2728e1f30eafbae7853f2b753a9ed13f0dd"),
    "Tts.kt": (f"https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/{TAG}/sherpa-onnx/kotlin-api/Tts.kt", "1a2a19e247553f774c5abdfda8319ce9e1a44e21279b16dc2c63e963a82ea9b1"),
}

def fetch(item):
    name, (url, expected) = item
    path = CACHE / name
    if not path.exists():
        print("Downloading", name, flush=True)
        request = urllib.request.Request(url, headers={"User-Agent": "Folio-build/0.3"})
        temporary = path.with_suffix(path.suffix + ".part")
        with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as out:
            shutil.copyfileobj(response, out)
        temporary.replace(path)
    digest = hashlib.file_digest(path.open("rb"), "sha256").hexdigest() if hasattr(hashlib, "file_digest") else hashlib.sha256(path.read_bytes()).hexdigest()
    if expected and digest != expected:
        raise ValueError(f"Hash mismatch: {name}: {digest}")
    print("Verified", name, path.stat().st_size, digest, flush=True)
    return {"name": name, "url": url, "sha256": digest, "bytes": path.stat().st_size}

def extract(path, destination):
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(path) as archive:
        members = []
        for member in archive.getmembers():
            resolved = (destination / member.name).resolve()
            if not resolved.is_relative_to(destination.resolve()):
                raise ValueError("Archive path escapes destination")
            if member.isfile() or member.isdir():
                members.append(member)
        archive.extractall(destination, members=members)

if __name__ == "__main__":
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        inventory = list(pool.map(fetch, ARTIFACTS.items()))
    (CACHE / "artifacts.json").write_text(json.dumps(inventory, indent=2), encoding="utf-8")
    extract(CACHE / "runtime.tar.bz2", CACHE / "runtime")
    extract(CACHE / "model.tar.bz2", CACHE / "model")
    extract(CACHE / "sherpa-source.tar.gz", CACHE / "source")
    with zipfile.ZipFile(CACHE / "espeak-source.zip") as archive:
        for member in archive.infolist():
            if not (CACHE / "source" / member.filename).resolve().is_relative_to((CACHE / "source").resolve()):
                raise ValueError("Archive path escapes destination")
        archive.extractall(CACHE / "source")
    model = next((CACHE / "model").glob("kokoro-multi-lang-v1_0"))
    assets = ROOT / "app/src/main/assets/kokoro"
    assets.mkdir(parents=True, exist_ok=True)
    for name in ("model.onnx", "voices.bin", "tokens.txt", "LICENSE", "README.md"):
        shutil.copy2(model / name, assets / name)
    shutil.copytree(model / "espeak-ng-data", assets / "espeak-ng-data", dirs_exist_ok=True)
    jni = ROOT / "app/src/main/jniLibs"
    for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
        libraries = list((CACHE / "runtime").rglob(f"{abi}/*.so"))
        if not libraries:
            raise ValueError(f"No native libraries for {abi}")
        (jni / abi).mkdir(parents=True, exist_ok=True)
        for library in libraries:
            if library.name not in ("libonnxruntime.so", "libsherpa-onnx-jni.so"):
                continue
            shutil.copy2(library, jni / abi / library.name)
            print("Installed", abi, library.name, library.stat().st_size, flush=True)
    kotlin = ROOT / "app/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt"
    kotlin.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(CACHE / "Tts.kt", kotlin)
    (assets / "artifacts.json").write_text(json.dumps(inventory[:2], indent=2), encoding="utf-8")
    print("Offline AI assets installed.", flush=True)
