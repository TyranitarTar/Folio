"""Package only source, public build files and the signed APK; never include signing keys."""
from pathlib import Path
import hashlib
import shutil
import sys
import re
import zipfile

root = Path(__file__).resolve().parent
output = root.parent / "output"
output.mkdir(exist_ok=True)
version = re.search(r'versionName\s*=\s*"([0-9.]+)"', (root / "app/build.gradle.kts").read_text()).group(1)
apk = output / f"Folio-{version}.apk"
shutil.copy2(root / "app/build/outputs/apk/release/app-release.apk", apk)
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
(output / f"Folio-{version}.apk.sha256").write_text(f"{digest}  {apk.name}\n", encoding="utf-8")
shutil.copy2(root / "README.md", output / f"Folio-{version}-Install-and-use.md")
files = []
for name in ("app/src", "gradle", ".github"):
    files.extend(p for p in (root / name).rglob("*") if p.is_file() and "jniLibs" not in p.parts
                 and not ("kokoro" in p.parts and p.name not in {"LICENSE", "README.md", "artifacts.json"})
                 and not ("supertonic" in p.parts and p.name not in {"LICENSE", "CONVERSION-LICENSE", "README.md", "artifacts.json"}))
for name in (".gitignore", "README.md", "TTS-roadmap.md", "build.gradle.kts", "app/build.gradle.kts",
             "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", "build.ps1",
             "bootstrap.py", "make_sample.py", "collect_notices.py", "prepare_signing.py",
             "package_release.py", "write_validation.py", "runtime-dependencies.txt", "LICENSE", "AI-NATIVE-BUILD.md",
             "bootstrap_ai.py", "bootstrap_supertonic.py", "Danish-TTS-feasibility.md", "Mihon-adaptation.md",
             "collect_ai_sources.py", "capture_ai_evidence.py", "validate_release_v6.py", "validate_release_v7.py", "verify_upgrade_v7.py", "IMPLEMENTATION-v0.6.md", "IMPLEMENTATION-v0.7.md", "GITHUB-RELEASES.md", "validate_release_v071.py", "verify_upgrade_v071.py", "IMPLEMENTATION-v0.8.md", "ROADMAP-0.8.md", "validate_release_v08.py", "verify_upgrade_v08.py", "ROADMAP-0.9.md", "IMPLEMENTATION-v0.9.md", "build_voice_packs.py", "validate_voice_packs.py", "validate_release_v09.py", "verify_upgrade_v09.py", "IMPLEMENTATION-v0.10.md", "DESIGN-plapre-pico.md", "DESIGN-next-voices-and-cat-game.md", "validate_release_v010.py"):
    path = root / name
    if path.is_file():
        files.append(path)
files.append(root / "verify_upgrade_v010.py")
for name in ("IMPLEMENTATION-v0.10.1.md", "DESIGN-v0.10.1-speech-continuity-and-storage.md", "validate_release_v0101.py",
             "IMPLEMENTATION-v0.10.2.md", "IMPLEMENTATION-v0.10.2-storage.md", "validate_release_v0102.py", "verify_upgrade_v0102.py",
             "IMPLEMENTATION-v0.11.md", "validate_release_v011.py", "verify_upgrade_v011.py",
             "IMPLEMENTATION-v0.12.md", "validate_release_v012.py", "IMPLEMENTATION-v0.13.md", "validate_release_v013.py",
             "ROADMAP-0.13.1.md", "IMPLEMENTATION-v0.13.1-GPU.md"):
    if (root / name).is_file(): files.append(root / name)
for name in ("ROADMAP-0.14.md", "DESIGN-0.14.md", "IMPLEMENTATION-v0.14.md", "validate_release_v014.py",
             "IMPLEMENTATION-v0.14.1.md","REVIEW-0.14-FOLLOWUP.md","SMART-AUDIOBOOK-COMPARISON.md","validate_release_v0141.py",
             "IMPLEMENTATION-v0.15.md","validate_release_v015.py",
             "ROADMAP-0.16.md","IMPLEMENTATION-v0.16.md","validate_release_v016.py",
             "IMPLEMENTATION-v0.16.1.md","ENGLISH-LIVE-READALONG-IDEA.md","validate_release_v0161.py",
             "IMPLEMENTATION-v0.17.md","validate_release_v017.py","test_bootstrap_ai.py"):
    if (root / name).is_file(): files.append(root / name)
files.extend(p for p in (root / "design/icons-0.14").glob("*") if p.is_file() and p.suffix in (".svg", ".png", ".md"))
files.append(root / "design/make_icons_014.py")
for name in ("MISO-FIRST-CAT.md", "MISO-STORY-DANISH.md", "TODO.md"):
    files.append(root / "design/cat-cafe" / name)
for name in ("FEASIBILITY.md", "model-metadata-2026-10-19.json", "FORMAT-v1.md", "REVERSE-ALIGNMENT-TODO.md"):
    files.append(root / "design/desktop-audiobooks" / name)
for name in ("CAT-CAFE-GDD.md", "DESIGN-SYSTEM.md", "assets/folio-cat-book.svg", "assets/folio-cat-book-monochrome.svg", "assets/cats/miso.svg"):
    files.append(root / "design/cat-cafe/v1" / name)
archive = output / f"Folio-source-{version}.zip"
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
    for path in sorted(set(files)):
        relative = path.relative_to(root)
        assert "signing" not in relative.parts and "toolchain" not in relative.parts
        assert path.suffix.lower() not in {".p12", ".jks", ".keystore"}
        bundle.write(path, Path("Folio") / relative)
    pico_tools = root.parent / "tools/pico"
    for path in sorted(list(pico_tools.glob("*.py")) + list((pico_tools / "licenses").glob("*.txt")) +
                       [pico_tools / "requirements.txt", pico_tools / "README.md", pico_tools / ".gitignore"]):
        if path.is_file(): bundle.write(path, Path("Folio/tools/pico") / path.relative_to(pico_tools))
    plapre3_tools=root.parent/"tools/plapre3"
    for path in sorted(list(plapre3_tools.glob("*.py"))+[plapre3_tools/"README.md",plapre3_tools/".gitignore",plapre3_tools/"upstream-manifest.json",plapre3_tools/"reference-source.json"]):
        if path.is_file():bundle.write(path,Path("Folio/tools/plapre3")/path.relative_to(plapre3_tools))
    narration_tools = root.parent / "tools/narration"
    bundle.write(root.parent / "tools/__init__.py", "Folio/tools/__init__.py")
    # Deliberate top-level allowlist: never include user books, generated audio,
    # resumable jobs, cached models or the private Python environment.
    for path in sorted(list(narration_tools.glob("*.py")) + [narration_tools / name for name in
                       ("README.md", "DIRECTML.md", ".gitignore", "requirements-cpu.txt", "model-snapshots.json")]):
        if path.is_file(): bundle.write(path, Path("Folio/tools/narration") / path.name)
print(f"Packaged {apk.name} ({apk.stat().st_size:,} bytes), SHA-256 {digest}")
print(f"Source: {archive.name}, {len(set(files))} files")
native = output / f"Folio-native-source-{version}.zip"
if "--reuse-native" in sys.argv:
    assert native.exists() and zipfile.is_zipfile(native)
    with zipfile.ZipFile(native) as existing:
        for path in (root / "app/src/main/cpp/pico").rglob("*"):
            if path.is_file():
                assert existing.read(str(Path("Folio-native/pico") / path.relative_to(root / "app/src/main/cpp/pico")).replace('\\','/')) == path.read_bytes()
    print(f"Kept verified native source: {native.name}")
    sys.exit(0)
if "--skip-native" in sys.argv:
    assert native.exists() and zipfile.is_zipfile(native)
    print(f"Kept verified unchanged native source: {native.name}")
    sys.exit(0)
with zipfile.ZipFile(native, "w", zipfile.ZIP_DEFLATED) as bundle:
    for path in sorted((root / "reference/ai/source").rglob("*")):
        if path.is_file():
            bundle.write(path, Path("Folio-native") / path.relative_to(root / "reference/ai/source"))
    for name in ("artifacts.json", "native-dependencies.json"):
        bundle.write(root / "reference/ai" / name, Path("Folio-native") / name)
    bundle.write(root / "AI-NATIVE-BUILD.md", "Folio-native/BUILD.md")
    for path in sorted((root / "app/src/main/cpp/pico").rglob("*")):
        if path.is_file(): bundle.write(path, Path("Folio-native/pico") / path.relative_to(root / "app/src/main/cpp/pico"))
print(f"Native corresponding source: {native.name} ({native.stat().st_size:,} bytes)")
