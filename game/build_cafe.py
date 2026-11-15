"""Stage only the reviewed v2 runtime. Keep transfer archives and originals intact."""
from pathlib import Path
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parent
HANDOFF = ROOT / 'Folio_Miso_Handoff'
PROJECT = HANDOFF / 'pixel-cat-experiment/production/playful-room-playable-v2'
TARGET = ROOT.parent / 'app/src/main/assets/cafe'

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    manifest = json.loads((HANDOFF / 'TRANSFER_MANIFEST.json').read_text())
    for entry in manifest['files']:
        path = HANDOFF / entry['path']
        assert path.is_relative_to(HANDOFF) and path.stat().st_size == entry['bytes'], path
        assert digest(path) == entry['sha256'], path
    env = json.loads((PROJECT / 'environment.json').read_text())
    meta = json.loads((PROJECT / 'animation_data.json').read_text())
    paths = {'environment.json', 'animation_data.json', 'source/engine.js', 'source/renderer.js'}
    paths.update(a['sprite'] for a in env['assets'])
    paths.update(t['path'] for t in env['tiles'])
    paths.add(meta['atlas'])
    records = []
    for name in sorted(paths):
        source = PROJECT / name
        destination = TARGET / name.removeprefix('source/')
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        records.append({'source': name, 'asset': destination.relative_to(TARGET).as_posix(),
                        'sha256': digest(destination), 'bytes': destination.stat().st_size})
    (TARGET / 'provenance.json').write_text(json.dumps({
        'baseline': 'playful-room-playable-v2 (review fixes)', 'catScale': env['cat_scale'],
        'frames': len(meta['rects']), 'clips': len(meta['clips']), 'files': records
    }, indent=2) + '\n')
    print(f'Verified {len(manifest["files"])} handoff files; staged {len(records)} unchanged runtime files.')

if __name__ == '__main__':
    main()
