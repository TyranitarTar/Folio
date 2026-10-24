"""Install checksum-pinned Nano weights, referencing existing identical Røst files."""
from pathlib import Path
import hashlib
import json
import os
import sys
import urllib.request

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from tools.narration.core import save_json,sha256_file

REVISION='71ccd1d0081b430592cea481f4307e764e07bc64'
REPO='ResembleAI/chatterbox-nano'
REQUIRED={'t3_nano_v1.safetensors','s3gen_meanflow.safetensors','ve.safetensors','conds.pt',
          'added_tokens.json','merges.txt','special_tokens_map.json','tokenizer_config.json','vocab.json'}


def valid(path,item):
    if not path.is_file() or path.stat().st_size!=item['size']:return False
    if item.get('lfs'):return sha256_file(path)==item['lfs']['sha256']
    payload=path.read_bytes()
    return hashlib.sha1(b'blob '+str(len(payload)).encode()+b'\0'+payload).hexdigest()==item['blobId']


def fetch(path,item):
    if valid(path,item):return
    temp=path.with_suffix(path.suffix+'.partial')
    start=temp.stat().st_size if temp.exists() else 0
    if start>=item['size']:temp.unlink();start=0
    request=urllib.request.Request(f'https://huggingface.co/{REPO}/resolve/{REVISION}/{item["rfilename"]}',
                                  headers={'Range':f'bytes={start}-'} if start else {})
    with urllib.request.urlopen(request,timeout=60) as response:
        append=start>0 and response.status==206
        if append and not response.headers.get('Content-Range','').startswith(f'bytes {start}-'):
            raise RuntimeError('Invalid resumed download range')
        with temp.open('ab' if append else 'wb') as stream:
            total=start if append else 0;reported=total
            while chunk:=response.read(4*1024*1024):
                stream.write(chunk);total+=len(chunk)
                if total-reported>=128*1024*1024:
                    print(f'{path.name}: {total/1e6:.0f}/{item["size"]/1e6:.0f} MB',flush=True);reported=total
            stream.flush();os.fsync(stream.fileno())
    if not valid(temp,item):raise RuntimeError('Checksum mismatch: '+path.name)
    temp.replace(path)


def main():
    manifest=json.loads((ROOT/'tools/narration/nano/upstream-manifest.json').read_text(encoding='utf-8'))
    if manifest['revision']!=REVISION or manifest['repository']!=REPO:raise RuntimeError('Unexpected model identity')
    folder=ROOT/'tools/narration/models/chatterbox-nano';folder.mkdir(parents=True,exist_ok=True)
    records=[];new_bytes=0;shared_bytes=0
    for item in manifest['files']:
        name=item['rfilename']
        if name not in REQUIRED and name!='README.md':continue
        if Path(name).name!=name:raise RuntimeError('Unsafe upstream filename')
        shared=ROOT/'tools/narration/models/roest'/name
        if name in ('s3gen_meanflow.safetensors','ve.safetensors') and valid(shared,item):
            path=shared;shared_bytes+=item['size']
        else:
            path=folder/name;fetch(path,item);new_bytes+=item['size']
        if name in REQUIRED:records.append(dict(name=name,path=str(path.resolve()),bytes=item['size'],sha256=sha256_file(path)))
    if {r['name'] for r in records}!=REQUIRED:raise RuntimeError('Incomplete Nano component list')
    save_json(folder/'folio-component-index.json',dict(engine='chatterbox-nano',files=records))
    save_json(folder/'folio-model-provenance.json',dict(repository=REPO,revision=REVISION,license='MIT',
        files=[{k:v for k,v in r.items() if k!='path'} for r in records]))
    print(json.dumps(dict(modelDir=str(folder),newModelBytes=new_bytes,sharedBytes=shared_bytes),indent=2),flush=True)


if __name__=='__main__':main()
