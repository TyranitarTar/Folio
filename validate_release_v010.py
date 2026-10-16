"""Validate the signed thin Pico candidate, source exclusions and actual scoped evidence."""
from pathlib import Path
import hashlib,json,re,struct,zipfile,xml.etree.ElementTree as E

root=Path(__file__).resolve().parent
out=root.parent/'output'
qa=root.parent/'tools/pico'
def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
    return h.hexdigest()
apk=out/'Folio-0.10.0.apk'
digest=sha(apk)
assert digest in (out/'Folio-0.10.0.apk.sha256').read_text()
assert apk.stat().st_size<100_000_000
with zipfile.ZipFile(apk) as now,zipfile.ZipFile(out/'Folio-0.9.0.apk') as old:
    natives=[n for n in old.namelist() if n.startswith('lib/') and n.endswith('.so')]
    assert len(natives)==12 and all(now.read(n)==old.read(n) for n in natives)
    bridges=[n for n in now.namelist() if n.endswith('/libfolio_pico.so')]
    assert len(bridges)==4
    for name in bridges:
        raw=now.read(name);is64=raw[4]==2
        offset=struct.unpack_from('<Q' if is64 else '<I',raw,32 if is64 else 28)[0]
        size,count=struct.unpack_from('<HH',raw,54 if is64 else 42)
        loads=[]
        for index in range(count):
            at=offset+index*size
            if struct.unpack_from('<I',raw,at)[0]==1:
                loads.append(struct.unpack_from('<Q' if is64 else '<I',raw,at+(48 if is64 else 28))[0])
        assert loads and min(loads)>=16384,(name,loads)
    assert not any(n.endswith('.onnx') or n.endswith('/embeddings.bin') for n in now.namelist())
    catalog=json.loads(now.read('assets/voice-packs.json'))['packs']
    assert len(catalog)==3 and sum(len(p['voices']) for p in catalog)==27
    pack=next(p for p in catalog if p['backend']=='pico')
    assert pack['sha256']=='84f7ea1939719278e1e24db3751431a0cdf4d7c674a0c1d6f0a32eab9730ac2f'
    assert pack['bytes']==528869334 and pack['expandedBytes']==621778031
with zipfile.ZipFile(out/'Folio-source-0.10.0.zip') as source:
    names=source.namelist()
    assert not any('/signing/' in n or '/toolchain/' in n or '/audit/' in n or '/.cxx/' in n or n.endswith(('.jks','.p12','.keystore','.onnx','.safetensors')) for n in names)
    assert not any(n.endswith('.epub') and not n.endswith('sample.epub') for n in names)
    assert any(n.endswith('pico_jni.cpp') for n in names)
    assert any(n.endswith('pico-tools/fix_lm.py') for n in names)
with zipfile.ZipFile(out/'Folio-native-source-0.10.0.zip') as source:
    assert any(n.endswith('pico/pico_jni.cpp') for n in source.namelist())
rows=[E.parse(p).getroot() for p in (root/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
counts={k:sum(int(row.get(k,'0')) for row in rows) for k in ('tests','failures','errors','skipped')}
assert counts['tests']>=134 and all(counts[k]==0 for k in ('failures','errors','skipped'))
assert 'BUILD SUCCESSFUL' in (qa/'final-build.log').read_text()
lint=(root/'app/build/reports/lint-results-debug.txt').read_text();assert '0 errors' in lint
for name,total in [('android-instrumentation.log',3),('android-regressions.log',4)]:
    text=(qa/name).read_text()
    assert f'OK ({total} tests)' in text and 'FAILURES!!!' not in text and 'Process crashed' not in text,name
badging=(qa/'apk-badging.log').read_text();assert "versionCode='12'" in badging and "versionName='0.10.0'" in badging
certificate='8ba95f74fce4d7a85afc6126a6ee37b1d4c1baf8cecc4276038a0e3a256b087b'
assert certificate in (qa/'apk-signature.log').read_text()
native=json.loads((qa/'results/pico-native-validation.json').read_text())
playback=json.loads((qa/'results/pico-playback-validation.json').read_text())
service=json.loads((qa/'results/pico-service-validation.json').read_text())
assert native['cancelAndRegenerate'] and native['checkpointPreserved'] and playback['noEarlyCompletion'] and service['rateChangeKeptWaveform']
result=dict(version='0.10.0',versionCode=12,apkBytes=apk.stat().st_size,apkSha256=digest,
    certificateSha256=certificate,jvm=counts,lintErrors=0,lintWarnings=40,
    android=dict(device='Android 15 API35 x86_64 emulator',picoChecks=3,regressionAndPublicDownloadChecks=4),
    pico=dict(downloadBytes=pack['bytes'],installedBytes=pack['expandedBytes'],sha256=pack['sha256'],voices=5,
        native=native,playback=playback,service=service),
    existingNativeLibrariesUnchanged=12,newNativeBridges=4,newBridgeLoadAlignment=16384,
    modelsExcludedFromApk=True,physicalPhoneVerified=False,
    limitations='Experimental Danish voice. No physical S10/Fold7 or Bluetooth acceptance. Emulator timing and ASR do not certify phone throughput, battery, heat or narration quality.')
(out/'Folio-0.10.0-validation.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
