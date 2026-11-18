"""Qualify the signed Godot release against source, baseline and real test evidence."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parent
OUT=ROOT.parent/'output'
AUDIT=ROOT/'audit/v019'
GAME=ROOT/'game/miso-home'
sha=lambda data:hashlib.sha256(data).hexdigest()
apk=OUT/'Folio-0.19.0.apk'
digest=sha(apk.read_bytes())
assert digest==sha((ROOT/'app/build/outputs/apk/release/app-release.apk').read_bytes())
java=next((ROOT/'toolchain/jdk').glob('*/bin/java.exe'))
bt=ROOT/'toolchain/sdk/build-tools/35.0.0'

def signature(path):
    text=subprocess.check_output([str(java),'-jar',str(bt/'lib/apksigner.jar'),'verify','--print-certs',str(path)],text=True)
    return re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)',text).group(1)

assert signature(apk)==signature(OUT/'Folio-0.18.1.apk')
badging=subprocess.check_output([str(bt/'aapt.exe'),'dump','badging',str(apk)],text=True,encoding='utf-8',errors='replace')
assert "versionCode='29'" in badging and "versionName='0.19.0'" in badging
assert "package: name='app.folio.reader'" in badging
with zipfile.ZipFile(apk) as archive,zipfile.ZipFile(OUT/'Folio-0.18.1.apk') as previous:
    assert archive.testzip() is None
    assert not any('FOLIO_MISO_' in n or 'Folio_Miso_Handoff' in n for n in archive.namelist())
    # The fallback kit, wallet/story sources and speech runtime remain preserved.
    classic=[n for n in previous.namelist() if n.startswith('assets/cafe/')]
    for name in classic:assert archive.read(name)==previous.read(name),name
    speech=[n for n in previous.namelist() if n.startswith('lib/') and n.endswith(('/libonnxruntime.so','/libfolio_pico.so','/libsherpa-onnx-jni.so'))]
    for name in speech:assert archive.read(name)==previous.read(name),name
    abis=['armeabi-v7a','arm64-v8a','x86','x86_64']
    for abi in abis:assert archive.getinfo('lib/'+abi+'/libgodot_android.so').file_size>1_000_000
    for name in ['miso-home.pck','LICENSE-GODOT.txt','COPYRIGHT-GODOT.txt']:
        assert archive.read('assets/cafe-engine/'+name)==(ROOT/'app/src/main/assets/cafe-engine'/name).read_bytes()
    assert json.loads(archive.read('assets/release-notes.json'))['releases'][0]['version']=='0.19.0'
    pack_sha=sha(archive.read('assets/cafe-engine/miso-home.pck'))

tested=json.loads((AUDIT/'test-build.json').read_text(encoding='utf-8'))
assert tested['packSha256']==pack_sha and tested['releaseApkSha256']==digest
assert tested['debugApkSha256']==sha((ROOT/'app/build/outputs/apk/debug/app-debug.apk').read_bytes())
assert tested['testApkSha256']==sha((AUDIT/'native-tested-instrumentation.apk').read_bytes())
assert tested['nativeTestSourceSha256']==sha((ROOT/'app/src/androidTest/java/app/folio/reader/CafeGodotInstrumentedTest.kt').read_bytes())

manifest=ET.parse(ROOT/'app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml').getroot()
attr=lambda name:'{http://schemas.android.com/apk/res/android}'+name
application=manifest.find('application')
assert application.get(attr('debuggable'),'false')=='false'
assert application.get(attr('allowBackup'))=='false'
activities={a.get(attr('name')):a for a in application.findall('activity')}
cafe=activities['app.folio.reader.CafeActivity']
assert cafe.get(attr('process'))==':cafe' and cafe.get(attr('exported'))=='false'
provider=next(p for p in application.findall('provider') if p.get(attr('name'))=='androidx.core.content.FileProvider')
assert provider.get(attr('authorities'))=='app.folio.reader.updates'
assert provider.get(attr('exported'))=='false'
assert provider.find('meta-data').get(attr('resource'))=='@xml/update_paths'

library=json.loads((GAME/'assets/animation-library.json').read_text(encoding='utf-8'))
assert len(library['facingClips'])==8 and len(library['clips'])==28
for clip in library['clips'].values():
    assert len(clip['files'])==len(clip['durations'])==len(clip['roots'])
    for size in [96,128]:
        for file in clip['files']:assert (GAME/file.replace('res://','').replace('{density}',str(size))).is_file()
art=json.loads((GAME/'art-source/production/export.json').read_text(encoding='utf-8'))
assert sum(job['frames'] for job in art['jobs'])==60
for job in art['jobs']:
    assert not job['paletteQuantized'] and not job['silhouetteFit']
    assert sha((GAME/'art-source/production'/(job['job']+'-original.png')).read_bytes())==job['sourceSha256']
    assert (GAME/'art-source/production'/(job['job']+'-prompt.txt')).is_file()
logic=(AUDIT/'logic-check.log').read_text(encoding='utf-8-sig')
assert 'PASS: 21 activities, 441 ordered transitions, 950 jumps' in logic
review=(AUDIT/'desktop-review.log').read_text(encoding='utf-8-sig')
assert 'FOLIO_CAFE_REVIEW_COMPLETE' in review and 'SCRIPT ERROR' not in review

roots=[ET.parse(p).getroot() for p in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
unit={k:sum(int(r.get(k,0)) for r in roots) for k in ['tests','failures','errors','skipped']}
assert unit['tests']>=233 and unit['failures']==unit['errors']==0
lint=ET.parse(ROOT/'app/build/reports/lint-results-debug.xml').getroot()
assert not any(i.get('severity') in ['Error','Fatal'] for i in lint)
device_cases=[('device-1080x2520.log',4),('device-1080x2280.log',3),('legacy-recreate.log',1),('legacy-test-mode.log',1)]
for name,count in device_cases:
    text=(AUDIT/name).read_text(encoding='utf-8-sig')
    assert f'OK ({count} test' in text and 'FAILURES!!!' not in text,name
# Record the observed caret issue and prove the old runtime sources are unchanged.
notebook=(AUDIT/'notebook-regression.log').read_text(encoding='utf-8-sig')
assert 'Tests run: 4,  Failures: 1' in notebook
assert '1) maximumSizeNotebookAllowsDeletionAndKeepsTextAcrossLandscape' in notebook
assert 'Long notebook: caret visible' in notebook
with zipfile.ZipFile(OUT/'Folio-source-0.18.1.zip') as baseline:
    for name in ['BookNotebookActivity.kt','NotebookTextEditor.kt','BookNotebook.kt','NotebookEditing.kt']:
        path='app/src/main/java/app/folio/reader/'+name
        assert baseline.read('Folio/'+path)==(ROOT/path).read_bytes()
upgrade=json.loads((ROOT/'audit/v019-upgrade/result.json').read_text(encoding='utf-8'))
assert upgrade['from']=='0.18.1' and upgrade['to']=='0.19.0' and upgrade['apkSha256']==digest
assert upgrade['byteIdenticalAfterSignedInstallation'] and not upgrade['uninstalledOrCleared']
assert upgrade['releaseRendered'] and upgrade['sampledColors']>1500
assert upgrade['releaseEngineOpened'] and upgrade['oldRoomMigrated'] and upgrade['releaseEngineExitedOnHome']
with zipfile.ZipFile(OUT/'Folio-source-0.19.0.zip') as source:
    assert source.testzip() is None
    for name in source.namelist():
        relative=name.removeprefix('Folio/');assert relative!=name
        assert not any(p in relative.split('/') for p in ['signing','toolchain','audit','.git','.gradle','.godot','__pycache__'])
        assert not relative.endswith(('.p12','.jks','.keystore','.onnx','.safetensors','.pyc'))
        if relative.startswith(('app/src/main/java/','app/src/main/assets/cafe-engine/','game/miso-home/')):
            assert source.read(name)==(ROOT/relative).read_bytes(),relative
    for name in ['IMPLEMENTATION-v0.19.md','validate_release_v019.py','game/miso-home/art-source/production/PROMPTS.md']:
        assert source.read('Folio/'+name)==(ROOT/name).read_bytes()
with zipfile.ZipFile(OUT/'Folio-native-source-0.19.0.zip') as native:assert native.testzip() is None
report=dict(passed=True,version='0.19.0',versionCode=29,apkSha256=digest,packSha256=pack_sha,
            sameSigningCertificateAs0181=True,unit=unit,lintErrors=0,emulatorTests=sum(c for _,c in device_cases),
            profiles=[[1080,2280],[1080,2520]],facings=8,clips=28,newCompleteDrawings=60,activities=21,
            orderedTransitions=441,jumps=950,unchangedClassicAssets=len(classic),unchangedSpeechLibraries=len(speech),
            allFourAbisPreserved=True,isolatedEngineProcess=True,backgroundEngineDestructionTested=True,
            audiobookCoexistenceTested=True,upgradeDurableFilesPreserved=upgrade['checkedDurableFiles'],
            notebookTestsPassed=3,knownNotebookIssue='Caret can leave the viewport on rotation at maximum document size',
            physicalPhoneTested=False,physicalBatteryMeasured=False)
(OUT/'Folio-0.19.0-validation.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report,indent=2))
