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
    assert any('ic_folio_cat' in n for n in now.namelist())
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
              cafeEmulatorTests=3, passed=True, physicalPhoneTested=False,
              verifiedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
(output / 'Folio-0.12.0-validation.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report, indent=2))
