"""Validate the delivered APK and preserved evidence; emulator tests are not acoustic acceptance."""
from pathlib import Path
import hashlib, json, re, shutil, xml.etree.ElementTree as ET, zipfile
root = Path(__file__).resolve().parent
output = root.parent / "output"
audit = root / "audit"
apk = output / "Folio-0.7.0.apk"
def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024*1024), b""): h.update(chunk)
    return h.hexdigest()
sha = digest(apk)
assert sha in (output / "Folio-0.7.0.apk.sha256").read_text()
assert "BUILD SUCCESSFUL" in (root / "build-v7-release-candidate.log").read_text(encoding="utf-8")
reports = list((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
counts = {key:sum(int(ET.parse(p).getroot().get(key,"0")) for p in reports) for key in ("tests","failures","errors","skipped")}
assert counts["tests"] >= 84 and not any(counts[k] for k in ("failures","errors","skipped"))
log = (root / "full-v7-final-device.log").read_text(encoding="utf-8")
assert not any(x in log for x in ("FAILURES!!!","INSTRUMENTATION_FAILED","STATUS_CODE: -3"))
match = re.search(r"OK \((\d+) tests\)",log)
assert match and int(match.group(1)) >= 28
lint = (root / "app/build/reports/lint-results-debug.txt").read_text(encoding="utf-8")
assert "0 errors" in lint
warnings = int(re.search(r"(\d+) warnings",lint.splitlines()[-1]).group(1))
cert = "8ba95f74fce4d7a85afc6126a6ee37b1d4c1baf8cecc4276038a0e3a256b087b"
assert cert in (audit / "v7-apk-signature.log").read_text(encoding="utf-8")
badging = (audit / "v7-apk-badging.log").read_text(encoding="utf-8")
assert "versionCode='7'" in badging and "versionName='0.7.0'" in badging
assert "android.permission.INTERNET" not in badging
with zipfile.ZipFile(apk) as current, zipfile.ZipFile(output / "Folio-0.6.0.apk") as old:
    def libraries(z): return {n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith("lib/") and n.endswith(".so")}
    libs = libraries(current); assert libs == libraries(old)
    assert "assets/kokoro/model.onnx" in current.namelist()
    assert any(n.startswith("assets/supertonic/") and n.endswith(".onnx") for n in current.namelist())
    model_names = [n for n in old.namelist() if n.startswith(("assets/kokoro/", "assets/supertonic/"))]
    assert model_names
    assert all(current.read(n) == old.read(n) for n in model_names), "Bundled model packs changed"

with zipfile.ZipFile(output / "Folio-source-0.7.0.zip") as source:
    names = source.namelist()
    assert any(n.endswith("ReaderPlaybackBar.kt") for n in names)
    assert any(n.endswith("ReaderDecorations.kt") for n in names)
    assert any(n.endswith("SpeechV7InstrumentedTest.kt") for n in names)
    assert not any("/signing/" in n.lower() or n.lower().endswith((".keystore",".jks",".p12")) for n in names)
    assert not any(n.lower().endswith(".epub") and not n.endswith("sample.epub") for n in names)
with zipfile.ZipFile(output / "Folio-native-source-0.7.0.zip") as native:
    assert native.testzip() is None
    assert "0.7.0" in native.read("Folio-native/BUILD.md").decode()
upgrade = json.loads((audit / "v7-upgrade-result.json").read_text())
assert upgrade["passed"]
waveforms = json.loads((audit / "v7-device-evidence/supertonic-waveform-comparison.json").read_text())
assert waveforms["referenceOnly"] and not waveforms["acousticEndingsVerified"]
result = {"version":"0.7.0","versionCode":7,"date":"2026-10-12","apkBytes":apk.stat().st_size,
    "apkSha256":sha,"signerCertificateSha256":cert,"nativeLibrariesUnchanged":len(libs),"jvm":counts,
    "android":{"tests":int(match.group(1)),"failures":0,"skipped":0,"device":"Android 15/API35 x86_64 local headless emulator","illustratedExternalEpubIncluded":True},
    "lint":{"errors":0,"warnings":warnings},"upgrade":upgrade,
    "supertonicSpeedPath":"Canonical 1.0 synthesis; pitch 1.0 AudioTrack playback speed; durable source samples",
    "waveformReferences":waveforms,"physicalGalaxyS10Tested":False,"audibleBluetoothTimingTested":False,
    "limitations":"Emulator frame/cache/order checks do not establish audible endings or physical ARM latency. RSVP and objective comprehension tests remain future work."}
(output / "Folio-0.7.0-validation.json").write_text(json.dumps(result,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")
shutil.copy2(audit / "Folio-0.7.0-review.md",output / "Folio-0.7.0-review.md")
copy = audit / "v7-unit-final"; copy.mkdir(exist_ok=True)
for report in reports: shutil.copy2(report,copy / report.name)
print(json.dumps({"apk":apk.name,"sha256":sha,"jvmTests":counts["tests"],"androidTests":int(match.group(1)),"upgradePreserved":True},indent=2))
