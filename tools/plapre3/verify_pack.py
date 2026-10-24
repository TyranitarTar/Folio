"""Verify every final archive member and unchanged existing catalog identities."""
from pathlib import Path
import hashlib,json,zipfile
ROOT=Path(__file__).resolve().parent;PROJECT=ROOT.parents[1]
out=PROJECT/'output/voice-packs-plapre3-v1'
pack=json.loads((out/'manifest.json').read_text(encoding='utf-8'))
assert pack['version']=='plapre3-fp32-5300281a-r10k-kanade961f20bf-hift-v1'
archive=out/'Folio-Voice-Plapre3-FP32-v1.zip'
def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for b in iter(lambda:stream.read(1024*1024),b''):h.update(b)
    return h.hexdigest()
assert archive.stat().st_size==pack['bytes'] and sha(archive)==pack['sha256']
with zipfile.ZipFile(archive) as z:
    assert set(z.namelist())=={f['name'] for f in pack['files']}
    for f in pack['files']:
        assert z.getinfo(f['name']).file_size==f['bytes']
        h=hashlib.sha256()
        with z.open(f['name']) as source:
            for b in iter(lambda:source.read(1024*1024),b''):h.update(b)
        assert h.hexdigest()==f['sha256'],f['name']
    assert b'Plapre 3' in z.read('NOTICE.txt') and b'10000' in z.read('NOTICE.txt')
with zipfile.ZipFile(PROJECT/'output/Folio-0.10.2.apk') as z:
    old=json.loads(z.read('assets/voice-packs.json'))['packs']
new=json.loads((PROJECT/'android-reader/app/src/main/assets/voice-packs.json').read_text(encoding='utf-8'))['packs']
assert len(new)==4
assert all(next(n for n in new if n['id']==p['id'])==p for p in old)
for v in pack['voices']:
    f=out/v['sample']['url'].rsplit('/',1)[-1]
    assert f.stat().st_size==v['sample']['bytes'] and sha(f)==v['sample']['sha256']
report=dict(passed=True,archiveBytes=pack['bytes'],expandedBytes=pack['expandedBytes'],
            archiveSha256=pack['sha256'],verifiedFiles=len(pack['files']),verifiedSamples=5,
            existingThreePackIdentitiesUnchanged=True)
(ROOT/'results/pack-validation.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
