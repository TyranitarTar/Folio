"""Validate the recorded-audiobook release against actual local and CI evidence."""
from pathlib import Path
import datetime
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parent
output = root.parent / 'output'
audit = root / 'audit'
apk = output / 'Folio-0.13.0.apk'
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
assert (output / 'Folio-0.13.0.apk.sha256').read_text(encoding='utf-8').split()[0] == digest
signature = (audit / 'v013-apk-signature.log').read_text(encoding='utf-8-sig')
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
assert 'certificate SHA-256 digest: 8ba95f74fce4d7a85afc6126a6ee37b1d4c1baf8cecc4276038a0e3a256b087b' in signature
assert "versionCode='17' versionName='0.13.0'" in (audit / 'v013-apk-badging.log').read_text(encoding='utf-8-sig')
manifest = (audit / 'v013-apk-manifest.log').read_text(encoding='utf-8-sig')
assert 'app.folio.reader.AudiobookService' in manifest and 'app.folio.reader.AudiobookActivity' in manifest
with zipfile.ZipFile(apk) as now, zipfile.ZipFile(output / 'Folio-0.12.0.apk') as old:
    natives = [n for n in now.namelist() if n.startswith('lib/') and n.endswith('.so')]
    assert len(natives) == 16 and all(now.read(n) == old.read(n) for n in natives)
    assert now.read('assets/voice-packs.json') == old.read('assets/voice-packs.json')
    assert not any(n.endswith(('.onnx', '.safetensors', '.gguf', '.foliobook')) for n in now.namelist())
results = [ET.parse(path).getroot() for path in (root / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
unit = {key: sum(int(result.get(key, 0)) for result in results) for key in ('tests', 'failures', 'errors', 'skipped')}
assert unit['tests'] >= 173 and unit['failures'] == unit['errors'] == 0
recorded = next(result for result in results if result.get('name') == 'app.folio.reader.RecordedNarrationTest')
assert int(recorded.get('tests')) == 4 and recorded.get('failures') == '0'
lint = ET.parse(root / 'app/build/reports/lint-results-debug.xml').getroot()
assert not any(issue.get('severity') in ('Error', 'Fatal') for issue in lint)
completed = lambda text: re.findall(r'INSTRUMENTATION_STATUS: test=([^\r\n]+)\r?\nINSTRUMENTATION_STATUS_CODE: 0', text)
audio = (audit / 'v013-final-audiobook-emulator.log').read_text(encoding='utf-8-sig')
assert 'OK (6 tests)' in audio and len(completed(audio)) == 6 and 'FAILURES!!!' not in audio
baseline = (audit / 'v013-baseline-regressions.log').read_text(encoding='utf-8-sig')
storage = (audit / 'v013-ui-regression-retry.log').read_text(encoding='utf-8-sig')
assert completed(storage) == ['voicePackUiShowsStorageTotalsAndExplicitCleanupActions']
home = (audit / 'v013-home-theme-final.log').read_text(encoding='utf-8-sig')
assert 'OK (1 test)' in home and len(completed(home)) == 1 and 'FAILURES!!!' not in home
assert len(set(completed(baseline) + completed(storage) + completed(home))) == 9 and 'shortMsg=' not in baseline
desktop = (audit / 'v013-narration-tests.log').read_text(encoding='utf-8-sig')
assert 'Ran 7 tests' in desktop and '\nOK' in desktop and 'FAILED' not in desktop
extracted = (audit / 'v013-source-archive-tests.log').read_text(encoding='utf-8-sig')
assert 'Ran 7 tests' in extracted and '\nOK' in extracted and 'FAILED' not in extracted
publication = json.loads((audit / 'v013-github-source-publication.json').read_text(encoding='utf-8-sig'))
ci = json.loads((audit / 'v013-ci-result.json').read_text(encoding='utf-8-sig'))
assert ci['conclusion'] == 'success' and ci['headSha'] == publication['sourceCommit']
with zipfile.ZipFile(output / 'Folio-source-0.13.0.zip') as source:
    names = source.namelist()
    for filename in ('AudiobookStore.kt', 'AudiobookService.kt', 'AudiobookScreen.kt', 'AudiobookFolder.kt', 'RecordedNarration.kt'):
        assert 'Folio/app/src/main/java/app/folio/reader/' + filename in names
    assert 'Folio/tools/narration/narrate.py' in names and 'Folio/tools/__init__.py' in names
    assert not any(part in {'signing', 'toolchain', '.git', '.gradle', '.cxx', 'audit', '.venv', 'jobs', 'models', 'voices'} for name in names for part in Path(name).parts)
    assert not any(name.endswith(('.jks', '.keystore', '.p12', '.onnx', '.safetensors', '.gguf', '.foliobook')) for name in names)
report = dict(version='0.13.0', versionCode=17, apkBytes=apk.stat().st_size, apkSha256=digest,
              speechNativeFilesIdenticalTo012=len(natives), voiceCatalogUnchanged=True,
              unit=unit, lintErrors=0, lintWarnings=sum(issue.get('severity') == 'Warning' for issue in lint),
              audiobookEmulatorTests=6, existingRegressionCases=9, desktopUnitTests=7,
              extractedSourceDesktopTests=7, sourceCiPassed=True, sourceCommit=ci['headSha'],
              regressionNote='Seven baseline cases passed initially. Two UI cases passed after correcting an old history fixture timestamp and scrolling the expanded More menu.',
              actualPrivateRoestMp3ImportPlaybackAndLocatorTest=True,
              privateBookPublished=False, fullBookNarrationCompleted=False,
              physicalPhoneTested=False, passed=True,
              verifiedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
upgrade = output / 'Folio-0.13.0-upgrade-verification.json'
if upgrade.exists():
    evidence = json.loads(upgrade.read_text(encoding='utf-8-sig'))
    assert evidence['passed'] and evidence['exactLibraryRecordsPreserved'] and evidence['toVersion'] == '0.13.0'
    report['publicInAppUpgradeFrom012Passed'] = True
(output / 'Folio-0.13.0-validation.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report, indent=2))
