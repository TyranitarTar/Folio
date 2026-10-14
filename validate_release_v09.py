"""Verify the core release, public source exclusions and actual scoped acceptance evidence."""
from pathlib import Path
import hashlib,json,re,sys,zipfile,xml.etree.ElementTree as E
root=Path(__file__).resolve().parent;out=root.parent/'output';audit=root/'audit'
def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()
apk=out/'Folio-0.9.0.apk';digest=sha(apk)
assert digest in (out/'Folio-0.9.0.apk.sha256').read_text()
assert apk.stat().st_size<100_000_000
with zipfile.ZipFile(apk) as now,zipfile.ZipFile(out/'Folio-0.8.1.apk') as old:
    native=[n for n in old.namelist() if n.startswith('lib/') and n.endswith('.so')]
    assert len(native)==12 and all(now.read(n)==old.read(n) for n in native)
    names=now.namelist()
    assert 'assets/voice-packs.json' in names
    assert not any(n.endswith(('.onnx','voices.bin','voice.bin','unicode_indexer.bin')) or '/espeak-ng-data/' in n for n in names)
    packs=json.loads(now.read('assets/voice-packs.json'))['packs']
    assert sum(len(p['voices']) for p in packs)==22 and all(v['sample'] for p in packs for v in p['voices'])
with zipfile.ZipFile(out/'Folio-source-0.9.0.zip') as z:
    names=z.namelist()
    assert not any('/signing/' in n or '/toolchain/' in n or '/audit/' in n or n.endswith(('.jks','.p12','.keystore','.onnx')) for n in names)
    assert not any(n.endswith('.epub') and not n.endswith('sample.epub') for n in names)
    for name in ('VoicePacksPanel.kt','VoicePackWorker.kt','VoiceSamplePlayer.kt','BookStatistics.kt','build_voice_packs.py','ROADMAP-0.9.md'):
        assert any(n.endswith(name) for n in names)
assert sha(out/'Folio-native-source-0.9.0.zip')==sha(out/'Folio-native-source-0.8.1.zip')
reports=list((root/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
counts={k:sum(int(E.parse(p).getroot().get(k,'0')) for p in reports) for k in ('tests','failures','errors','skipped')}
assert counts['tests']>=127 and all(counts[k]==0 for k in ('failures','errors','skipped'))
assert 'BUILD SUCCESSFUL' in (audit/'build-v09-final-checks.log').read_text()
lint=(root/'app/build/reports/lint-results-debug.txt').read_text();assert '0 errors' in lint
runs={}
for name in ('v09-reader-final.log','v09-voice-final.log','v09-voice-downloads.log'):
    text=(audit/name).read_text()
    assert 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text and 'Process crashed' not in text
    m=re.search(r'OK \((\d+) tests?\)',text);assert m,name
    runs[name]=int(m[1])
meta=(audit/'v09-apk-badging.log').read_text();assert "versionCode='11'" in meta and "versionName='0.9.0'" in meta
cert='8ba95f74fce4d7a85afc6126a6ee37b1d4c1baf8cecc4276038a0e3a256b087b'
assert cert in (audit/'v09-apk-signature.log').read_text()
upgrade={'status':'Pending published in-app update'} if '--prepublication' in sys.argv else json.loads((audit/'v09-upgrade-result.json').read_text())
if '--prepublication' not in sys.argv:assert upgrade['passed']
result=dict(version='0.9.0',versionCode=11,date='2026-10-14',apkBytes=apk.stat().st_size,apkSha256=digest,
    signerCertificateSha256=cert,jvm=counts,android=dict(runs=runs,device='Android 15 API35 x86_64 emulator'),
    nativeLibrariesUnchanged=12,modelsExcludedFromApk=True,authenticVoiceSamples=22,
    voicePacks=json.loads((out/'voice-packs/validation.json').read_text()),
    lint=dict(errors=0,warnings=int(re.search(r'(\d+) warnings',lint.splitlines()[-1])[1])),upgrade=upgrade,
    limitations='No physical Galaxy S10 or Bluetooth device was connected. Native source-frame tests do not prove acoustic endings, sustained ARM inference throughput, battery or thermal behavior at 3×.')
(out/'Folio-0.9.0-validation.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
