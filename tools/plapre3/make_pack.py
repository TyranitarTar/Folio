"""Build the standalone, checksummed FP32 Plapre 3 pack and signed catalog entry."""
from pathlib import Path
import hashlib,json,shutil,zipfile
from tokenizers import Tokenizer
ROOT=Path(__file__).resolve().parent
READER=next(p for p in [ROOT.parents[1]/'android-reader',ROOT.parent/'android-reader',ROOT.parent] if (p/'app/build.gradle.kts').is_file())
PROJECT=READER.parent;APP=READER/'app/src/main'
PICO=next(p for p in [ROOT.parent/'pico',ROOT.parent/'pico-tools'] if p.is_dir())
VERSION='plapre3-fp32-5300281a-r10k-kanade961f20bf-hift-v1'
BASE='https://github.com/TyranitarTar/Folio/releases/download/voice-packs-plapre3-v1/'
def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
    return h.hexdigest()
def main():
    reference_check=json.loads((ROOT/'results/lm-reference.json').read_text())
    assert reference_check['passed'] and reference_check['ropeTheta']==10000
    samples=json.loads((ROOT/'results/samples.json').read_text());assert len(samples)==7
    assert all(s['stopToken'] in [0,21223] for s in samples)
    out=PROJECT/'output/voice-packs-plapre3-v1';data=out/'files';data.mkdir(parents=True,exist_ok=True)
    sources={name:ROOT/'export'/name for name in ['plapre3.onnx','plapre3.onnx.data','embeddings.bin','voices.json']}
    sources.update({'tokenizer.json':ROOT/'artifacts/tokenizer.json',
                    'kanade.onnx':PICO/'export/kanade.onnx','hift.onnx':PICO/'export/hift.onnx'})
    for name,source in sources.items():
        target=data/name
        if target.exists():
            assert target.stat().st_size==source.stat().st_size and sha(target)==sha(source),name
        else:
            try:target.hardlink_to(source)
            except OSError:shutil.copyfile(source,target)
    reference=json.loads((ROOT/'reference-source.json').read_text())
    notice=(
        'Folio Plapre 3 Danish FP32 voice pack\n'
        'Model: syv.ai, syvai/plapre-3, revision 5300281a08c692a5ad18c9f00a4a47aa3dff9148, CC-BY-4.0.\n'
        'https://huggingface.co/syvai/plapre-3\nhttps://creativecommons.org/licenses/by/4.0/\n'
        f'Built-in speaker vectors: syv-ai/plapre, revision {reference["revision"]}.\n'
        'https://github.com/syv-ai/plapre\n'
        'Kanade codec: frothywater/kanade-25hz-clean, MIT; synthesis export from source 961f20bf892c59f391d0b6c5f7b88e70ed919b99.\n'
        'HiFT vocoder: FunAudioLLM/CosyVoice2-0.5B, Apache-2.0; hift.pt revision eec1ae6c79877dbd9379285cf8789c9e0879293d.\n'
        'Acoustic export adaptations: 42futures/plapre-pico-coreml, CC-BY-4.0, revision 5e10176a9edce39b150c659e3f73da1c2d819d74.\n'
        'Folio changes: explicit FP32 ONNX Llama export matching Transformers 4.57.6 rotary base 10000, cached decoding and whole-prompt prefill; precomputed speaker projections; inference-only Kanade/HiFT reused unchanged; conservative edge-padding cleanup.\n'
        'No model quantization, voice cloning or additional inter-sentence silence. This conversion is experimental and is not endorsed by the upstream authors.\n'
        '\nComplete license texts\n\n'+ '\n\n'.join(p.read_text(encoding='utf-8') for p in sorted((PICO/'licenses').glob('*.txt'))))
    (data/'NOTICE.txt').write_text(notice,encoding='utf-8')
    files=[dict(name=p.name,bytes=p.stat().st_size,sha256=sha(p)) for p in sorted(data.iterdir()) if p.is_file()]
    archive=out/'Folio-Voice-Plapre3-FP32-v1.zip'
    print('Compressing verified model files',flush=True)
    with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for spec in files:
            info=zipfile.ZipInfo(spec['name'],date_time=(2026,10,18,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            with (data/spec['name']).open('rb') as src,z.open(info,'w',force_zip64=True) as dst:shutil.copyfileobj(src,dst,1024*1024)
    assert archive.stat().st_size<2*1024**3,'GitHub release asset limit'
    voices=[]
    for i,name in enumerate(['ida','tor','liv','ask','kaj']):
        sample=out/f'Plapre3-da-{name}.wav';shutil.copyfile(ROOT/f'results/samples/plapre3-da-{i}.wav',sample)
        voices.append(dict(id=str(i),label=name.title(),language='da',sample=dict(url=BASE+sample.name,bytes=sample.stat().st_size,sha256=sha(sample))))
    pack=dict(id='plapre3',backend='plapre3',label='Plapre 3 · Dansk (experimental FP32)',version=VERSION,
              runtime='plapre3-fp32-onnx-v1',languages=['da'],url=BASE+archive.name,bytes=archive.stat().st_size,
              sha256=sha(archive),expandedBytes=sum(f['bytes'] for f in files),files=files,voices=voices,
              license='Plapre 3: syv.ai, CC-BY-4.0. Kanade: MIT. HiFT: Apache-2.0. FP32 Folio ONNX conversion; attribution and complete licenses in pack. 64-bit Android required.')
    (out/'manifest.json').write_text(json.dumps(pack,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    catalog=APP/'assets/voice-packs.json';j=json.loads(catalog.read_text(encoding='utf-8'))
    j['packs']=[p for p in j['packs'] if p['id']!='plapre3']+[pack]
    catalog.write_text(json.dumps(j,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    kotlin='package app.folio.reader\n\n// Generated by tools/plapre3/make_pack.py; identities are pinned by the signed APK.\ninternal object Plapre3PackIdentity {\n    val files = listOf(\n'
    kotlin+=',\n'.join(f'        SpeechModelFile("{f["name"]}", {f["bytes"]}L, "{f["sha256"]}")' for f in files)
    kotlin+='\n    )\n}\n';(APP/'java/app/folio/reader/Plapre3PackIdentity.kt').write_text(kotlin,encoding='utf-8')
    licenses=APP/'assets/AI_VOICE_LICENSES.txt';existing=licenses.read_text(encoding='utf-8').split('\n\nFOLIO PLAPRE 3\n')[0]
    licenses.write_text(existing+'\n\nFOLIO PLAPRE 3\n'+notice,encoding='utf-8')
    tests=READER/'app/src/test/resources'
    shutil.copyfile(ROOT/'artifacts/tokenizer.json',tests/'plapre3-tokenizer.json')
    tokenizer=Tokenizer.from_file(str(ROOT/'artifacts/tokenizer.json'))
    texts=['Hej, hvordan har du det?','Æblegrød på Rødovre Station.','Hun sagde: »Læs videre!«',
           '12,50 kroner og 25 bøger.','e\u0301 og é','  flere   mellemrum  ','en\tbog\r\nto bøger','Kat 🐈 og bøger 📚']
    (tests/'plapre3-tokenizer-cases.json').write_text(json.dumps([dict(text=t,tokens=tokenizer.encode(t,add_special_tokens=False).ids) for t in texts],ensure_ascii=False),encoding='utf-8')
    print(json.dumps({k:pack[k] for k in ['bytes','expandedBytes','sha256']}),flush=True)
if __name__=='__main__':main()
