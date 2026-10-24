"""Build a pinned standalone Pico voice archive, samples, catalog entry and JVM fixtures."""
import hashlib
import json
import pathlib
import shutil
import zipfile
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer
from probe_lm import ROOT

READER = next(p for p in [ROOT.parent / "android-reader", ROOT.parent.parent / "android-reader", ROOT.parent] if (p / "app/build.gradle.kts").is_file())
PROJECT = READER.parent
VERSION = "pico-int8-r100k-40060c99-kanade961f20bf-hift-v1"
BASE = "https://github.com/TyranitarTar/Folio/releases/download/voice-packs-pico-v1/"

def sha(path):
    h=hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda:f.read(1024*1024),b""):
            h.update(block)
    return h.hexdigest()

def main():
    output=PROJECT / "output/voice-packs-pico-v1"
    data=output / "files"; data.mkdir(parents=True,exist_ok=True)
    source=ROOT / "artifacts/pico"
    for old,new in [(ROOT/"export/pico.onnx","pico.onnx"),
                    (ROOT/"export/kanade.onnx","kanade.onnx"),(ROOT/"export/hift.onnx","hift.onnx"),
                    (source/"tokenizer.json","tokenizer.json")]:
        shutil.copyfile(old,data/new)
    np.load(source/"embed_tokens.npy").astype("<f4").tofile(data/"embeddings.bin")
    options=ort.SessionOptions();options.intra_op_num_threads=1
    projection=ort.InferenceSession(str(source/"onnx/speaker_proj.onnx"),options)
    speakers=json.loads((source/"speakers.json").read_text())
    voices=[]
    for i,key in enumerate(["ida","tor","liv","ask","kaj"]):
        embedding=np.asarray(speakers[key],np.float32)
        prefix=projection.run(None,{projection.get_inputs()[0].name:embedding[None]})[0].reshape(-1)
        voices.append(dict(id=i,key=key,embedding=embedding.tolist(),prefix=prefix.tolist()))
    (data/"voices.json").write_text(json.dumps(voices,separators=(",",":")),encoding="utf-8")
    (data/"NOTICE.txt").write_text(
        "Folio Plapre Pico Danish voice pack\n"
        "Base model: syv.ai / syvai/plapre-pico, CC-BY-4.0.\nhttps://huggingface.co/syvai/plapre-pico\n"
        "License: https://creativecommons.org/licenses/by/4.0/\n"
        "LM conversion: varsan-g/plapre-pico-ONNX, revision 40060c99ec388c19ec89b0c18030005e89c694ed.\n"
        "Base weight attribution and conditions are retained regardless of the conversion card's MIT label.\n"
        "Codec: frothywater/kanade-25hz-clean, MIT; https://github.com/frothywater/kanade-tokenizer\n"
        "Vocoder: FunAudioLLM/CosyVoice2-0.5B hift.pt, Apache-2.0.\n"
        "Portable rotary attention adapted from 42futures/plapre-pico-coreml (CC-BY-4.0), revision 5e10176a9edce39b150c659e3f73da1c2d819d74.\n"
        "Folio changes: corrected rotary base to configured 100000; synthesis-only ONNX exports; real STFT/iSTFT; explicit excitation randomness; disabled inference dropout; precomputed voice projections.\n"
        "This is an experimental conversion, not an endorsement by the original authors.\n",encoding="utf-8")
    license_text="\n\n".join(path.read_text(encoding="utf-8") for path in sorted((ROOT/"licenses").glob("*.txt")))
    with (data/"NOTICE.txt").open("a",encoding="utf-8") as notice:
        notice.write("\nComplete license texts\n\n"+license_text)
    files=[dict(name=p.name,bytes=p.stat().st_size,sha256=sha(p)) for p in sorted(data.iterdir()) if p.is_file()]
    archive=output/"Folio-Voice-Pico-v1.zip"
    with zipfile.ZipFile(archive,"w",compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for spec in files:
            info=zipfile.ZipInfo(spec["name"],date_time=(2026,10,16,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            with (data/spec["name"]).open("rb") as src,z.open(info,"w",force_zip64=True) as dst:
                shutil.copyfileobj(src,dst,1024*1024)
    previews=[]
    for i,voice in enumerate(voices):
        name=f"Pico-da-{voice['key']}.wav"
        target=output/name;shutil.copyfile(ROOT/f"results/samples/pico-da-{i}.wav",target)
        previews.append(dict(id=str(i),label=voice["key"].title(),language="da",
                             sample=dict(url=BASE+name,bytes=target.stat().st_size,sha256=sha(target))))
    pack=dict(id="pico",backend="pico",label="Plapre Pico · Dansk (experimental)",version=VERSION,
              runtime="pico-onnx-v1",languages=["da"],url=BASE+archive.name,bytes=archive.stat().st_size,
              sha256=sha(archive),expandedBytes=sum(f["bytes"] for f in files),files=files,voices=previews,
              license="Plapre Pico: syv.ai, CC-BY-4.0. Kanade: MIT. HiFT: Apache-2.0. Experimental Folio ONNX conversion; source attribution included in the pack.")
    (output/"manifest.json").write_text(json.dumps(pack,indent=2,ensure_ascii=False),encoding="utf-8")
    app=READER/"app/src/main"
    catalog=json.loads((app/"assets/voice-packs.json").read_text())
    catalog["packs"]=[p for p in catalog["packs"] if p["id"]!="pico"]+[pack]
    (app/"assets/voice-packs.json").write_text(json.dumps(catalog,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")
    kotlin="package app.folio.reader\n\n// Generated from the checksummed Pico pack by tools/pico/make_pack.py.\ninternal object PicoPackIdentity {\n    val files = listOf(\n"
    kotlin+=",\n".join(f'        SpeechModelFile("{f["name"]}", {f["bytes"]}L, "{f["sha256"]}")' for f in files)
    kotlin+="\n    )\n}\n"
    (app/"java/app/folio/reader/PicoPackIdentity.kt").write_text(kotlin,encoding="utf-8")
    notices=app/"assets/AI_VOICE_LICENSES.txt"
    existing=notices.read_text(encoding="utf-8").split("\n\nFOLIO PLAPRE PICO\n")[0]
    notices.write_text(existing+"\n\nFOLIO PLAPRE PICO\n"+(data/"NOTICE.txt").read_text(encoding="utf-8"),encoding="utf-8")
    tests=READER/"app/src/test/resources";tests.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(source/"tokenizer.json",tests/"pico-tokenizer.json")
    tokenizer=Tokenizer.from_file(str(source/"tokenizer.json"))
    texts=["Hej, hvordan har du det?","Æblegrød på Rødovre Station.","Nu går vi hjem.\nHun bliver her.",
           "Hun sagde: »Læs videre!«", "12,50 kroner og 25 bøger.","e\u0301 og é", "  flere   mellemrum  ",
           "en\tbog\r\nto bøger", "A\u00a0B\u2003C", "Kat 🐈 og bøger 📚", "Verdenssundhedsorganisationen", "Can't won't I'll."]
    (tests/"pico-tokenizer-cases.json").write_text(json.dumps([dict(text=t,tokens=tokenizer.encode(t,add_special_tokens=False).ids) for t in texts],ensure_ascii=False),encoding="utf-8")
    print(json.dumps({k:pack[k] for k in ["version","bytes","expandedBytes","sha256"]}),flush=True)

if __name__=="__main__":
    main()
