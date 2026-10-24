"""Opt-in real-model release checks, using authored text only and one process at a time."""
from pathlib import Path
import hashlib
import html
import json
import os
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.narration.core import create_job, load_json, save_json, validate_manifest
from tools.studio.processes import active_narrators, keep_awake, live_lease

AUDIT = ROOT / 'android-reader/audit/studio-0.2.0'


def authored_epubs():
    from tools.narration.benchmark_parallel import DA, EN
    extra = {'da': 'Senere fandt hun katten sovende mellem to puder. Hun hentede sin bog og satte sig ved siden af ham, mens regnen igen begyndte at falde udenfor.',
             'en': 'Later that evening she found the cat asleep between two cushions. She brought her book and sat beside him as the rain began to fall again.'}
    for language, texts in (('da', DA), ('en', EN)):
        texts = texts + [extra[language]]
        assert sum(len(text.split()) for text in texts) >= 200
        body = ''.join(f'<p id="p{i}">{html.escape(text)}</p>' for i,text in enumerate(texts))
        files = {'mimetype': 'application/epub+zip',
            'META-INF/container.xml': '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>',
            'EPUB/package.opf': f'<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">folio-studio-authored-{language}</dc:identifier><dc:title>Folio Studio authored {language} sample</dc:title><dc:language>{language}</dc:language></metadata><manifest><item id="ch1" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="ch1"/></spine></package>',
            'EPUB/chapter.xhtml': f'<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Sample chapter</title></head><body>{body}</body></html>'}
        with zipfile.ZipFile(AUDIT/f'authored-{language}.epub', 'w') as archive:
            for name, value in files.items():
                archive.writestr(zipfile.ZipInfo(name), value.encode('utf-8'))


def runtime(backend):
    return ROOT / ('tools/narration/.venv/Scripts/python.exe' if backend == 'cpu'
                   else 'tools/narration/.venv-dml/Scripts/python.exe')


def run(arguments, log):
    with log.open('w', encoding='utf-8') as stream:
        subprocess.run([str(a) for a in arguments], cwd=ROOT, stdout=stream,
                       stderr=subprocess.STDOUT, timeout=600, check=True,
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)


def verify_export(directory, expected_count):
    plan = load_json(directory / 'job.json')
    progress = load_json(directory / 'progress.json')
    bundles = sorted(directory.glob('exports/*.foliobook'))
    if len(bundles) != 1:
        raise AssertionError('Expected exactly one authored-test export')
    with zipfile.ZipFile(bundles[0]) as archive:
        assert archive.testzip() is None
        manifest = json.loads(archive.read('manifest.json'))
        validate_manifest(manifest)
        assert hashlib.sha256(archive.read('book.epub')).hexdigest() == plan['sourceSha256']
        expected = [cue for chapter in plan['chapters'] for cue in chapter['cues']][:expected_count]
        actual = [cue for chapter in manifest['chapters'] for cue in chapter['cues']]
        assert len(actual) == expected_count
        assert [cue['text'] for cue in actual] == [cue['text'] for cue in expected]
        assert [cue['href'] for cue in actual] == [cue['href'] for cue in expected]
        expected_preview = expected_count < manifest['totalSentenceCount']
        assert manifest['preview'] == expected_preview
        # Resolve FFmpeg through the same isolated runtime as production.
        ffmpeg = subprocess.check_output(
            [str(runtime('cpu')), '-c', 'import imageio_ffmpeg;print(imageio_ffmpeg.get_ffmpeg_exe())'],
            text=True, creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0).strip()
        for index, chapter in enumerate(manifest['chapters']):
            previous_end = 0
            for cue in chapter['cues']:
                assert cue['startMs'] >= previous_end
                previous_end = cue['endMs']
            mp3 = directory / chapter['file']
            assert archive.read(chapter['file']) == mp3.read_bytes()
            decoded = directory / f'decoded-test-{index}.pcm'
            run([ffmpeg, '-v', 'error', '-y', '-i', mp3, '-ar', '24000', '-ac', '1',
                 '-f', 's16le', decoded], directory / f'decode-{index}.log')
            duration_ms = decoded.stat().st_size / 2 / 24
            assert abs(duration_ms - chapter['durationMs']) < 40
            decoded.unlink()
    return dict(bundle=str(bundles[0].relative_to(ROOT)), cues=expected_count,
                preview=manifest['preview'], phase=progress['phase'],
                audioSeconds=progress['generatedSamples'] / 24000,
                synthesisSeconds=progress['synthesisSeconds'],
                execution=progress.get('parallelExecution', {}),
                sourceAndTextCoverage=True, decodedDurationMatches=True)


def main():
    lease = ROOT / 'tools/narration/jobs/validation-studio-0.2.0/job.lock'
    if active_narrators(ROOT, ROOT / 'output/FolioStudio-data'):
        raise RuntimeError('A renderer is active; run release validation after it finishes')
    lease.parent.mkdir(parents=True, exist_ok=True)
    if lease.exists():
        if live_lease(lease):
            raise RuntimeError('Release validation is already active')
        lease.unlink()
    with lease.open('x') as stream:
        json.dump(dict(pid=os.getpid(), started=time.time()), stream)
    keep_awake(True)
    report = {}
    try:
        authored_epubs()
        for parallel, threads in ((1, 4), (4, 2)):
            name = f'pico-sustained-p{parallel}-t{threads}'
            folder = AUDIT / name
            save_json(AUDIT / 'validation-status.json', dict(state='running', stage=name))
            print(name, flush=True)
            if not (folder / 'result.json').is_file():
                run([runtime('onnx-directml'), ROOT / 'tools/narration/benchmark_parallel.py',
                     '--engine', 'pico', '--backend', 'onnx-directml', '--parallel', parallel,
                     '--threads', threads, '--repeat', 4, '--output', folder], AUDIT / (name + '.log'))
        baseline = load_json(AUDIT / 'pico-sustained-p1-t4/result.json')
        parallel = load_json(AUDIT / 'pico-sustained-p4-t2/result.json')
        assert [p['sha256'] for p in baseline['passages']] == [p['sha256'] for p in parallel['passages']]
        report['sustainedPico'] = dict(passages=len(parallel['passages']),
            audioSeconds=parallel['audioSeconds'], baselineSeconds=baseline['synthesisSeconds'],
            parallelSeconds=parallel['synthesisSeconds'],
            speedup=baseline['synthesisSeconds']/parallel['synthesisSeconds'], byteIdentical=True)
        results = load_json(AUDIT / 'summary.json')
        nano = min((r for r in results if r['engine'] == 'chatterbox-nano' and not r.get('failed')),
                   key=lambda r:r['synthesisSeconds'])
        jobs = [('pico', 'onnx-directml', 4, 2, None),
                ('plapre3', 'onnx-directml', 4, 2, 3),
                ('chatterbox-nano', nano['backend'], 1, nano['threads'], 3)]
        report['exports'] = []
        for engine, backend, passages, threads, limit in jobs:
            save_json(AUDIT / 'validation-status.json', dict(state='running', stage='export-'+engine))
            print('Export', engine, flush=True)
            source = AUDIT / ('authored-en.epub' if engine == 'chatterbox-nano' else 'authored-da.epub')
            directory, plan = create_job(source, engine, jobs_root=AUDIT/'export-jobs', accelerator=backend,
                                         voice='ida' if engine != 'chatterbox-nano' else None)
            model = (ROOT/'tools/narration/models/chatterbox-nano' if engine == 'chatterbox-nano'
                     else ROOT/'output/FolioStudio-data/models'/engine)
            args = [runtime(backend), ROOT/'tools/narration/narrate.py', 'generate', '--job', directory,
                    '--model-dir', model, '--parallel-passages', passages, '--cpu-threads', threads]
            if limit:
                args += ['--through-sentences', limit]
            started = time.monotonic()
            run(args, AUDIT / ('export-'+engine+'.log'))
            count = limit or sum(len(c['cues']) for c in plan['chapters'])
            record = verify_export(directory, count)
            record.update(engine=engine, wallSeconds=time.monotonic()-started)
            report['exports'].append(record)
            save_json(AUDIT/'validation-result.json', report)
        save_json(AUDIT/'validation-status.json', dict(state='complete'))
        print('Release validation complete', flush=True)
    finally:
        keep_awake(False)
        lease.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
