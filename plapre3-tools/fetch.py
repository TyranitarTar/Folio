"""Download public pinned Plapre 3 inputs, checking LFS SHA-256 or Git blob IDs.

No credentials or remote-code execution. Upstream scripts are retained for
inspection and speaker vectors, not imported as executable model code.
"""
from pathlib import Path
import hashlib,json,urllib.request
ROOT=Path(__file__).resolve().parent
MODEL='5300281a08c692a5ad18c9f00a4a47aa3dff9148'
SOURCE='b111239d3b099cfafcb54b3471a8e9e9ba71ae8b'
def request(url):return urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Folio-Plapre3/1'}),timeout=120)
def digest(path,lfs):
    h=hashlib.sha256() if lfs else hashlib.sha1()
    if not lfs:h.update(f'blob {path.stat().st_size}\0'.encode())
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
    return h.hexdigest()
def main():
    ROOT.mkdir(exist_ok=True)
    with request(f'https://huggingface.co/api/models/syvai/plapre-3/revision/{MODEL}?blobs=true') as stream:j=json.load(stream)
    assert j['sha']==MODEL and not j.get('gated')
    (ROOT/'upstream-manifest.json').write_text(json.dumps(j,indent=2),encoding='utf-8')
    for f in j['siblings']:
        name=f['rfilename']
        if name not in ['model.safetensors','speaker_proj.pt','tokenizer.json','tokenizer_config.json','special_tokens_map.json','config.json','README.md']:continue
        p=ROOT/'artifacts'/name;p.parent.mkdir(exist_ok=True)
        lfs=f.get('lfs');expected=lfs['sha256'] if lfs else f['blobId']
        if p.is_file() and p.stat().st_size==f['size'] and digest(p,lfs)==expected:
            print('Verified '+name,flush=True);continue
        partial=p.with_name(p.name+'.partial')
        with request(f'https://huggingface.co/syvai/plapre-3/resolve/{MODEL}/{name}') as stream,partial.open('wb') as target:
            for block in iter(lambda:stream.read(1024*1024),b''):target.write(block)
        assert partial.stat().st_size==f['size'] and digest(partial,lfs)==expected,name
        partial.replace(p);print('Downloaded and verified '+name,flush=True)
    with request(f'https://api.github.com/repos/syv-ai/plapre/git/trees/{SOURCE}?recursive=1') as stream:tree=json.load(stream)
    paths=['plapre/inference.py','plapre/tasks.py','plapre/speakers.json','pyproject.toml','README.md']
    for entry in tree['tree']:
        if entry['path'] not in paths:continue
        p=ROOT/'reference'/entry['path'];p.parent.mkdir(parents=True,exist_ok=True)
        with request(f'https://raw.githubusercontent.com/syv-ai/plapre/{SOURCE}/{entry["path"]}') as stream:p.write_bytes(stream.read())
        assert digest(p,False)==entry['sha']
    (ROOT/'reference-source.json').write_text(json.dumps(dict(repo='syv-ai/plapre',revision=SOURCE),indent=2)+'\n')
if __name__=='__main__':main()
