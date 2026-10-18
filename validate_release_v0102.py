"""Verify the signed 0.10.2 app, unchanged voice packs and actual device evidence."""
from pathlib import Path
import datetime,hashlib,json,re,struct,xml.etree.ElementTree as ET,zipfile

root=Path(__file__).resolve().parent
output=root.parent/'output';tools=root.parent/'tools/pico'
apk=output/'Folio-0.10.2.apk'
digest=hashlib.sha256(apk.read_bytes()).hexdigest()
assert (output/'Folio-0.10.2.apk.sha256').read_text().split()[0]==digest
signer='8ba95f74fce4d7a85afc6126a6ee37b1d4c1baf8cecc4276038a0e3a256b087b'
signature=(tools/'apk-signature-v0102.log').read_text(encoding='utf-8-sig')
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature and f'certificate SHA-256 digest: {signer}' in signature
badging=(tools/'apk-badging-v0102.log').read_text(encoding='utf-8-sig')
assert "versionCode='14' versionName='0.10.2'" in badging
with zipfile.ZipFile(apk) as now,zipfile.ZipFile(output/'Folio-0.10.1.apk') as old:
    natives=[n for n in now.namelist() if n.startswith('lib/') and n.endswith('.so')]
    bridges=[n for n in natives if n.endswith('/libfolio_pico.so')]
    assert len(natives)==16 and len(bridges)==4
    assert all(now.read(n)==old.read(n) for n in natives if n not in bridges)
    assert all(now.read(n)!=old.read(n) for n in bridges)
    for name in bridges:
        raw=now.read(name);is64=raw[4]==2
        offset=struct.unpack_from('<Q' if is64 else '<I',raw,32 if is64 else 28)[0]
        size,count=struct.unpack_from('<HH',raw,54 if is64 else 42)
        loads=[struct.unpack_from('<Q' if is64 else '<I',raw,offset+i*size+(48 if is64 else 28))[0]
               for i in range(count) if struct.unpack_from('<I',raw,offset+i*size)[0]==1]
        assert loads and min(loads)>=16384,(name,loads)
    assert now.read('assets/voice-packs.json')==old.read('assets/voice-packs.json')
    packs=json.loads(now.read('assets/voice-packs.json'))['packs']
    assert len(packs)==3 and all(p['url'].startswith('https://github.com/TyranitarTar/Folio/releases/download/') for p in packs)
    assert not any(n.endswith(('.onnx','.safetensors','.gguf','/embeddings.bin')) for n in now.namelist())
with zipfile.ZipFile(output/'Folio-source-0.10.2.zip') as source:
    names=source.namelist()
    for name in ['PicoBenchmarks.kt','VoiceStorage.kt','SpeechPassages.kt']:
        assert 'Folio/app/src/main/java/app/folio/reader/'+name in names
    assert 'Folio/IMPLEMENTATION-v0.10.2.md' in names
    assert not any(part in {'signing','toolchain','.git','.gradle','.cxx','audit'} for n in names for part in Path(n).parts)
    assert not any(n.endswith(('.jks','.keystore','.p12','.onnx','.safetensors','.gguf')) for n in names)
results=[ET.parse(p).getroot() for p in (root/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
unit={k:sum(int(r.get(k,0)) for r in results) for k in ['tests','failures','errors','skipped']}
assert unit['tests']>=153 and unit['failures']==unit['errors']==0
skipped=[(r.get('name'),t.get('name')) for r in results for t in r.findall('testcase') if t.find('skipped') is not None]
assert all(item==('app.folio.reader.VoiceStorageTest','symlinkCannotEscapePrivateVoiceRoot') for item in skipped)
lint=ET.parse(root/'app/build/reports/lint-results-debug.xml').getroot()
assert not any(i.get('severity') in ('Error','Fatal') for i in lint)
device=tools/'results/v0102/s10'
for suite,count in [('native',4),('threads',1),('continuity',1),('prepare',1),('storage',2)]:
    text=(device/(suite+'.log')).read_text(encoding='utf-8-sig')
    assert re.search(rf'OK \({count} tests?\)',text) and 'FAILURES!!!' not in text,suite
    assert text.count('INSTRUMENTATION_STATUS_CODE: 0')==count and 'INSTRUMENTATION_STATUS_CODE: -4' not in text,suite
benchmark=json.loads((device/'pico-thread-benchmark.json').read_text())
assert {t['threads'] for t in benchmark['trials']}=={1,2,4,6,8} and len(benchmark['trials'])==10
assert len(benchmark['grouping'])==6
continuity=json.loads((device/'pico-continuity-validation.json').read_text())
events=continuity['events'];starts=[e for e in events if e['kind']=='player.presented-start']
ends={e['id']:e for e in events if e['kind']=='player.presented-end'}
windows=[(e['nanos'],ends[e['id']]['nanos']) for e in starts]
overlap=sum(any(a<=e['nanos']<=b for a,b in windows) for e in events if e['kind']=='pico.synthesis.start')
assert continuity['passages']==20 and overlap==continuity['generationStartsDuringPlayback'] and overlap>0
prepared=json.loads((device/'pico-prepare-ahead-validation.json').read_text())
assert prepared['bookmarkPreserved'] and prepared['resumeSynthesisCalls']==0 and prepared['completedPassages']==3
prior=json.loads((root/'audit/v0101-real-upgrade-result.json').read_text())
assert prior['passed'] and prior['toVersion']=='0.10.1'
report=dict(version='0.10.2',versionCode=14,apkBytes=apk.stat().st_size,sha256=digest,signerCertificateSha256=signer,
    unchangedNativeLibraries=12,newProfiledPicoBridges=4,newBridgeLoadAlignment=16384,modelCatalogUnchanged=True,modelDownloadRequired=False,
    unit=unit,unitPassed=unit['tests']-unit['skipped'],unitSkipped=skipped,lintWarnings=len(lint),
    physicalDevice=benchmark['device'],picoThreadBenchmark=benchmark,
    benchmarkEnvironment=json.loads((device/'threads-environment.json').read_text()),
    continuity={k:v for k,v in continuity.items() if k!='events'},prepareAhead=prepared,
    androidS10Checks=9,linkedPathSafety='Also exercised on real Android; Windows host symlink case may skip',
    priorFullPublicUpgrade=prior,pending=['Flip7 measurements','Bluetooth routes','unplugged battery life','human Danish listening acceptance'],
    verifiedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())
for name,count in [('v0102-emulator-regressions.log',3),('v0102-kokoro-regression.log',1)]:
    log=(root/'audit'/name).read_text(encoding='utf-8-sig')
    assert re.search(rf'OK \({count} tests?\)',log) and log.count('INSTRUMENTATION_STATUS_CODE: 0')==count
    assert 'FAILURES!!!' not in log and 'INSTRUMENTATION_STATUS_CODE: -4' not in log
report['androidEmulatorRegressionChecks']=4
cancel=ET.parse(root/'audit/v0102-benchmark-cancelled.xml')
assert any(n.get('text')=='Benchmark cancelled.' for n in cancel.iter('node'))
report['foregroundBenchmarkBackgroundCancellation']='Verified through emulator UI: background, wait for STOP, reopen and observe Benchmark cancelled.'
upgrade=root/'audit/v0102-upgrade-result.json'
if upgrade.exists():
    report['fullPublicUpgrade']=json.loads(upgrade.read_text());assert report['fullPublicUpgrade']['passed']
(output/'Folio-0.10.2-validation.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k not in ['picoThreadBenchmark','continuity']},indent=2))
