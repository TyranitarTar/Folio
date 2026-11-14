"""Render the public release body from the changelog bundled in the Android app."""
import argparse
import json
from pathlib import Path


def render(version: str) -> str:
    root = Path(__file__).resolve().parent
    catalog = json.loads((root / 'app/src/main/assets/release-notes.json').read_text(encoding='utf-8'))
    assert catalog['version'] == 1
    matches = [row for row in catalog['releases'] if row['version'] == version]
    if len(matches) != 1:
        raise ValueError(f'Expected one changelog record for {version}')
    return '\n\n'.join('- ' + change for change in matches[0]['changes']) + '\n'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    body = render(args.version)
    if args.output:
        args.output.write_text(body, encoding='utf-8')
    else:
        print(body, end='')
