"""Check the delivered café APK against actual build and emulator evidence."""
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
apk = output / 'Folio-0.12.0.apk'
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
assert (output / 'Folio-0.12.0.apk.sha256').read_text(encoding='utf-8').split()[0] == digest
signature = (audit / 'v012-apk-signature.log').read_text(encoding='utf-8-sig')
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
assert 'certificate SHA-256 digest: 8ba95f74fce4d7a85afc6126a6ee37b1d4c1baf8cecc4276038a0e3a256b087b' in signature
assert "versionCode='16' versionName='0.12.0'" in (audit / 'v012-apk-badging.log').read_text(encoding='utf-8-sig')
with zipfile.ZipFile(apk) as now, zipfile.ZipFile(output / 'Folio-0.11.0.apk') as old:
    natives = [n for n in now.namelist() if n.startswith('lib/') and n.endswith('.so')]
    assert len(natives) == 16
    assert all(now.read(n) == old.read(n) for n in natives), 'Unexpected speech native change'
    assert now.read('assets/voice-packs.json') == old.read('assets/voice-packs.json')
    assert not any(n.endswith(('.onnx', '.safetensors', '.gguf', '/embeddings.bin')) for n in now.namelist())
resources = (audit / 'v012-apk-resources.log').read_text(encoding='utf-8-sig')
manifest = (audit / 'v012-apk-manifest.log').read_text(encoding='utf-8-sig')
for name, attribute in [('ic_folio_cat', 'icon'), ('ic_folio_cat_round', 'roundIcon')]:
    # Release optimization shortens ZIP filenames; check compiled resource identities.
    match = re.search(r'spec resource (0x[0-9a-f]+) app.folio.reader:mipmap/' + name + r':', resources)
    assert match and re.search(r'android:' + attribute + r'\(.*?\)=@' + match.group(1), manifest)
results = [ET.parse(path).getroot() for path in (root / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
unit = {key: sum(int(result.get(key, 0)) for result in results) for key in ('tests', 'failures', 'errors', 'skipped')}
assert unit['tests'] >= 169 and unit['failures'] == unit['errors'] == 0
for suite, expected in [('CafeStoreTest', 8), ('CafeRewardClockTest', 2)]:
    record = next(result for result in results if result.get('name') == 'app.folio.reader.' + suite)
    assert int(record.get('tests')) == expected and record.get('failures') == '0'
lint = ET.parse(root / 'app/build/reports/lint-results-debug.xml').getroot()
assert not any(issue.get('severity') in ('Error', 'Fatal') for issue in lint)
emulator = (audit / 'v012-cafe-emulator.log').read_text(encoding='utf-8')
assert 'OK (3 tests)' in emulator and emulator.count('INSTRUMENTATION_STATUS_CODE: 0') == 3
assert 'FAILURES!!!' not in emulator and 'shortMsg=' not in emulator
speech = (root.parent / 'tools/plapre3/results/android/v012-regression/regressions.log').read_text(encoding='utf-8-sig')
remaining = (audit / 'v012-final-regressions.log').read_text(encoding='utf-8-sig')
completed = lambda text: re.findall(r'INSTRUMENTATION_STATUS: test=([^\r\n]+)\r?\nINSTRUMENTATION_STATUS_CODE: 0', text)
assert len(completed(speech)) == 4 and 'FAILURES!!!' not in speech
assert 'OK (3 tests)' in remaining and len(completed(remaining)) == 3 and 'shortMsg=' not in remaining
assert len(set(completed(speech) + completed(remaining))) == 7
ci = json.loads((audit / 'v012-ci-result.json').read_text(encoding='utf-8-sig'))
assert ci['conclusion'] == 'success' and ci['headSha'] == 'f643abf70221ec57c00ebc10c162abf2a123ec24'
with zipfile.ZipFile(output / 'Folio-source-0.12.0.zip') as source:
    names = source.namelist()
    for filename in ('CafeStore.kt', 'CafeReadingRewards.kt', 'CafeStory.kt', 'CafeScreen.kt', 'CafeArtwork.kt'):
        assert 'Folio/app/src/main/java/app/folio/reader/' + filename in names
    assert 'Folio/IMPLEMENTATION-v0.12.md' in names
    assert not any(part in {'signing', 'toolchain', '.git', '.gradle', '.cxx', 'audit'} for name in names for part in Path(name).parts)
    assert not any(name.endswith(('.jks', '.keystore', '.p12', '.onnx', '.safetensors', '.gguf')) for name in names)
report = dict(version='0.12.0', versionCode=16, apkBytes=apk.stat().st_size, apkSha256=digest,
              speechNativeFilesIdenticalTo011=len(natives), voiceCatalogUnchanged=True,
              unit=unit, lintErrors=0, lintWarnings=sum(issue.get('severity') == 'Warning' for issue in lint),
              cafeEmulatorTests=3, existingRegressionCases=7, sourceCiPassed=True,
              regressionNote='Four speech cases completed before the tool-session interruption; three update/storage cases passed after resuming.',
              passed=True, physicalPhoneTested=False,
              verifiedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
(output / 'Folio-0.12.0-validation.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report, indent=2))
