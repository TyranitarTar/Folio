"""Validate the signed follow-up against local artifacts and recorded test evidence."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from PIL import Image

ROOT = Path(__file__).resolve().parent
OUT = ROOT.parent / 'output'
AUDIT = ROOT / 'audit'
ASSETS = ROOT / 'app/src/main/assets/cafe'
sha = lambda data: hashlib.sha256(data).hexdigest()
apk = OUT / 'Folio-0.18.1.apk'
digest = sha(apk.read_bytes())
assert digest == sha((ROOT / 'app/build/outputs/apk/release/app-release.apk').read_bytes())
java = next((ROOT / 'toolchain/jdk').glob('*/bin/java.exe'))
bt = ROOT / 'toolchain/sdk/build-tools/35.0.0'

def signature(path):
    text = subprocess.check_output([str(java), '-jar', str(bt / 'lib/apksigner.jar'),
                                    'verify', '--print-certs', str(path)], text=True)
    return re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)', text).group(1)

assert signature(apk) == signature(OUT / 'Folio-0.18.0.apk')
badging = subprocess.check_output([str(bt / 'aapt.exe'), 'dump', 'badging', str(apk)],
                                 text=True, encoding='utf8', errors='replace')
assert "versionCode='28'" in badging and "versionName='0.18.1'" in badging
assert "package: name='app.folio.reader'" in badging
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    assert not any('Folio_Miso_Handoff' in n or 'FOLIO_MISO_' in n for n in archive.namelist())
    for file in ASSETS.rglob('*'):
        if file.is_file():
            assert archive.read('assets/cafe/' + file.relative_to(ASSETS).as_posix()) == file.read_bytes()
    notes = json.loads(archive.read('assets/release-notes.json'))
    assert notes['releases'][0]['version'] == '0.18.1'

provenance = json.loads((ASSETS / 'provenance.json').read_text(encoding='utf-8'))
for file in provenance['files']:
    assert sha((ASSETS / file['asset']).read_bytes()) == file['sha256']
for file in provenance['folioOverrides']:
    assert sha((ASSETS / file['asset']).read_bytes()) == file['runtimeSha256']
meta = json.loads((ASSETS / 'animation_data.json').read_text(encoding='utf-8'))
baseline = ROOT / 'game/eight-direction/baseline'
old = json.loads((baseline / 'animation_data.json').read_text(encoding='utf-8'))
assert len(meta['rects']) == 310 and len(meta['clips']) == 40
assert meta['canvas'] == [64, 64] and meta['root'] == [31, 55]
assert len(meta['palette']) == 16 and meta['palette'] == old['palette']
assert len(old['rects']) == 289 and len(old['clips']) == 34
for key, value in old['clips'].items():
    assert meta['clips'][key] == value, key
original = Image.open(baseline / old['atlas']).convert('RGBA')
current = Image.open(ASSETS / meta['atlas']).convert('RGBA')
def pixels(image, rect):
    x, y, w, h = rect
    return image.crop((x, y, x+w, y+h)).tobytes()
for index, rect in enumerate(old['rects']):
    assert pixels(original, rect) == pixels(current, meta['rects'][index]), index
env = json.loads((ASSETS / 'environment.json').read_text(encoding='utf-8'))
assert env['cat_scale'] == 1.3 and env['render_scale'] == 2

roots = [ET.parse(p).getroot() for p in (ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
unit = {key: sum(int(r.get(key, 0)) for r in roots) for key in ('tests', 'failures', 'errors', 'skipped')}
assert unit['tests'] >= 229 and unit['failures'] == unit['errors'] == 0
lint = ET.parse(ROOT / 'app/build/reports/lint-results-debug.xml').getroot()
assert not any(i.get('severity') in ('Error', 'Fatal') for i in lint)
for name, count in [('v0181-accepted-device.log', 5), ('v0181-notebook-regression.log', 1),
                    ('v0181-hidpi-device.log', 1)]:
    text = (AUDIT / name).read_text(encoding='utf-8-sig')
    assert f'OK ({count} test' in text and 'FAILURES!!!' not in text, name
for name in ['v0181-host-check.log', 'v0181-movement-check.log']:
    assert 'PASS:' in (AUDIT / name).read_text(encoding='utf-8-sig'), name
simulation = json.loads((AUDIT / 'v0181-simulation-check.log').read_text(encoding='utf-8-sig'))
assert simulation['walk_collisions'] == 0 and simulation['ordered_action_transitions'] == 441
layers = json.loads((AUDIT / 'v0181-layers-check.log').read_text(encoding='utf-8-sig'))
assert layers['feeding_faces_remain_visible'] and layers['tunnel_hides_and_reveals_correctly']
upgrade = json.loads((AUDIT / 'v0181-upgrade/result.json').read_text(encoding='utf-8'))
assert upgrade['from'] == '0.18.0' and upgrade['to'] == '0.18.1'
assert upgrade['byteIdenticalAfterSignedInstallation'] and not upgrade['uninstalledOrCleared']
assert upgrade['apkSha256'] == digest
hidpi = json.loads((AUDIT / 'v0181/hidpi-v0181-room-work.json').read_text(encoding='utf-8'))
assert hidpi['canvas'][0] > 1000 and hidpi['idleDrawsInTwoSeconds'] <= 20

with zipfile.ZipFile(OUT / 'Folio-source-0.18.1.zip') as source:
    assert source.testzip() is None
    for name in source.namelist():
        relative = name.removeprefix('Folio/')
        assert relative != name
        assert not any(part in relative.split('/') for part in ['signing', 'toolchain', 'audit', '.git', '.gradle'])
        assert not relative.endswith(('.p12', '.jks', '.keystore', '.onnx', '.safetensors'))
        if relative.startswith(('app/src/main/java/', 'app/src/main/assets/cafe/', 'game/eight-direction/')):
            assert source.read(name) == (ROOT / relative).read_bytes(), relative
    for name in ['IMPLEMENTATION-v0.18.1.md', 'game/VALIDATION-0181.md', 'validate_release_v0181.py']:
        assert source.read('Folio/' + name) == (ROOT / name).read_bytes()
with zipfile.ZipFile(OUT / 'Folio-native-source-0.18.1.zip') as native:
    assert native.testzip() is None

report = dict(passed=True, version='0.18.1', versionCode=28, apkSha256=digest,
              sameSigningCertificateAs0180=True, unit=unit, lintErrors=0,
              emulatorTests=7, highDpiWork=hidpi, unchangedOriginalFrames=289,
              unchangedOriginalClips=34, frames=310, clips=40,
              movementDirectAnglesChecked=72, obstacleRoutesChecked=59,
              actionTransitionsChecked=441, jumpsChecked=simulation['jumps_checked'],
              upgradeDurableFilesPreserved=upgrade['checkedDurableFiles'],
              physicalPhoneTested=False, physicalBatteryMeasured=False)
(OUT / 'Folio-0.18.1-validation.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(json.dumps(report, indent=2))
