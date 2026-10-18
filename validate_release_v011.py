"""Validate the signed release and recorded real tests; never infer phone performance."""
from pathlib import Path
import datetime, hashlib, json, re, struct, xml.etree.ElementTree as ET, zipfile

root = Path(__file__).resolve().parent
output = root.parent / 'output'
research = root.parent / 'tools/plapre3/results'
audit = root / 'audit'

def read(path):
    return path.read_text(encoding='utf-8-sig')

def data(path):
    return json.loads(read(path))

def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(4 << 20), b''):
            h.update(block)
    return h.hexdigest()

apk = output / 'Folio-0.11.0.apk'
digest = sha(apk)
assert digest == 'fb61633c5d81fab25b469b6028a1cb98ef6996b6d472f1ab0650513e71aa6f5c'
assert read(output / 'Folio-0.11.0.apk.sha256').split()[0] == digest
signer = '8ba95f74fce4d7a85afc6126a6ee37b1d4c1baf8cecc4276038a0e3a256b087b'
signature = read(audit / 'v011-apk-signature.log')
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
assert f'certificate SHA-256 digest: {signer}' in signature
assert "versionCode='15' versionName='0.11.0'" in read(audit / 'v011-apk-badging.log')
with zipfile.ZipFile(apk) as now, zipfile.ZipFile(output / 'Folio-0.10.2.apk') as old:
    natives = [n for n in now.namelist() if n.startswith('lib/') and n.endswith('.so')]
    bridges = [n for n in natives if n.endswith('/libfolio_pico.so')]
    assert len(natives) == 16 and len(bridges) == 4
    assert all(now.read(n) == old.read(n) for n in natives if n not in bridges)
    assert all(now.read(n) != old.read(n) for n in bridges)
    for name in bridges:
        raw = now.read(name)
        is64 = raw[4] == 2
        offset = struct.unpack_from('<Q' if is64 else '<I', raw, 32 if is64 else 28)[0]
        size, count = struct.unpack_from('<HH', raw, 54 if is64 else 42)
        loads = [struct.unpack_from('<Q' if is64 else '<I', raw, offset+i*size+(48 if is64 else 28))[0]
                 for i in range(count) if struct.unpack_from('<I', raw, offset+i*size)[0] == 1]
        assert loads and min(loads) >= 16384, (name, loads)
    packs = json.loads(now.read('assets/voice-packs.json'))['packs']
    previous = json.loads(old.read('assets/voice-packs.json'))['packs']
    assert len(packs) == 4 and all(p in packs for p in previous)
    assert all(p['url'].startswith('https://github.com/TyranitarTar/Folio/releases/download/') for p in packs)
    pack = next(p for p in packs if p['backend'] == 'plapre3')
    assert not any(n.endswith(('.onnx', '.safetensors', '.gguf', '/embeddings.bin')) for n in now.namelist())
archive = output / 'voice-packs-plapre3-v1/Folio-Voice-Plapre3-FP32-v1.zip'
verified_pack = data(research / 'pack-validation.json')
assert verified_pack['passed'] and verified_pack['existingThreePackIdentitiesUnchanged']
assert sha(archive) == verified_pack['archiveSha256'] == pack['sha256']
assert archive.stat().st_size == verified_pack['archiveBytes'] == pack['bytes']
assert verified_pack['expandedBytes'] == pack['expandedBytes']
assert verified_pack['verifiedFiles'] == len(pack['files']) == 8
assert verified_pack['verifiedSamples'] == len(pack['voices']) == 5
published = data(audit / 'v011-model-public-assets.json')
assert not published['draft'] and published['prerelease'] and len(published['assets']) == 7
for asset in published['assets']:
    local = archive.parent / asset['name']
    assert local.stat().st_size == asset['size'] and 'sha256:' + sha(local) == asset['digest']
with zipfile.ZipFile(output / 'Folio-source-0.11.0.zip') as source:
    names = source.namelist()
    for name in ['Plapre3Model.kt', 'Plapre3PackIdentity.kt', 'PicoBenchmarks.kt', 'VoiceStorage.kt']:
        assert 'Folio/app/src/main/java/app/folio/reader/' + name in names
    for name in ['IMPLEMENTATION-v0.11.md', 'validate_release_v011.py', 'verify_upgrade_v011.py', 'plapre3-tools/export_lm.py']:
        assert 'Folio/' + name in names
    assert not any(part in {'signing', 'toolchain', '.git', '.gradle', '.cxx', 'audit', 'reference-deps'} for n in names for part in Path(n).parts)
    assert not any(n.endswith(('.jks', '.keystore', '.p12', '.onnx', '.safetensors', '.gguf')) for n in names)
reference = data(research / 'lm-reference.json')
assert reference['passed'] and reference['ropeTheta'] == 10000
assert len(reference['prefillAndCachedDecode']) == 4
assert all(v['sameTopToken'] and v['maxAbsoluteLogitError'] < 1.6e-5 for v in reference['prefillAndCachedDecode'])
recognizer = data(research / 'samples-asr-reference.json')
assert len(recognizer) >= 5 and all(v['text'] == v['asr'] for v in recognizer[:5])
results = [ET.parse(p).getroot() for p in (root / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
unit = {k: sum(int(r.get(k, 0)) for r in results) for k in ['tests', 'failures', 'errors', 'skipped']}
assert unit['tests'] == 159 and unit['failures'] == unit['errors'] == 0
skipped = [(r.get('name'), t.get('name')) for r in results for t in r.findall('testcase') if t.find('skipped') is not None]
assert skipped == [('app.folio.reader.VoiceStorageTest', 'symlinkCannotEscapePrivateVoiceRoot')]
lint = ET.parse(root / 'app/build/reports/lint-results-debug.xml').getroot()
assert not any(i.get('severity') in ('Error', 'Fatal') for i in lint)
checks = 0
for folder, suites in [('emulator', [('native', 4), ('prepare', 1), ('continuity', 1), ('threads', 1), ('samples', 1), ('download', 1)]),
                       ('legacy-emulator', [('regressions', 7)])]:
    for suite, count in suites:
        text = read(research / 'android' / folder / (suite + '.log'))
        assert re.search(rf'OK \({count} tests?\)', text) and 'FAILURES!!!' not in text, suite
        assert text.count('INSTRUMENTATION_STATUS_CODE: 0') == count and 'INSTRUMENTATION_STATUS_CODE: -4' not in text, suite
        checks += count
device = research / 'android/emulator'
native = data(device / 'plapre3-native-validation.json')
assert native['cancelAndRegenerate'] and native['checkpointPreserved'] and native['samples'] > 24000
assert data(device / 'plapre3-playback-validation.json')['noEarlyCompletion']
service = data(device / 'plapre3-service-validation.json')
assert service['rateChangeKeptWaveform'] and service['passageAdvancedAfterPlayback']
voices = data(device / 'plapre3-padding-voices-validation.json')
assert len(voices) == 5 and all(v['samples'] > 24000 for v in voices)
prepared = data(device / 'plapre3-prepare-ahead-validation.json')
assert prepared['bookmarkPreserved'] and prepared['silentPreparation'] and prepared['resumeSynthesisCalls'] == 0 and prepared['completedPassages'] == 3
continuity = data(device / 'plapre3-continuity-validation.json')
events = continuity['events']
starts = [e for e in events if e['kind'] == 'player.presented-start']
ends = {e['id']: e for e in events if e['kind'] == 'player.presented-end'}
windows = [(e['nanos'], ends[e['id']]['nanos']) for e in starts]
overlap = sum(any(a <= e['nanos'] <= b for a, b in windows) for e in events if e['kind'] == 'plapre3.synthesis.start')
assert len(starts) == len(ends) == continuity['passages'] == 20
assert overlap == continuity['generationStartsDuringPlayback'] and overlap > 0
assert continuity['lookaheadQueuedBeforePreviousEnd'] >= 2
generation_starts = [e['nanos'] for e in events if e['kind'] == 'plapre3.synthesis.start']
generation_ends = [e['nanos'] for e in events if e['kind'] == 'plapre3.synthesis.end']
assert len(generation_starts) == len(generation_ends) == 20
generation_windows = list(zip(generation_starts, generation_ends))
interval_overlap = sum(any(a < y and b > x for x, y in windows) for a, b in generation_windows)
overlap_seconds = sum(max(0, min(b, y) - max(a, x)) for a, b in generation_windows for x, y in windows) / 1e9
assert interval_overlap > 0 and overlap_seconds > 0
benchmark = data(device / 'plapre3-thread-benchmark.json')
assert benchmark['backend'] == 'plapre3' and len(benchmark['trials']) == 5
assert {t['threads'] for t in benchmark['trials']} == {1, 2, 4, 6, 8}
download = data(device / 'v9-voice-download-validation.json')
assert len(download) == 1 and download[0]['pack'] == pack['id']
assert download[0]['resumedPartialBytes'] > 0 and download[0]['filesVerified'] == 8 and download[0]['nativeSamples'] > 24000
home = ET.parse(audit / 'v011-home.xml')
assert any(n.get('text') == 'Audiobooks' for n in home.iter('node'))
assert any(n.get('text') == 'Coming later · your audiobook library' for n in home.iter('node'))
ci = data(audit / 'v011-ci-status.json')
assert ci['status'] == 'completed' and ci['conclusion'] == 'success' and ci['head_sha'] == '6e0b183dde787785f3ffd8b2d164b90006d7ec49'
report = dict(version='0.11.0', versionCode=15, apkBytes=apk.stat().st_size, sha256=digest,
              signerCertificateSha256=signer, nativeLibraries=16, unchangedNativeLibraries=12,
              newBridgeLoadAlignment=16384, optionalPack=verified_pack,
              unit=unit, unitPassed=158, unitSkipped=skipped, lintErrors=0, lintWarnings=len(lint),
              androidEmulatorChecks=checks, modelReference=reference, ci=ci,
              native=native, playback=data(device / 'plapre3-playback-validation.json'), service=service,
              memoryPointObservation=data(audit / 'v011-emulator-memory-observation.json'),
              previewRecognizerMatches=5, humanListeningAccepted=False,
              prepareAhead=prepared, continuity={k: v for k, v in continuity.items() if k != 'events'},
              independentlyRecomputedConcurrency=dict(generationJobsOverlappingPlayback=interval_overlap,
                                                       overlappingSeconds=overlap_seconds),
              threadBenchmark=benchmark, publicPackDownload=download, audiobookHomePlaceholder=True,
              pending=['Physical Flip7/S10 Plapre 3 measurements', 'Human Danish listening acceptance',
                       'Physical Bluetooth, heat and unplugged battery checks', 'Audiobook playback', 'Joint UI cleanup'],
              verifiedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())
upgrade = audit / 'v011-upgrade-result.json'
publication = audit / 'v011-evidence-github-source-publication.json'
if publication.exists():
    report['evidenceSourcePublication'] = data(publication)
    assert report['evidenceSourcePublication']['parentCommit'] == ci['head_sha']
    assert report['evidenceSourcePublication']['existingFilesPreserved']
if upgrade.exists():
    report['fullPublicUpgrade'] = data(upgrade)
    assert report['fullPublicUpgrade']['passed'] and report['fullPublicUpgrade']['toVersion'] == '0.11.0'
else:
    report['pending'].append('Full public 0.10.2 to 0.11.0 in-app install and populated-library verification')
(output / 'Folio-0.11.0-validation.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({k: v for k, v in report.items() if k not in ['threadBenchmark', 'modelReference']}, indent=2))
