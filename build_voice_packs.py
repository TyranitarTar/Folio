"""Create immutable data-only voice archives and the APK's pinned pack/sample catalog."""
from pathlib import Path
import hashlib,json,re,sys,zipfile
root=Path(__file__).resolve().parent
assets=root/'app/src/main/assets'
out=root.parent/'output/voice-packs';out.mkdir(parents=True,exist_ok=True)
def sha(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()
base='https://github.com/TyranitarTar/Folio/releases/download/voice-packs-v1/'
catalog=[]
for identity,label,version,archive,languages in [
 ('supertonic','Supertonic 3','supertonic3-int8-20260511-cca5a0e6-sherpa1.13.8-steps8-seed42','Folio-supertonic3-int8-v1.zip',['en','da']),
 ('kokoro','Kokoro','kokoro-v1_0-fp32-sherpa1.13.8','Folio-kokoro-v1.0-fp32.zip',['en'])]:
    folder=assets/identity
    files=[]
    for p in sorted(folder.rglob('*')):
        if not p.is_file():continue
        name=p.relative_to(folder).as_posix()
        files.append(dict(name=name,bytes=p.stat().st_size,sha256=sha(p),required=p.name not in ['LICENSE','CONVERSION-LICENSE','README.md','artifacts.json']))
    destination=out/archive
    if not destination.exists() or '--repack' in sys.argv:
        with zipfile.ZipFile(destination,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
            for entry in files:
                info=zipfile.ZipInfo(entry['name'],(2026,10,14,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
                with z.open(info,'w') as dst,(folder/entry['name']).open('rb') as src:
                    for chunk in iter(lambda:src.read(1024*1024),b''):dst.write(chunk)
    voices=[]
    if identity=='kokoro':
        variants=[('21','Emma · British English','en','sample-kokoro-emma-en.m4a'),('3','Heart · American English','en','sample-kokoro-heart-en.m4a')]
    else:
        variants=[(str(n),f'Voice {n+1}',lang,f'sample-supertonic-v{n+1:02}-{lang}.m4a') for lang in languages for n in range(10)]
    for sid,name,language,sample in variants:
        p=out/sample
        voices.append(dict(id=sid,label=name,language=language,sample=None if not p.exists() else dict(url=base+sample,bytes=p.stat().st_size,sha256=sha(p))))
    catalog.append(dict(id=identity,backend=identity,label=label,version=version,runtime='sherpa-onnx-1.13.8',
        languages=languages,url=base+archive,bytes=destination.stat().st_size,sha256=sha(destination),
        expandedBytes=sum(e['bytes'] for e in files),files=files,voices=voices,
        license='OpenRAIL-M (model), MIT (conversion)' if identity=='supertonic' else 'Apache-2.0 (weights), GPL-3.0-or-later (phonemizer runtime)'))
    print(f'{label}: {destination.stat().st_size/1e6:.2f} MB archive, {len(files)} files, {sum(v["sample"] is not None for v in voices)}/{len(voices)} samples')
(assets/'voice-packs.json').write_text(json.dumps(dict(schema=1,packs=catalog),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(out/'voice-packs.json').write_bytes((assets/'voice-packs.json').read_bytes())
