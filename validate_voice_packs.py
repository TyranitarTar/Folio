"""Check pinned archives and authentic preview assets before publication."""
import hashlib,json,zipfile
from pathlib import Path
root=Path(__file__).resolve().parent
folder=root.parent/'output/voice-packs'
catalog=json.loads((root/'app/src/main/assets/voice-packs.json').read_text(encoding='utf-8'))
def digest(stream):
    h=hashlib.sha256()
    for b in iter(lambda:stream.read(1024*1024),b''):h.update(b)
    return h.hexdigest()
report=[]
for pack in catalog['packs']:
    name=pack['url'].rsplit('/',1)[-1];archive=folder/name
    assert archive.stat().st_size==pack['bytes']
    assert digest(archive.open('rb'))==pack['sha256']
    with zipfile.ZipFile(archive) as z:
        assert set(z.namelist())=={f['name'] for f in pack['files']}
        for f in pack['files']:
            assert not f['name'].startswith('/') and '..' not in f['name'].split('/')
            assert z.getinfo(f['name']).file_size==f['bytes']
            with z.open(f['name']) as data:
                assert digest(data)==f['sha256']
    for v in pack['voices']:
        sample=v['sample'];assert sample
        path=folder/sample['url'].rsplit('/',1)[-1]
        assert path.stat().st_size==sample['bytes']<2_000_000
        assert digest(path.open('rb'))==sample['sha256']
        assert path.read_bytes()[4:8]==b'ftyp'
    report.append(dict(id=pack['id'],archiveBytes=pack['bytes'],expandedBytes=pack['expandedBytes'],sha256=pack['sha256'],files=len(pack['files']),samples=len(pack['voices'])))
assert len(json.loads((folder/'provenance.json').read_text(encoding='utf-8')))==22
(folder/'validation.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report,indent=2))
