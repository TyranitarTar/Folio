"""Fetch pinned public Pico research artifacts without credentials or remote code execution."""
import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
SOURCES = {
    "pico": ("varsan-g/plapre-pico-ONNX", "40060c99ec388c19ec89b0c18030005e89c694ed"),
    "kanade": ("frothywater/kanade-25hz-clean", "cb2c8f10959ff0e5d3e97c9b82fcc3779c9532a5"),
    "hift": ("FunAudioLLM/CosyVoice2-0.5B", "eec1ae6c79877dbd9379285cf8789c9e0879293d"),
    "mobile": ("42futures/plapre-pico-coreml", "5e10176a9edce39b150c659e3f73da1c2d819d74"),
}
KANADE_SOURCE = "961f20bf892c59f391d0b6c5f7b88e70ed919b99"
FILES = {
    "pico": ["config.json", "tokenizer.json", "tokenizer_config.json", "speakers.json", "embed_tokens.npy",
             "onnx/model_decode.onnx", "onnx/model_decode.onnx.data", "onnx/model_decode_int8.onnx",
             "onnx/speaker_proj.onnx", "onnx/speaker_proj.onnx.data"],
    "kanade": ["config.yaml", "model.safetensors"],
    "hift": ["hift.pt"],
    "mobile": ["README.md", "TRIALS.md", "scripts/convert_audio.py", "scripts/attention.py",
               "scripts/model_wrapper.py", "swift-cli/Sources/Inference.swift", "swift-cli/Sources/main.swift"],
}

def request(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Folio-Pico-Research/1"}), timeout=120)

def digest(path, lfs):
    h = hashlib.sha256() if lfs else hashlib.sha1()
    if not lfs:
        h.update(f"blob {path.stat().st_size}\0".encode())
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()

def download(group, info):
    repo, revision = SOURCES[group]
    name = info["rfilename"]
    target = ROOT / "artifacts" / group / name
    target.parent.mkdir(parents=True, exist_ok=True)
    lfs = info.get("lfs")
    expected = lfs["sha256"] if lfs else info["blobId"]
    if target.exists() and target.stat().st_size == info["size"] and digest(target, lfs) == expected:
        return f"Verified {group}/{name} ({info['size']} bytes)"
    partial = target.with_name(target.name + ".partial")
    with request(f"https://huggingface.co/{repo}/resolve/{revision}/{name}") as response, partial.open("wb") as output:
        while True:
            block = response.read(1024 * 1024)
            if not block:
                break
            output.write(block)
    if partial.stat().st_size != info["size"] or digest(partial, lfs) != expected:
        raise RuntimeError(f"Checksum/size mismatch for {group}/{name}")
    partial.replace(target)
    return f"Downloaded and verified {group}/{name} ({info['size']} bytes)"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--small-only", action="store_true")
    args = parser.parse_args()
    with request(f"https://api.github.com/repos/frothywater/kanade-tokenizer/git/trees/{KANADE_SOURCE}?recursive=1") as response:
        tree = json.load(response)
    for entry in tree["tree"]:
        name = entry["path"]
        if entry["type"] != "blob" or not (name.startswith("src/kanade_tokenizer/") and name.endswith(".py")):
            continue
        target = ROOT / "vendor/kanade" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.is_file() or digest(target, False) != entry["sha"]:
            with request(f"https://raw.githubusercontent.com/frothywater/kanade-tokenizer/{KANADE_SOURCE}/{name}") as response:
                target.write_bytes(response.read())
            if digest(target, False) != entry["sha"]:
                raise RuntimeError(f"Kanade source checksum mismatch: {name}")
    tasks = []
    for group, (repo, revision) in SOURCES.items():
        with request(f"https://huggingface.co/api/models/{repo}/revision/{revision}?blobs=true") as response:
            metadata = json.load(response)
        if metadata["sha"] != revision:
            raise RuntimeError("Unexpected upstream revision")
        destination = ROOT / "artifacts" / group
        destination.mkdir(parents=True, exist_ok=True)
        (destination / "source-manifest.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
        available = {f["rfilename"]: f for f in metadata["siblings"]}
        for name in FILES[group]:
            info = available[name]
            if not args.small_only or info["size"] < 4_000_000:
                tasks.append((group, info))
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        futures = [pool.submit(download, *task) for task in tasks]
        for future in concurrent.futures.as_completed(futures):
            print(future.result(), flush=True)

if __name__ == "__main__":
    main()
