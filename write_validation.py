"""Collect v0.5 release evidence; refuse failed tests, incomplete upgrades or invalid artifacts."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parent
output = root.parent / "output"
version = "0.5.0"
apk = output / f"Folio-{version}.apk"
units = [ET.parse(p).getroot().attrib for p in sorted((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))]
total = sum(int(u["tests"]) for u in units)
assert total == 41 and all(u["failures"] == u["errors"] == u["skipped"] == "0" for u in units)
device = (root / "full-v5-delivery-device.log").read_text(encoding="utf-8-sig")
assert "OK (14 tests)" in device and "Process crashed" not in device and "FAILURES!!!" not in device
assert "INSTRUMENTATION_STATUS_CODE: -3" not in device
signature = (root / "signature-v5.log").read_text(encoding="utf-8-sig")
assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signature
certificate = re.search(r"certificate SHA-256 digest: (\w+)", signature)[1]
assert certificate == "8ba95f74fce4d7a85afc6126a6ee37b1d4c1baf8cecc4276038a0e3a256b087b"
assert "android.permission.INTERNET" not in (root / "permissions-v5.log").read_text(encoding="utf-8-sig")
assert "FATAL EXCEPTION" not in (root / "runtime-v5-device.log").read_text(encoding="utf-8-sig")
lint = {}
for mode in ("debug", "release"):
    issues = ET.parse(root / f"app/build/reports/lint-results-{mode}.xml").getroot()
    assert not [i for i in issues if i.attrib.get("severity") in ("Error", "Fatal")]
    lint[mode] = {"errors": 0, "warnings": sum(i.attrib.get("severity") == "Warning" for i in issues)}
with zipfile.ZipFile(apk) as bundle:
    architectures = sorted({n.split("/")[1] for n in bundle.namelist() if n.startswith("lib/")})
    assert architectures == ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
    assert bundle.getinfo("assets/kokoro/model.onnx").file_size == 325560556
    assert all(f"lib/{abi}/libsherpa-onnx-jni.so" in bundle.namelist() for abi in architectures)
    model = json.loads(bundle.read("assets/supertonic/artifacts.json"))
    assert model["revision"] == "cca5a0e6c96e1d2c720986bf7e75fcc81dee3ae4"
    for item in model["files"]:
        data = bundle.read("assets/supertonic/" + item["name"])
        assert len(data) == item["bytes"] and hashlib.sha256(data).hexdigest() == item["sha256"]
    licenses = bundle.read("assets/AI_VOICE_LICENSES.txt").decode("utf-8")
    assert "SUPERTONIC 3 MODEL WEIGHTS LICENSE (OpenRAIL-M)" in licenses
    assert "KOKORO MODEL LICENSE" in licenses and "Supertonic 3 by Supertone" in licenses
    assert "Mihon UI patterns adapted for Folio" in bundle.read("assets/MIHON_LICENSE.txt").decode("utf-8")
def evidence(name):
    return json.loads((output / f"Folio-{version}-{name}.json").read_text())
kokoro = evidence("AI-test-evidence")
supertonic = evidence("Supertonic-test-evidence")
continuous = evidence("continuous-speech-evidence")
from_here = evidence("read-from-here-evidence")
performance = evidence("Kokoro-performance")
assert kokoro["restartSample"] > kokoro["pauseSample"] > 0
assert supertonic["restartSample"] > supertonic["pauseSample"] > 0
assert supertonic["deterministicRegeneration"] and supertonic["activeBackendSwitch"] and supertonic["pausedLanguageSwitch"]
assert all(supertonic[f"voice{i}"]["sampleRate"] == 44100 for i in range(1, 11))
assert all(supertonic[f"danishVoice{i}"]["sampleRate"] == 44100 for i in (1, 6))
assert len(continuous["handoffGapsMillis"]) >= 5 and continuous["maximumGapMillis"] < 750
assert continuous["persistentAudioTrack"] and continuous["firstAudioTrackId"] == continuous["finalAudioTrackId"]
assert from_here["passed"] and from_here["visibleWord"] > from_here["oldParagraphWord"]
upgrade = json.loads((root / "upgrade-v5-smoke.json").read_text())
assert upgrade["completed"] and upgrade["from_version"] == "0.4.0" and upgrade["to_version"] == version
digest = hashlib.sha256()
with apk.open("rb") as source:
    while block := source.read(1024 * 1024): digest.update(block)
report = {
    "app": "Folio", "version": version, "version_code": 5, "package": "app.folio.reader",
    "apk": apk.name, "apk_bytes": apk.stat().st_size, "sha256": digest.hexdigest(),
    "architectures": architectures, "min_sdk": 26, "target_sdk": 35,
    "signing": {"verified": True, "certificate_sha256": certificate, "same_key_as_previous_releases": True},
    "internet_permission": False,
    "models": {"kokoro": "v1.0 FP32, Emma/Heart, Apache-2.0",
        "supertonic": {"name": "Supertonic 3 INT8 by Supertone", "languages": ["en", "da"], "voices": 10,
            "sample_rate": 44100, "steps": 8, "seed": 42, "manifest": model, "license": "OpenRAIL-M weights; MIT conversion"}},
    "runtime": "Unchanged sherpa-onnx 1.13.8 / ONNX Runtime 1.28.2 shared by both models",
    "playback": {"only_selected_neural_model_loaded": True, "kokoro_cpu_threads": "available processors, capped at four",
        "supertonic_cpu_threads": 2, "initial_buffer_seconds": 12, "maximum_upcoming_passages": 6,
        "cache_limit_bytes": 64 * 1024 * 1024, "checkpoint": "AudioTrack presentation timestamps when supported; guarded render estimate otherwise, once per second plus pause/stop",
        "natural_handoff": "one persistent ordered PCM FIFO; a growing MP3 is unnecessary",
        "following": "passage quotes; guided word timings remain independent"},
    "library": {"mihon_adaptation": "Library/History/Browse/More, grids/list, filters/sort/display, persistent shelves, book details/chapters/annotations", "credits": "Mihon contributors, Apache-2.0", "legacy_epub_data_preserved": True},
    "evidence": {"kokoro": kokoro, "supertonic": supertonic, "continuous": continuous,
        "read_from_here": from_here, "thread_benchmark": performance},
    "unit_tests": [{k: u[k] for k in ("name", "tests", "failures", "errors", "skipped")} for u in units],
    "unit_test_total": total, "android_tests": {"tests": 14, "failures": 0, "skipped": 0},
    "lint": lint, "release_upgrade": upgrade,
    "device": "Android 15 / API 35 x86_64 emulator with Google TTS",
    "physical_device_tested": False, "subjective_voice_audition": False,
    "remaining_validation": ["Physical ARM phone Danish pronunciation, sustained throughput, battery/memory and Bluetooth"],
    "created_utc": datetime.now(timezone.utc).isoformat()
}
(output / f"Folio-validation-{version}.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
summary = f"""# Folio {version} validation

Fixes early passage transitions with one persistent PCM AudioTrack and timestamp-aware completion. Retains Supertonic English/Danish, Kokoro Emma/Heart and compatible listening checkpoints. Adapts Mihon's library/navigation patterns to EPUBs.

- {total} JVM tests and all 14 Android tests passed; no skipped EPUB fixture.
- Cold Kokoro playback crossed five passage boundaries with a maximum measured handoff gap of {continuous['maximumGapMillis']:.1f} ms. The same Android audio-track ID was retained at every boundary. Future timestamps and pause/seek cancellation have dedicated regressions.
- Read from here began at visible word {from_here['visibleWord']}, rather than paragraph word {from_here['oldParagraphWord']}, and spoke “{from_here['spokenFirstWord']}”.
- Real JNI synthesis checked ten Supertonic voice styles, English/Danish, fixed-seed regeneration, pause/reopen recovery, language/voice changes and switching back to Kokoro during playback.
- Home UI checks cover shelf assignment/search recovery, grid/list preference persistence, book details, history, browse and theme recreation.
- Debug/release lint has no errors. Signed upgrade retains the previous certificate and reader data.
- Model hashes and license texts were verified in the APK. No Internet permission or cloud dependency. Source bundles contain no private signing keys.

The emulator is muted; WAV previews are included for audition. Physical ARM phone pronunciation, sustained speed, battery/memory and Bluetooth still need testing. Initial buffering adds preparation time before playback; a device that synthesizes slower than the selected speech rate can eventually need more buffering.
"""
(output / f"Folio-validation-{version}.md").write_text(summary, encoding="utf-8")
print(f"Validated {apk.name}: {total} JVM tests, 14 Android tests, compatible signature")
