"""0.16 release gates. Evidence must come from this build and isolated emulator."""
from pathlib import Path
import datetime, hashlib, json, re, struct, zipfile
import xml.etree.ElementTree as ET

root=Path(__file__).resolve().parent
out=root.parent/'output';audit=root/'audit';apk=out/'Folio-0.16.0.apk'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
digest=sha(apk)
assert (out/'Folio-0.16.0.apk.sha256').read_text().split()[0]==digest
signature=(audit/'v016-apk-signature.log').read_text(encoding='utf-8-sig')
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
assert 'certificate SHA-256 digest: 8ba95f74fce4d7a85afc6126a6ee37b1d4c1baf8cecc4276038a0e3a256b087b' in signature
assert "versionCode='21' versionName='0.16.0'" in (audit/'v016-apk-badging.log').read_text(encoding='utf-8-sig')

def without_build_id(data):
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

with zipfile.ZipFile(apk) as now,zipfile.ZipFile(out/'Folio-0.15.0.apk') as old:
    natives=[n for n in now.namelist() if n.startswith('lib/') and n.endswith('.so')]
    assert len(natives)==16
    assert all(without_build_id(now.read(n))==without_build_id(old.read(n)) for n in natives)
    identical=sum(now.read(n)==old.read(n) for n in natives)
    assert now.read('assets/voice-packs.json')==old.read('assets/voice-packs.json')
    assert not any(n.endswith(('.onnx','.safetensors','.gguf','.foliobook')) for n in now.namelist())
results=[ET.parse(p).getroot() for p in (root/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
unit={k:sum(int(r.get(k,0)) for r in results) for k in ('tests','failures','errors','skipped')}
assert unit['tests']>=206 and unit['failures']==unit['errors']==0
lint=ET.parse(root/'app/build/reports/lint-results-debug.xml').getroot()
assert not any(i.get('severity') in ('Error','Fatal') for i in lint)
log=(audit/'v016-qualified-instrumentation.log').read_text(encoding='utf-8-sig')
assert 'FAILURES!!!' not in log and 'shortMsg=' not in log
assert re.search(r'OK \(\d+ tests?\)',log)
passed=set();cls=test=None
for line in log.splitlines():
    if line.startswith('INSTRUMENTATION_STATUS: class='):cls=line.split('=',1)[1]
    if line.startswith('INSTRUMENTATION_STATUS: test='):test=line.split('=',1)[1]
    if line=='INSTRUMENTATION_STATUS_CODE: 0':passed.add(f'{cls}#{test}')
assert len(passed)>=15
assert 'app.folio.reader.Audio016InstrumentedTest#progress_is_read_only_skips_cross_files_and_chapter_seek_requires_apply' in passed
assert 'app.folio.reader.Audio016InstrumentedTest#transientFocusResumesButManualPauseAndPermanentLossDoNot' in passed
assert 'app.folio.reader.Library016InstrumentedTest#librariesHaveTopAlignedGridsAndFullWidthMenus' in passed
qualification=json.loads((audit/'v016-emulator-qualification.json').read_text())
assert qualification['passed'] and qualification['installedApkMatchesFinalBuild']
assert qualification['debugApkSha256']==sha(root/'app/build/outputs/apk/debug/app-debug.apk')
screens=audit/'v016-screens'
player=json.loads((screens/'v016-player.json').read_text());focus=json.loads((screens/'v016-focus.json').read_text())
library=json.loads((screens/'v016-library.json').read_text())
assert all(player.values()) and all(focus.values()) and library['passed']
upgrade=json.loads((audit/'v016-upgrade-release/result.json').read_text(encoding='utf-8-sig'))
assert upgrade['passed'] and upgrade['checkedFiles']>=14 and not upgrade['changedFiles'] and upgrade['noClearOrUninstall']
with zipfile.ZipFile(out/'Folio-source-0.16.0.zip') as source:
    names=source.namelist()
    for name in ('AudioPlaybackPolicy.kt','AudioSettings.kt','SourceDeletion.kt','AudiobookListeningScreen.kt','LibraryStatusPager.kt'):
        assert 'Folio/app/src/main/java/app/folio/reader/'+name in names
    for name in ('ROADMAP-0.16.md','IMPLEMENTATION-v0.16.md','validate_release_v016.py'):
        assert 'Folio/'+name in names
    assert not any(part in {'signing','toolchain','.git','.gradle','.cxx','audit','.venv','jobs','models','voices'} for n in names for part in Path(n).parts)
    assert not any(n.endswith(('.jks','.keystore','.p12','.onnx','.safetensors','.gguf','.foliobook','.apk')) for n in names)
    with zipfile.ZipFile(out/'Folio-source-0.15.0.zip') as old_source:
        cafe=[n for n in names if n.startswith('Folio/app/src/main/java/app/folio/reader/') and Path(n).name.startswith(('Cafe','Miso')) and n.endswith('.kt')]
        assert cafe and all(source.read(n)==old_source.read(n) for n in cafe)
report=dict(version='0.16.0',versionCode=21,passed=True,apkSha256=digest,apkBytes=apk.stat().st_size,
    unit=unit,lintErrors=0,lintWarnings=sum(i.get('severity')=='Warning' for i in lint),
    focusedEmulatorTests=len(passed),emulatorCases=sorted(passed),emulatorQualification=qualification,
    speechNativeFilesIdenticalExceptGnuBuildId=len(natives),speechNativeFilesByteIdentical=identical,
    voiceCatalogUnchanged=True,productionCafeFilesUnchanged=len(cafe),signedReleaseUpgrade=upgrade,
    player=player,focus=focus,library=library,physicalPhoneTested=False,publishedToGitHub=False,
    limits=['Physical Bluetooth, notification and call behavior still require phone acceptance.',
            'Audio focus cannot detect apps that refuse to request it; GPU/voice throughput is unchanged.',
            'Unaligned audio gaps do not invent EPUB positions; audio and text percentages are different measures.',
            'Older copied imports may not retain an original source URI. Provider deletion cannot be rolled back.',
            'Verified whole-folder deletion needs Android 10+ and provider support; shared/ambiguous roots are protected.',
            'The exact user Mars Trilogy files/covers have not been tested on the phone.',
            'Exact uncached page totals still require layout of unknown resources.'],
    verifiedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
(out/'Folio-0.16.0-validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps({k:report[k] for k in ('version','passed','apkBytes','unit','focusedEmulatorTests')},indent=2))
