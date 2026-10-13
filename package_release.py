"""Package only source, public build files and the signed APK; never include signing keys."""
from pathlib import Path
import hashlib
import shutil
import sys
import zipfile

root = Path(__file__).resolve().parent
output = root.parent / "output"
output.mkdir(exist_ok=True)
version = "0.8.0"
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
             "collect_ai_sources.py", "capture_ai_evidence.py", "validate_release_v6.py", "validate_release_v7.py", "verify_upgrade_v7.py", "IMPLEMENTATION-v0.6.md", "IMPLEMENTATION-v0.7.md", "GITHUB-RELEASES.md", "validate_release_v071.py", "verify_upgrade_v071.py", "IMPLEMENTATION-v0.8.md", "ROADMAP-0.8.md", "validate_release_v08.py", "verify_upgrade_v08.py"):
    path = root / name
    if path.is_file():
        files.append(path)
archive = output / f"Folio-source-{version}.zip"
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
    for path in sorted(set(files)):
        relative = path.relative_to(root)
        assert "signing" not in relative.parts and "toolchain" not in relative.parts
        assert path.suffix.lower() not in {".p12", ".jks", ".keystore"}
        bundle.write(path, Path("Folio") / relative)
print(f"Packaged {apk.name} ({apk.stat().st_size:,} bytes), SHA-256 {digest}")
print(f"Source: {archive.name}, {len(set(files))} files")
native = output / f"Folio-native-source-{version}.zip"
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
print(f"Native corresponding source: {native.name} ({native.stat().st_size:,} bytes)")
