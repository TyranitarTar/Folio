"""Fetch only a checksum-pinned public desktop narrator; no credentials or phone assets."""
from pathlib import Path
import argparse
import concurrent.futures
import hashlib
import json
import os
import urllib.request

HERE = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--engine', choices=['roest', 'chatterbox'], default='roest')
parser.add_argument('--output', type=Path, help='Local model directory; default: tools/narration/models/ENGINE')
args = parser.parse_args()
repo = 'CoRal-project/roest-v3-chatterbox-350m' if args.engine == 'roest' else 'ResembleAI/chatterbox-turbo'
catalog = json.loads((HERE / 'model-snapshots.json').read_text(encoding='utf-8'))
record = next(item for item in catalog if item['repository'] == repo)
target = args.output.resolve() if args.output else HERE / 'models' / args.engine
target.mkdir(parents=True, exist_ok=True)
selected = [item for item in record['files'] if item['name'] in ('ve.safetensors', 's3gen_meanflow.safetensors', 't3_turbo_v1.safetensors', 'conds.pt')
            or item['name'].endswith(('.json', '.txt'))]
assert all(isinstance(item.get('sha256'), str) and len(item['sha256']) == 64
           and all(char in '0123456789abcdef' for char in item['sha256']) for item in selected), 'Incomplete model checksum catalog'

def checksum(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(4 * 1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()

def fetch(item):
    name = item['name']
    assert Path(name).name == name
    path = target / name
    expected = item.get('sha256')
    if path.exists() and path.stat().st_size == item['bytes'] and (not expected or checksum(path) == expected):
        print(f'Verified cached {name}', flush=True)
        return
    partial = path.with_suffix(path.suffix + '.partial')
    start = partial.stat().st_size if partial.exists() else 0
    if start >= item['bytes']:
        partial.unlink(); start = 0
    url = f'https://huggingface.co/{repo}/resolve/{record["sha"]}/{name}'
    request = urllib.request.Request(url, headers={'Range': f'bytes={start}-'} if start else {})
    with urllib.request.urlopen(request, timeout=120) as source:
        append = start > 0 and source.status == 206
        if append:
            assert source.headers.get('Content-Range', '').startswith(f'bytes {start}-')
        else:
            start = 0
        with partial.open('ab' if append else 'wb') as output:
            total = start
            reported = total
            while True:
                data = source.read(1024 * 1024)
                if not data: break
                output.write(data); total += len(data)
                if total - reported >= 256 * 1024 * 1024:
                    print(f'{name}: {total / 1e9:.2f} / {item["bytes"] / 1e9:.2f} GB', flush=True)
                    reported = total
            output.flush(); os.fsync(output.fileno())
    assert partial.stat().st_size == item['bytes'], f'Unexpected size: {name}'
    actual = checksum(partial)
    assert not expected or actual == expected, f'Checksum mismatch: {name}'
    partial.replace(path)
    print(f'Ready {name} ({item["bytes"] / 1e6:.1f} MB)', flush=True)

with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(fetch, selected))
provenance = dict(repository=repo, revision=record['sha'], license=record['license'],
                  files=[dict(name=i['name'], bytes=i['bytes'], sha256=checksum(target / i['name'])) for i in selected])
(target / 'folio-model-provenance.json').write_text(json.dumps(provenance, indent=2), encoding='utf-8')
print(f'All selected model files verified: {target}', flush=True)
