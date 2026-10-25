"""Release gates for 0.15.0, using this build's local evidence only."""
from pathlib import Path
import datetime, hashlib, json, re, struct, zipfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent
output = root.parent / 'output'
audit = root / 'audit'
apk = output / 'Folio-0.15.0.apk'
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
assert (output / 'Folio-0.15.0.apk.sha256').read_text().split()[0] == digest
signature = (audit / 'v015-apk-signature.log').read_text(encoding='utf-8-sig')
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
assert 'certificate SHA-256 digest: 8ba95f74fce4d7a85afc6126a6ee37b1d4c1baf8cecc4276038a0e3a256b087b' in signature
assert "versionCode='20' versionName='0.15.0'" in (audit / 'v015-apk-badging.log').read_text(encoding='utf-8-sig')

def without_build_id(data):
    binary = bytearray(data)
    wide = binary[4] == 2
    offset = struct.unpack_from('<Q' if wide else '<I', binary, 40 if wide else 32)[0]
    size, count, names = struct.unpack_from('<HHH', binary, 58 if wide else 46)
    def section(index):
        at = offset + index * size
        label = struct.unpack_from('<I', binary, at)[0]
        start, length = struct.unpack_from('<QQ' if wide else '<II', binary, at + (24 if wide else 16))
        return label, start, length
    _, start, length = section(names)
    strings = binary[start:start+length]
    for index in range(count):
        label, start, length = section(index)
        if bytes(strings[label:]).split(b'\0', 1)[0] == b'.note.gnu.build-id':
            assert struct.unpack_from('<III', binary, start) == (4, 20, 3)
            binary[start+16:start+36] = b'\0' * 20
    return bytes(binary)

with zipfile.ZipFile(apk) as now, zipfile.ZipFile(output / 'Folio-0.14.1.apk') as old:
    natives = [n for n in now.namelist() if n.startswith('lib/') and n.endswith('.so')]
    assert len(natives) == 16
    assert all(without_build_id(now.read(n)) == without_build_id(old.read(n)) for n in natives)
    byte_identical = sum(now.read(n) == old.read(n) for n in natives)
    assert now.read('assets/voice-packs.json') == old.read('assets/voice-packs.json')
    assert not any(n.endswith(('.onnx', '.safetensors', '.gguf', '.foliobook')) for n in now.namelist())
results = [ET.parse(p).getroot() for p in (root / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
unit = {k: sum(int(r.get(k, 0)) for r in results) for k in ('tests', 'failures', 'errors', 'skipped')}
assert unit['tests'] >= 195 and unit['failures'] == unit['errors'] == 0
lint = ET.parse(root / 'app/build/reports/lint-results-debug.xml').getroot()
assert not any(i.get('severity') in ('Error', 'Fatal') for i in lint)
instrumentation = (audit / 'v015-qualified-instrumentation.log').read_text(encoding='utf-8-sig')
assert re.search(r'OK \(28 tests\)',instrumentation) and 'FAILURES!!!' not in instrumentation and 'shortMsg=' not in instrumentation
recorded = (audit / 'v015-recorded-fixture-instrumentation.log').read_text(encoding='utf-8-sig')
assert re.search(r'OK \(1 test\)',recorded) and 'FAILURES!!!' not in recorded
recorded_proof=json.loads((audit/'v015-screens/v015-recorded-fixture.json').read_text(encoding='utf-8'))
assert recorded_proof['passed'] and recorded_proof['realGeneratedAudioPlayed'] and recorded_proof['hybridTextLinked']
qualification = json.loads((audit / 'v015-emulator-qualification.json').read_text(encoding='utf-8'))
assert qualification['passed'] and qualification['uniqueCasesPassed']==28 and qualification['installedApkMatchesFinalBuild']
folder = json.loads((audit / 'v015-root-scan.json').read_text(encoding='utf-8'))
assert folder['passed'] and folder['copiedAudioBytes'] == 0 and folder['linkedTracks'] == 2
assert folder['decoderDurationMs'] > 0 and folder['cursorAndBookmarkRetained']
pages = json.loads((audit / 'v015-screens/v015-pagination.json').read_text(encoding='utf-8'))
assert pages['callbacks'] == 40 and pages['verifiedBefore'] == pages['verifiedAfter']
reference = json.loads((audit / 'v015-screens/pagination-v071-timings.json').read_text(encoding='utf-8'))
assert reference['equivalent'] and reference['exactCounts'] == reference['referenceCounts']
m4b = json.loads((audit / 'v015-screens/v015-m4b.json').read_text(encoding='utf-8'))
assert all(m4b[k] for k in ('passed','artworkCached','pausedNavigationPreserved','historyPersisted','bookmarkPreserved','autoRewindPassed'))
assert m4b['chapterStarts'] == [0,6000,12000]
controls = json.loads((audit / 'v015-screens/v015-update-controls.json').read_text(encoding='utf-8'))
assert controls['passed'] and len(controls['buttonTopPositions'])==5
assert max(controls['buttonTopPositions'])-min(controls['buttonTopPositions'])<20
assert controls['releaseNotesCharacters']>5000
upgrade = json.loads((audit / 'v015-upgrade-debug/result.json').read_text(encoding='utf-8-sig'))
signed_upgrade = json.loads((audit / 'v015-upgrade-release/result.json').read_text(encoding='utf-8-sig'))
for evidence, count in ((upgrade, 127), (signed_upgrade, 14)):
    assert evidence['passed'] and evidence['checkedFiles'] == count and not evidence['changedFiles']
    assert evidence['noClearOrUninstall']
with zipfile.ZipFile(output / 'Folio-source-0.15.0.zip') as source:
    names = source.namelist()
    for filename in ('AudioChapters.kt', 'AudioArtwork.kt', 'AudioFolderDiscovery.kt', 'AudiobookListeningScreen.kt', 'LibraryStatusPager.kt'):
        assert 'Folio/app/src/main/java/app/folio/reader/' + filename in names
    for filename in ('SMART-AUDIOBOOK-COMPARISON.md', 'IMPLEMENTATION-v0.15.md', 'validate_release_v015.py'):
        assert 'Folio/' + filename in names
    assert not any(part in {'signing', 'toolchain', '.git', '.gradle', '.cxx', 'audit', '.venv', 'jobs', 'models', 'voices'} for name in names for part in Path(name).parts)
    assert not any(n.endswith(('.jks', '.keystore', '.p12', '.onnx', '.safetensors', '.gguf', '.foliobook', '.apk')) for n in names)
    with zipfile.ZipFile(output/'Folio-source-0.14.1.zip') as old_source:
        cafe_files=[n for n in names if n.startswith('Folio/app/src/main/java/app/folio/reader/') and Path(n).name.startswith(('Cafe','Miso')) and n.endswith('.kt')]
        assert cafe_files and all(source.read(n)==old_source.read(n) for n in cafe_files)
report = dict(version='0.15.0', versionCode=20, passed=True, apkSha256=digest, apkBytes=apk.stat().st_size,
    unit=unit, lintErrors=0, lintWarnings=sum(i.get('severity') == 'Warning' for i in lint),
    focusedEmulatorTests=28, emulatorQualification=qualification, speechNativeFilesIdenticalExceptGnuBuildId=len(natives),
    speechNativeFilesByteIdentical=byte_identical, voiceCatalogUnchanged=True,
    upgrade=upgrade, signedReleaseUpgrade=signed_upgrade,
    actualSafFolderDiscovery=folder, pagination=pages, chapteredM4b=m4b, pinnedUpdateControls=controls,
    independentPaginationCounts=reference['referenceCounts'],
    authoredRealPicoFoliobookPlayed=True,recordedFixture=recorded_proof,productionCafeFilesUnchanged=len(cafe_files), physicalPhoneTested=False,
    smartPlayerReference='Static API/component inspection only; supplied APK not installed or benchmarked.',
    limits=['Emulator checks do not certify S10/Flip7 speed, heat, battery or Bluetooth.',
            'Folder timing uses a small authored fixture, not a large library.',
            'Linked audio requires its selected source/provider to remain available.',
            'FolioBook and explicit file imports still copy/extract media.',
            'Exact uncached page totals still require layout of unknown resources.',
            'M4B support covers tested Nero/QuickTime chapter metadata, not every vendor/edit-list variant.',
            'Physical public-update installation and longer device/provider acceptance are separate from emulator qualification.'],
    publishedToGitHub=False, verifiedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
(output / 'Folio-0.15.0-validation.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps({k: report[k] for k in ('version', 'passed', 'apkBytes', 'unit', 'focusedEmulatorTests')}, indent=2))
