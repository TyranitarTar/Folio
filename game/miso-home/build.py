"""Build the production Godot room pack and verify its complete activity planner."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent
ANDROID = ROOT.parents[1]

def run_godot(exe, args, log_path):
    env = dict(os.environ)
    if os.name == 'nt':
        env['APPDATA'] = str(ANDROID / 'toolchain/godot/user-data')
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open('w', encoding='utf-8') as log:
        result = subprocess.run([str(exe), *args], env=env, stdout=log,
                                stderr=subprocess.STDOUT, timeout=120)
    text = log_path.read_text(encoding='utf-8')
    if result.returncode or 'SCRIPT ERROR' in text or 'Failed to load' in text:
        raise RuntimeError(text[-4000:])

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--godot', default=shutil.which('godot') or r'C:\Program Files\Godot\Godot.exe')
    parser.add_argument('--art', action='store_true', help='Re-export native PNGs from preserved generated originals; requires Pillow')
    parser.add_argument('--review', action='store_true', help='Render desktop review screenshots with the actual renderer')
    args = parser.parse_args()
    if args.art:
        from export_art import export as export_art
        from export_scene import export as export_scene
        export_art()
        export_scene()
    audit = ANDROID / 'audit/v019'
    exe = Path(args.godot)
    version = subprocess.check_output([str(exe), '--headless', '--version'], text=True).strip()
    assert version.startswith('4.7.2.'), version
    base = ['--headless', '--path', str(ROOT)]
    run_godot(exe, base + ['--editor', '--import', '--quit'], audit / 'import.log')
    run_godot(exe, base + ['--script', 'res://scripts/check_game.gd'], audit / 'logic-check.log')
    assert 'PASS:' in (audit / 'logic-check.log').read_text(encoding='utf-8')
    output = ANDROID / 'app/src/main/assets/cafe-engine/miso-home.pck'
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT/'LICENSE-GODOT.txt',output.parent/'LICENSE-GODOT.txt')
    run_godot(exe, base + ['--script', 'res://scripts/check_notices.gd', '--', '--notices=' + (ROOT/'COPYRIGHT-GODOT.txt').as_posix()], audit / 'notices.log')
    shutil.copy2(ROOT/'COPYRIGHT-GODOT.txt',output.parent/'COPYRIGHT-GODOT.txt')
    run_godot(exe, base + ['--export-pack', 'Android pack', str(output)], audit / 'pack.log')
    if args.review:
        run_godot(exe, ['--path', str(ROOT), '--resolution', '1080x2400', '--position', '-2000,0',
                       '--', '--review', '--review-dir=' + audit.as_posix()], audit / 'desktop-review.log')
    print(json.dumps(dict(engine=version, packBytes=output.stat().st_size,
                          packSha256=hashlib.sha256(output.read_bytes()).hexdigest())))

if __name__ == '__main__':
    main()
