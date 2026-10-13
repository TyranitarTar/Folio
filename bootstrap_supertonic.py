"""Fetch a pinned Supertonic INT8 pack and retain upstream model license/provenance."""
from pathlib import Path
import concurrent.futures
import hashlib
import json
import shutil
import urllib.request

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / "app/src/main/assets/supertonic"
REPO = "csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
REVISION = "cca5a0e6c96e1d2c720986bf7e75fcc81dee3ae4"
UPSTREAM = "supertone-oss-archive/supertonic-3"
UPSTREAM_REVISION = "aafc6e32416a594460b32413efc49d7fe4ce6d46"
FILES = ("duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
         "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin", "README.md", "LICENSE")

def open_url(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Folio-build/0.4"}), timeout=90)

def fetch(name, metadata, destination=None, url=None):
    path = ASSETS / (destination or name)
    source = url or f"https://huggingface.co/{REPO}/resolve/{REVISION}/{name}"
    expected = metadata.get("lfs", {}).get("sha256")
    expected_size = metadata.get("size") or metadata.get("lfs", {}).get("size")
    if not path.exists() or (expected and hashlib.sha256(path.read_bytes()).hexdigest() != expected):
        temporary = path.with_name(path.name + ".partial")
        print("Downloading", path.name, flush=True)
        with open_url(source) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output, 1024 * 1024)
        if expected_size and temporary.stat().st_size != expected_size:
            raise ValueError(f"Unexpected length: {name}")
        digest = hashlib.sha256(temporary.read_bytes()).hexdigest()
        if expected and digest != expected:
            raise ValueError(f"SHA-256 mismatch: {name}")
        temporary.replace(path)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if expected_size and path.stat().st_size != expected_size:
        raise ValueError(f"Unexpected length: {name}")
    print("Verified", path.name, path.stat().st_size, digest, flush=True)
    return {"name": path.name, "url": source, "bytes": path.stat().st_size, "sha256": digest}

if __name__ == "__main__":
    ASSETS.mkdir(parents=True, exist_ok=True)
    with open_url(f"https://huggingface.co/api/models/{REPO}/revision/{REVISION}?blobs=true") as response:
        metadata = json.load(response)
    assert metadata["sha"] == REVISION
    siblings = {item["rfilename"]: item for item in metadata["siblings"]}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        inventory = list(pool.map(lambda name: fetch(name, siblings[name],
                         destination="CONVERSION-LICENSE" if name == "LICENSE" else None), FILES))
    # The converter's MIT license does not replace the original model's OpenRAIL-M license.
    inventory.append(fetch("LICENSE", {}, url=f"https://huggingface.co/{UPSTREAM}/resolve/{UPSTREAM_REVISION}/LICENSE"))
    record = {"model": "Supertonic 3 INT8", "repository": REPO, "revision": REVISION,
              "upstream_repository": UPSTREAM, "upstream_revision": UPSTREAM_REVISION,
              "license": "OpenRAIL-M (model); MIT (conversion packaging)", "files": inventory}
    (ASSETS / "artifacts.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    print("Installed Supertonic assets", sum(item["bytes"] for item in inventory), "bytes", flush=True)
