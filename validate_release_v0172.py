"""Qualify the signed 0.17.2 artifacts against actual local test evidence."""
from pathlib import Path
import datetime,hashlib,json,re,subprocess,xml.etree.ElementTree as ET,zipfile

r=Path(__file__).resolve().parent
out=r.parent/'output';audit=r/'audit'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
apk=out/'Folio-0.17.2.apk';previous=out/'Folio-0.17.1.apk'
assert apk.is_file() and sha(apk)==sha(r/'app/build/outputs/apk/release/app-release.apk')
bt=r/'toolchain/sdk/build-tools/35.0.0'
java=next((r/'toolchain/jdk').glob('*/bin/java.exe'))
def signature(path):
    text=subprocess.check_output([str(java),'-jar',str(bt/'lib/apksigner.jar'),'verify','--print-certs',str(path)],text=True)
    return re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)',text).group(1)
assert signature(apk)==signature(previous)
badging=subprocess.check_output([str(bt/'aapt.exe'),'dump','badging',str(apk)],text=True,encoding='utf8',errors='replace')
assert "versionCode='25'" in badging and "versionName='0.17.2'" in badging
assert "package: name='app.folio.reader'" in badging
with zipfile.ZipFile(apk) as now,zipfile.ZipFile(previous) as old:
    assert now.testzip() is None
    assert now.read('assets/voice-packs.json')==old.read('assets/voice-packs.json')
    assert not any(n.endswith(('.onnx','.safetensors','.gguf','.foliobook')) for n in now.namelist())
    # Native rebuilds may vary only in the GNU build-id; retain the qualified normalizer.
    def normalized(data):
        found=[];offset=0
        while True:
            index=data.find(b'GNU\x00',offset)
            if index<0:break
            if index>=12:
                import struct
                namesz,descsz,kind=struct.unpack_from('<III',data,index-12)
                if namesz==4 and descsz in (16,20,32) and kind==3 and index+4+descsz<=len(data):found.append((index+4,index+4+descsz))
            offset=index+4
        value=bytearray(data)
        for start,end in found:value[start:end]=b'\x00'*(end-start)
        return bytes(value)
    natives=[n for n in now.namelist() if n.startswith('lib/') and n.endswith('.so')]
    assert natives and all(normalized(now.read(n))==normalized(old.read(n)) for n in natives)
roots=[ET.parse(p).getroot() for p in (r/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
unit={k:sum(int(t.get(k,0)) for t in roots) for k in ('tests','failures','errors','skipped')}
assert unit['tests']>=224 and unit['failures']==unit['errors']==0
lint=ET.parse(r/'app/build/reports/lint-results-debug.xml').getroot()
assert not any(i.get('severity') in ('Error','Fatal') for i in lint)
passed=set()
for name in ('v0172-accepted-instrumentation.log','v0172-notebook-ui-accepted.log','v0172-process-death-setup.log','v0172-process-death-verify.log'):
    text=(audit/name).read_text(encoding='utf-8-sig')
    assert 'FAILURES!!!' not in text and 'shortMsg=' not in text and re.search(r'OK \(\d+ tests?\)',text),name
    cls=test=None
    for line in text.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: class='):cls=line.split('=',1)[1]
        if line.startswith('INSTRUMENTATION_STATUS: test='):test=line.split('=',1)[1]
        if line=='INSTRUMENTATION_STATUS_CODE: 0':passed.add(f'{cls}#{test}')
assert len(passed)>=36
assert sum('Notebook0172InstrumentedTest#' in v for v in passed)==4
assert any('Audio017SnapshotInstrumentedTest#' in v for v in passed)
tested=json.loads((audit/'v0172-tested-apks.json').read_text(encoding='utf-8'))
assert tested['release']==sha(apk)
assert tested['debug']==sha(r/'app/build/outputs/apk/debug/app-debug.apk')
assert tested['test']==sha(r/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
upgrade=json.loads((audit/'v0172-upgrade-release/result.json').read_text(encoding='utf-8-sig'))
assert upgrade['passed'] and upgrade['checkedFiles']>=8 and not upgrade['changedFiles']
assert upgrade['apkSha256']==sha(apk) and upgrade['installedApkMatchesFinalBuild'] and upgrade['noClearOrUninstall']
with zipfile.ZipFile(out/'Folio-source-0.17.2.zip') as source:
    names=source.namelist()
    for name in ('BookNotebook.kt','BookNotebookActivity.kt','NotebookTextEditor.kt','NotebookEditing.kt','ReleaseHistory.kt','AppUpdates.kt','AppUpdatesPanel.kt','AudioJournal.kt','AudiobookStore.kt','AudiobookService.kt',
                 'AudiobookListeningScreen.kt','AudiobookScreen.kt','LiveSpeechBenchmark.kt','PicoBenchmarks.kt','SpeechBufferConfiguration.kt'):
        key='app/src/main/java/app/folio/reader/'+name
        assert source.read('Folio/'+key)==(r/key).read_bytes()
    assert all('Folio/'+name in names for name in ('IMPLEMENTATION-v0.17.2.md','ROADMAP-0.17.2.md','validate_release_v0172.py','release_notes.py'))
    assert not any(part in {'signing','toolchain','.git','.gradle','.cxx','audit','.venv','jobs','models','voices'} for n in names for part in Path(n).parts)
    assert not any(n.endswith(('.jks','.keystore','.p12','.onnx','.safetensors','.gguf','.foliobook','.apk')) for n in names)
with zipfile.ZipFile(apk) as bundle:
    catalog=json.loads(bundle.read('assets/release-notes.json'))
    assert catalog['releases'][0]['version']=='0.17.2'
    assert bundle.read('assets/release-notes.json')==(r/'app/src/main/assets/release-notes.json').read_bytes()
from release_notes import render
assert (audit/'v0172-release-notes.md').read_text(encoding='utf-8')==render('0.17.2')
report=dict(version='0.17.2',versionCode=25,passed=True,apkSha256=sha(apk),apkBytes=apk.stat().st_size,
    signingCertificateSha256=signature(apk),unit=unit,lintErrors=0,lintWarnings=sum(i.get('severity')=='Warning' for i in lint),
    emulatorCases=sorted(passed),focusedEmulatorTests=len(passed),notebookUiAndMigrationQualified=True,
    signedReleaseUpgrade=upgrade,voiceCatalogUnchanged=True,nativeFilesUnchangedApartFromBuildId=len(natives),
    physicalPhoneTested=False,publishedToGitHub=False,verifiedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
    limits=['Physical-phone sustained playback, Bluetooth, calls, heat and battery remain acceptance checks.',
            'An OS kill before a write reaches disk, or full storage preventing both notebook and draft, cannot guarantee unsaved text recovery.',
            'Legacy written timestamps remain original text; notebooks are not acoustically remapped during a snapshot update.',
            'Editor selection/undo survive Activity recreation; actual process loss reopens saved text at a fresh final line.',
            'No new phone voice-speed result is claimed; installed models and native runtimes are unchanged.',
            'No Apple TV hardware or third-party AirPlay bridge was tested; casting remains a future item.'])
(out/'Folio-0.17.2-validation.json').write_text(json.dumps(report,indent=2),encoding='utf8')
print(json.dumps({k:report[k] for k in ('version','passed','apkBytes','unit','focusedEmulatorTests')},indent=2))
