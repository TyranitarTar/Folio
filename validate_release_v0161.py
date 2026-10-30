"""Qualify the signed 0.16.1 artifact against evidence from this build."""
from pathlib import Path
import datetime,hashlib,json,re,struct,zipfile
import xml.etree.ElementTree as ET
r=Path(__file__).resolve().parent;out=r.parent/'output';audit=r/'audit'
apk=out/'Folio-0.16.1.apk'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
digest=sha(apk)
assert (out/'Folio-0.16.1.apk.sha256').read_text().split()[0]==digest
signature=(audit/'v0161-apk-signature.log').read_text(encoding='utf-8-sig')
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
assert 'certificate SHA-256 digest: 8ba95f74fce4d7a85afc6126a6ee37b1d4c1baf8cecc4276038a0e3a256b087b' in signature
assert "versionCode='22' versionName='0.16.1'" in (audit/'v0161-apk-badging.log').read_text(encoding='utf-8-sig')
assert 'BUILD SUCCESSFUL' in (audit/'v0161-build-final.log').read_text(encoding='utf-8-sig')

def normalized(data):
    binary=bytearray(data);wide=binary[4]==2
    offset=struct.unpack_from('<Q' if wide else '<I',binary,40 if wide else 32)[0]
    size,count,names=struct.unpack_from('<HHH',binary,58 if wide else 46)
    def section(index):
        at=offset+index*size;label=struct.unpack_from('<I',binary,at)[0]
        start,length=struct.unpack_from('<QQ' if wide else '<II',binary,at+(24 if wide else 16))
        return label,start,length
    _,start,length=section(names);strings=binary[start:start+length]
    for index in range(count):
        label,start,length=section(index)
        if bytes(strings[label:]).split(b'\0',1)[0]==b'.note.gnu.build-id':
            assert struct.unpack_from('<III',binary,start)==(4,20,3)
            binary[start+16:start+36]=b'\0'*20
    return bytes(binary)

with zipfile.ZipFile(apk) as now,zipfile.ZipFile(out/'Folio-0.16.0.apk') as old:
    natives=[n for n in now.namelist() if n.startswith('lib/') and n.endswith('.so')]
    assert len(natives)==16 and all(normalized(now.read(n))==normalized(old.read(n)) for n in natives)
    identical=sum(now.read(n)==old.read(n) for n in natives)
    assert now.read('assets/voice-packs.json')==old.read('assets/voice-packs.json')
    assert not any(n.endswith(('.onnx','.safetensors','.gguf','.foliobook')) for n in now.namelist())
results=[ET.parse(p).getroot() for p in (r/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
unit={k:sum(int(t.get(k,0)) for t in results) for k in ('tests','failures','errors','skipped')}
assert unit['tests']>=210 and unit['failures']==unit['errors']==0
lint=ET.parse(r/'app/build/reports/lint-results-debug.xml').getroot()
assert not any(i.get('severity') in ('Error','Fatal') for i in lint)
passed=set()
for name in ['v0161-focused-instrumentation.log','v0161-regression-instrumentation.log']:
    log=(audit/name).read_text(encoding='utf-8-sig')
    assert 'FAILURES!!!' not in log and 'shortMsg=' not in log and re.search(r'OK \(\d+ tests?\)',log)
    cls=test=None
    for line in log.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: class='):cls=line.split('=',1)[1]
        if line.startswith('INSTRUMENTATION_STATUS: test='):test=line.split('=',1)[1]
        if line=='INSTRUMENTATION_STATUS_CODE: 0':passed.add(f'{cls}#{test}')
assert sum('Audio0161InstrumentedTest#' in name for name in passed)==3
assert 'app.folio.reader.Audio016InstrumentedTest#transientFocusResumesButManualPauseAndPermanentLossDoNot' in passed
qualification=json.loads((audit/'v0161-emulator-qualification.json').read_text())
assert qualification['passed'] and qualification['installedApkMatchesFinalBuild']
assert qualification['debugApkSha256']==sha(r/'app/build/outputs/apk/debug/app-debug.apk')
upgrade=json.loads((audit/'v0161-upgrade-release/result.json').read_text(encoding='utf-8-sig'))
assert upgrade['passed'] and upgrade['checkedFiles']>=14 and not upgrade['changedFiles'] and upgrade['noClearOrUninstall']
assert upgrade['installedApkMatchesFinalBuild'] and upgrade['apkSha256']==digest
with zipfile.ZipFile(out/'Folio-source-0.16.1.zip') as source:
    names=source.namelist()
    for path in ('AudioPlayerPolicy.kt','AudioPlayerAwake.kt','AudioPlayerDialogs.kt','SavedQuotes.kt','AudiobookListeningScreen.kt','AudiobookService.kt','LibraryStore.kt'):
        name='Folio/app/src/main/java/app/folio/reader/'+path
        assert name in names and source.read(name)==(r/name.removeprefix('Folio/')).read_bytes()
    assert all('Folio/'+n in names for n in ('IMPLEMENTATION-v0.16.1.md','ENGLISH-LIVE-READALONG-IDEA.md','validate_release_v0161.py'))
    assert not any(part in {'signing','toolchain','.git','.gradle','.cxx','audit','.venv','jobs','models','voices'} for n in names for part in Path(n).parts)
    assert not any(n.endswith(('.jks','.keystore','.p12','.onnx','.safetensors','.gguf','.foliobook','.apk')) for n in names)
report=dict(version='0.16.1',versionCode=22,passed=True,apkSha256=digest,apkBytes=apk.stat().st_size,
    unit=unit,lintErrors=0,lintWarnings=sum(i.get('severity')=='Warning' for i in lint),
    focusedEmulatorTests=len(passed),emulatorCases=sorted(passed),emulatorQualification=qualification,
    speechNativeFilesIdenticalExceptGnuBuildId=len(natives),speechNativeFilesByteIdentical=identical,
    voiceCatalogUnchanged=True,signedReleaseUpgrade=upgrade,physicalPhoneTested=False,publishedToGitHub=False,
    limits=['Physical-phone Bluetooth, sustained listening, battery and heat remain acceptance checks.',
        'Player lock protects on-screen controls; headset and system playback remain available.',
        'Live English speech recognition is an evaluated idea only; no recognition model or microphone feature added.',
        'Studio live FolioBook output and shared queue remain separate backlog tasks.',
        'Exact user Mars Trilogy files/covers and unusual providers remain device acceptance cases.'],
    verifiedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
(out/'Folio-0.16.1-validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps({k:report[k] for k in ('version','passed','apkBytes','unit','focusedEmulatorTests')},indent=2))
