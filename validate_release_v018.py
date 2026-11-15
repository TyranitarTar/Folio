"""Verify the local 0.18 artifacts against actual, recorded café test evidence."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
OUT = ROOT.parent / 'output'
AUDIT = ROOT / 'audit'
ASSETS = ROOT / 'app/src/main/assets/cafe'
sha = lambda data: hashlib.sha256(data).hexdigest()

apk = OUT / 'Folio-0.18.0.apk'
assert sha(apk.read_bytes()) == sha((ROOT / 'app/build/outputs/apk/release/app-release.apk').read_bytes())
java = next((ROOT / 'toolchain/jdk').glob('*/bin/java.exe'))
bt = ROOT / 'toolchain/sdk/build-tools/35.0.0'
def signature(path):
    text = subprocess.check_output([str(java), '-jar', str(bt / 'lib/apksigner.jar'), 'verify', '--print-certs', str(path)], text=True)
    return re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)', text).group(1)
assert signature(apk) == signature(OUT / 'Folio-0.17.2.apk')
badging = subprocess.check_output([str(bt / 'aapt.exe'), 'dump', 'badging', str(apk)], text=True, encoding='utf8', errors='replace')
assert "versionCode='27'" in badging and "versionName='0.18.0'" in badging
assert "package: name='app.folio.reader'" in badging
with zipfile.ZipFile(apk) as archive, zipfile.ZipFile(OUT / 'Folio-0.19.0.apk') as previous:
    for name in previous.namelist():
        if name.startswith('assets/cafe/'): assert archive.read(name) == previous.read(name)
    assert archive.testzip() is None
    assert not any('Folio_Miso_Handoff' in n or 'FOLIO_MISO_' in n for n in archive.namelist())
    for file in ASSETS.rglob('*'):
        if file.is_file(): assert archive.read('assets/cafe/' + file.relative_to(ASSETS).as_posix()) == file.read_bytes()
    notes = json.loads(archive.read('assets/release-notes.json'))
    assert notes['releases'][0]['version'] == '0.18.0'
provenance = json.loads((ASSETS / 'provenance.json').read_text())
assert provenance['frames'] == 289 and provenance['clips'] == 34 and provenance['catScale'] == 1.3
for file in provenance['files']: assert sha((ASSETS / file['asset']).read_bytes()) == file['sha256']
roots = [ET.parse(p).getroot() for p in (ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
unit = {key: sum(int(r.get(key, 0)) for r in roots) for key in ('tests', 'failures', 'errors', 'skipped')}
assert unit['tests'] >= 227 and unit['failures'] == unit['errors'] == 0
lint = ET.parse(ROOT / 'app/build/reports/lint-results-debug.xml').getroot()
assert not any(i.get('severity') in ('Error', 'Fatal') for i in lint)
device = (AUDIT / 'v019-device-final.log').read_text(encoding='utf-8-sig')
assert 'OK (4 tests)' in device and 'FAILURES!!!' not in device
legacy = (AUDIT / 'v019-legacy-device.log').read_text(encoding='utf-8-sig')
assert 'OK (2 tests)' in legacy and 'FAILURES!!!' not in legacy
assert 'PASS:' in (AUDIT / 'v019-host-check.log').read_text(encoding='utf-8-sig')
upgrade = json.loads((AUDIT / 'v018-upgrade/result.json').read_text())
assert upgrade['byteIdenticalAfterSignedInstallation'] and not upgrade['uninstalledOrCleared']
assert upgrade['apkSha256'] == sha(apk.read_bytes())
with zipfile.ZipFile(OUT / 'Folio-source-0.18.0.zip') as source:
    baselineSource = zipfile.ZipFile(OUT / 'Folio-source-0.19.0.zip')
    for name in ('CafeRoomView.kt', 'CafeRoomScreen.kt'):
        relative = 'app/src/main/java/app/folio/reader/' + name
        assert source.read('Folio/' + relative) == (ROOT / relative).read_bytes()
        assert source.read('Folio/' + relative) == baselineSource.read('Folio/' + relative)
    assert source.read('Folio/game/VALIDATION.md') == (ROOT / 'game/VALIDATION.md').read_bytes()
report = {'passed':True, 'version':'0.18.0', 'versionCode':27, 'apkSha256':sha(apk.read_bytes()),
          'sameSigningCertificateAs0172':True, 'unit':unit, 'lintErrors':0,
          'cafeEmulatorTests':4, 'legacyCafeEmulatorTests':2,
          'unchangedRuntimeFiles':len(provenance['files']),
          'upgradeDurableFilesPreserved':upgrade['checkedDurableFiles'],
          'physicalPhoneTested':False}
report['behaviorQualification'] = 'Unchanged room code/art from locally tested 0.19.0; renumbered to requested 0.18.0.'
smoke = (AUDIT / 'v018-smoke.log').read_text(encoding='utf-8-sig')
assert 'OK (1 test)' in smoke and 'FAILURES!!!' not in smoke
report['releaseEmulatorSmokeTests'] = 1
(OUT / 'Folio-0.18.0-validation.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
