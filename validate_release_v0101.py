"""Verify the padding-only app update without modifying models or release artifacts."""
from pathlib import Path
import datetime
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parent
output = root.parent / "output"
tools = root.parent / "tools/pico"
apk = output / "Folio-0.10.1.apk"
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
assert (output / "Folio-0.10.1.apk.sha256").read_text().split()[0] == digest
signer = "8ba95f74fce4d7a85afc6126a6ee37b1d4c1baf8cecc4276038a0e3a256b087b"
signature = (tools / "apk-signature-v0101.log").read_text(encoding="utf-8-sig")
assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signature
assert f"certificate SHA-256 digest: {signer}" in signature
badging = (tools / "apk-badging-v0101.log").read_text(encoding="utf-8-sig")
assert "versionCode='13' versionName='0.10.1'" in badging
with zipfile.ZipFile(apk) as current, zipfile.ZipFile(output / "Folio-0.10.0.apk") as previous:
    libraries = [name for name in current.namelist() if name.startswith("lib/") and name.endswith(".so")]
    assert len(libraries) == 16
    assert all(current.read(name) == previous.read(name) for name in libraries)
    assert current.read("assets/voice-packs.json") == previous.read("assets/voice-packs.json")
    packs = json.loads(current.read("assets/voice-packs.json"))["packs"]
    assert len(packs) == 3 and all(pack["url"].startswith("https://github.com/TyranitarTar/Folio/releases/download/") for pack in packs)
    assert not any(name.endswith((".onnx", ".safetensors", ".gguf")) for name in current.namelist())
with zipfile.ZipFile(output / "Folio-source-0.10.1.zip") as source:
    names = source.namelist()
    assert "Folio/app/src/main/java/app/folio/reader/PicoPadding.kt" in names
    assert "Folio/app/src/androidTest/java/app/folio/reader/PicoContinuityInstrumentedTest.kt" in names
    assert "Folio/IMPLEMENTATION-v0.10.1.md" in names
    assert not any(part in {"signing", "toolchain", ".git", ".gradle", ".cxx", "audit"} for name in names for part in Path(name).parts)
    assert not any(name.endswith((".jks", ".keystore", ".p12", ".onnx", ".safetensors", ".gguf")) for name in names)
results = [ET.parse(path).getroot() for path in (root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml")]
unit = {key: sum(int(item.get(key, 0)) for item in results) for key in ("tests", "failures", "errors", "skipped")}
assert unit["tests"] == 140 and all(unit[key] == 0 for key in ("failures", "errors", "skipped")), unit
lint = ET.parse(root / "app/build/reports/lint-results-debug.xml").getroot()
assert not any(issue.get("severity") in ("Error", "Fatal") for issue in lint)
for name, count in (("android-instrumentation-v0101.log", 4), ("android-continuity-v0101.log", 1), ("android-regressions-v0101.log", 3), ("android-preview-v0101.log", 1), ("android-grouping-v0101.log", 1)):
    text = (tools / name).read_text(encoding="utf-8-sig")
    assert re.search(rf"OK \({count} tests?\)", text) and "FAILURES!!!" not in text, name
    if name == "android-regressions-v0101.log":
        # Two native engine checks passed. A network-opt-in preview was skipped here
        # and is verified by its explicit one-test rerun above, never counted as a pass.
        assert text.count("INSTRUMENTATION_STATUS_CODE: 0") == 2
        assert text.count("INSTRUMENTATION_STATUS_CODE: -4") == 1
    else:
        assert "INSTRUMENTATION_STATUS_CODE: -4" not in text and text.count("INSTRUMENTATION_STATUS_CODE: 0") == count
continuity = json.loads((tools / "results/v0101/pico-continuity-validation.json").read_text())
assert continuity["passages"] == 20 and continuity["generationStartsDuringPlayback"] > 0
assert continuity["lookaheadQueuedBeforePreviousEnd"] >= 2
events = continuity["events"]
starts = [event for event in events if event["kind"] == "player.presented-start"]
ends = {event["id"]: event for event in events if event["kind"] == "player.presented-end"}
windows = [(event["nanos"], ends[event["id"]]["nanos"]) for event in starts]
actualOverlap = sum(any(start <= event["nanos"] <= end for start, end in windows)
                    for event in events if event["kind"] == "pico.synthesis.start")
assert actualOverlap == continuity["generationStartsDuringPlayback"] and actualOverlap > 0
report = {
    "version": "0.10.1", "versionCode": 13, "apkBytes": apk.stat().st_size, "sha256": digest,
    "signerCertificateSha256": signer, "unchangedNativeLibraries": len(libraries),
    "modelCatalogUnchanged": True, "modelDownloadRequired": False, "voicePackHost": "GitHub Releases",
    "unit": unit, "lintWarnings": len(lint), "androidChecks": 9,
    "networkPreviewInitialSkip": "Retried with its required networkVoiceSamples=true flag; explicit rerun must pass",
    "groupingExperiment": json.loads((tools / "results/v0101/pico-grouping-validation.json").read_text()),
    "continuity": {key: value for key, value in continuity.items() if key != "events"},
    "physicalPhonePerformance": "Not measured; Galaxy S10 and Flip 7 acceptance required",
    "verifiedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
}
target = output / "Folio-0.10.1-validation.json"
target.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(json.dumps(report, indent=2))
