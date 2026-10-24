"""Local release gates; every test claim comes from this version's evidence."""
from pathlib import Path
import datetime,hashlib,json,re,zipfile,struct
import xml.etree.ElementTree as ET

root=Path(__file__).resolve().parent
output=root.parent/'output';audit=root/'audit'
apk=output/'Folio-0.14.0.apk'
digest=hashlib.sha256(apk.read_bytes()).hexdigest()
assert (output/'Folio-0.14.0.apk.sha256').read_text().split()[0]==digest
signature=(audit/'v014-apk-signature.log').read_text(encoding='utf-8-sig')
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
assert 'certificate SHA-256 digest: 8ba95f74fce4d7a85afc6126a6ee37b1d4c1baf8cecc4276038a0e3a256b087b' in signature
assert "versionCode='18' versionName='0.14.0'" in (audit/'v014-apk-badging.log').read_text(encoding='utf-8-sig')
def without_build_id(data):
    binary=bytearray(data);wide=binary[4]==2
    offset=struct.unpack_from('<Q' if wide else '<I',binary,40 if wide else 32)[0]
    size,count,names=struct.unpack_from('<HHH',binary,58 if wide else 46)
    def section(index):
        at=offset+index*size
        label=struct.unpack_from('<I',binary,at)[0]
        start,length=struct.unpack_from('<QQ' if wide else '<II',binary,at+(24 if wide else 16))
        return label,start,length
    _,start,length=section(names);strings=binary[start:start+length]
    for index in range(count):
        label,start,length=section(index)
        if bytes(strings[label:]).split(b'\0',1)[0]==b'.note.gnu.build-id':
            assert struct.unpack_from('<III',binary,start)==(4,20,3) and binary[start+12:start+16]==b'GNU\0'
            binary[start+16:start+36]=b'\0'*20
    return bytes(binary)
with zipfile.ZipFile(apk) as now,zipfile.ZipFile(output/'Folio-0.13.0.apk') as old:
    natives=[n for n in now.namelist() if n.startswith('lib/') and n.endswith('.so')]
    assert len(natives)==16 and all(without_build_id(now.read(n))==without_build_id(old.read(n)) for n in natives)
    byte_identical=sum(now.read(n)==old.read(n) for n in natives)
    assert byte_identical==12
    assert all(n.endswith('/libfolio_pico.so') for n in natives if now.read(n)!=old.read(n))
    assert now.read('assets/voice-packs.json')==old.read('assets/voice-packs.json')
    assert not any(n.endswith(('.onnx','.safetensors','.gguf','.foliobook')) for n in now.namelist())
results=[ET.parse(p).getroot() for p in (root/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
unit={k:sum(int(r.get(k,0)) for r in results) for k in ('tests','failures','errors','skipped')}
assert unit['tests']>=179 and unit['failures']==unit['errors']==0
lint=ET.parse(root/'app/build/reports/lint-results-debug.xml').getroot()
assert not any(i.get('severity') in ('Error','Fatal') for i in lint)
def instrumentation(name,count):
    text=(audit/name).read_text(encoding='utf-8-sig')
    assert re.search(r'OK \('+str(count)+r' tests?\)',text) and 'FAILURES!!!' not in text and 'shortMsg=' not in text
    return count
interface=instrumentation('v014-final-interface.log',5)
regressions=instrumentation('v014-final-regressions.log',10)
home=instrumentation('v014-home-settled.log',2)
upgrade=json.loads((audit/'v014-upgrade/result.json').read_text())
folder=json.loads((audit/'v014-folder/result.json').read_text())
live=json.loads((audit/'v014-live-benchmark.json').read_text())
assert upgrade['passed'] and upgrade['checkedFiles']==28 and not upgrade['changedFiles']
assert folder['passed'] and folder['nestedCdOrdering'] and folder['nonzeroCursorRetained']
assert live['generationOverlappedPlayback']>0 and live['queueWaits']>0 and live['appliedPlaybackRate']==1.5
studio=json.loads((output/'FolioStudio-0.3.0/VALIDATION.json').read_text())
assert studio['passed'] and studio['authoredRealPicoExport']['passed']
with zipfile.ZipFile(output/'Folio-source-0.14.0.zip') as z:
    names=z.namelist()
    for filename in ('AudiobookSources.kt','ChapterPagination.kt','LiveSpeechBenchmark.kt'):
        assert 'Folio/app/src/main/java/app/folio/reader/'+filename in names
    assert 'Folio/design/icons-0.14/2-bookmark-cat.svg' in names
    assert not any(part in {'signing','toolchain','.git','.gradle','.cxx','audit','.venv','jobs','models','voices'} for name in names for part in Path(name).parts)
    assert not any(n.endswith(('.jks','.keystore','.p12','.onnx','.safetensors','.gguf','.foliobook')) for n in names)
report=dict(version='0.14.0',versionCode=18,passed=True,apkSha256=digest,apkBytes=apk.stat().st_size,
    unit=unit,lintErrors=0,lintWarnings=sum(i.get('severity')=='Warning' for i in lint),
    interfaceEmulatorTests=interface,regressionEmulatorCases=regressions,homeEmulatorTests=home,
    speechNativeFilesIdenticalExceptGnuBuildId=len(natives),speechNativeFilesByteIdentical=byte_identical,picoBuildIdOnlyChanges=4,voiceCatalogUnchanged=True,
    upgrade=upgrade,signedReleaseUpgrade=json.loads((audit/'v014-upgrade/release-install.json').read_text()),actualSafFolderDiscovery=folder,liveAudioBenchmark=live,
    homeTestNote='Automated theme tap originally stopped an unfinished Compose scroll; test now waits for scrolling to settle and verifies the saved setting.',
    authoredRealPicoFoliobookPlayed=True,launcherIcon='2 — Bookmark Cat',
    physicalPhoneTested=False,fullGpuSpeechBackendAdded=False,publishedToGitHub=False,
    limits=['Emulator checks do not certify phone speed/heat/battery/Bluetooth.',
            'Queue waits are callback starvation measurements, not acoustic gap measurements.',
            'Unusual TOCs, image-only chapter endings and revoked provider permissions need broader acceptance.',
            'No full GPU-resident speech pipeline qualified; production hybrids unchanged.'],
    verifiedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
(output/'Folio-0.14.0-validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps({k:report[k] for k in ('version','passed','apkBytes','unit','interfaceEmulatorTests','regressionEmulatorCases','homeEmulatorTests')},indent=2))
