"""Fail closed on release evidence; never infer hardware results from the emulator."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parent
output = root.parent / "output"
audit = root / "audit"
apk = output / "Folio-0.6.0.apk"
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
assert digest in (output / "Folio-0.6.0.apk.sha256").read_text()
build = (root / "build-v6-second-loop-final.log").read_text(encoding="utf-8")
assert "BUILD SUCCESSFUL" in build
lint = (root / "app/build/reports/lint-results-debug.txt").read_text(encoding="utf-8")
assert not re.search(r"^.+: Error:", lint, re.MULTILINE)
warning_count = re.search(r"(\d+) warnings?", lint.splitlines()[-1])
reports = list((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
counts = {key: sum(int(ET.parse(p).getroot().get(key, "0")) for p in reports)
          for key in ("tests", "failures", "errors", "skipped")}
assert counts["tests"] >= 74 and not any(counts[k] for k in ("failures", "errors", "skipped"))
native_log = (root / "full-v6-second-loop-final-device.log").read_text(encoding="utf-8")
assert "FAILURES!!!" not in native_log and "INSTRUMENTATION_FAILED" not in native_log
match = re.search(r"OK \((\d+) tests\)", native_log)
assert match and int(match.group(1)) >= 20
assert "STATUS_CODE: -3" not in native_log, "A device test was skipped"
signature = (audit / "v6-apk-signature.log").read_text(encoding="utf-8")
cert = "8ba95f74fce4d7a85afc6126a6ee37b1d4c1baf8cecc4276038a0e3a256b087b"
assert cert in signature
badging = (audit / "v6-apk-badging.log").read_text(encoding="utf-8")
assert "versionCode='6'" in badging and "versionName='0.6.0'" in badging
assert "android.permission.INTERNET" not in badging
with zipfile.ZipFile(apk) as new, zipfile.ZipFile(output / "Folio-0.5.0.apk") as old:
    libs = {i.filename: hashlib.sha256(new.read(i)).hexdigest() for i in new.infolist()
            if i.filename.startswith("lib/") and i.filename.endswith(".so")}
    previous = {i.filename: hashlib.sha256(old.read(i)).hexdigest() for i in old.infolist()
                if i.filename.startswith("lib/") and i.filename.endswith(".so")}
    assert libs == previous
    assert "assets/kokoro/model.onnx" in new.namelist()
    assert any(n.startswith("assets/supertonic/") and n.endswith(".onnx") for n in new.namelist())
    assert any("license" in n.lower() for n in new.namelist())
with zipfile.ZipFile(output / "Folio-source-0.6.0.zip") as source:
    names = source.namelist()
    assert any(n.endswith("LibraryBackup.kt") for n in names)
    assert any(n.endswith("BoundedJson.kt") for n in names)
    assert not any(n.lower().endswith((".keystore", ".jks", ".p12")) or "/signing/" in n.lower() for n in names)
    assert not any(n.lower().endswith(".epub") and not n.endswith("sample.epub") for n in names)
upgrade = json.loads((audit / "v6-upgrade-result.json").read_text())
assert upgrade["passed"]
usage = json.loads((audit / "v6-usage-after-implementation.json").read_text())
reset_usage = json.loads((audit / "v6-usage-after-user-reset.json").read_text())
result = {"version": "0.6.0", "versionCode": 6, "date": "2026-10-11", "apkBytes": apk.stat().st_size,
          "apkSha256": digest, "signerCertificateSha256": cert, "nativeLibrariesUnchanged": len(libs),
          "jvm": counts, "android": {"tests": int(match.group(1)), "failures": 0, "skipped": 0,
              "device": "Android 15/API 35 x86_64 headless local emulator", "illustratedExternalEpubIncluded": True},
          "lint": {"errors": 0, "warnings": int(warning_count.group(1)) if warning_count else None,
                   "note": "Remaining warnings are documented in the preserved lint report; pinned dependency upgrade/style suggestions are not silenced."},
          "upgrade": upgrade, "usageAfterInitialImplementation": usage,
          "usageAfterUserReset": reset_usage,
          "secondEvaluationLoop": "Completed; partial/older-mode restore, JSON work/commit budgets, queued-ahead FIFO, handoff pause and pin-lock fixes",
          "physicalGalaxyS10Tested": False, "audibleBluetoothTimingTested": False,
          "limitations": "See implementation-and-review report for physical acceptance, whole-book indexing, publisher DOM/script tests and cross-file restore transaction limits."}
for name in ("Folio-0.6.0-validation.json",):
    (output / name).write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
shutil.copy2(audit / "Folio-0.6.0-implementation-and-review.md", output / "Folio-0.6.0-review.md")
destination = audit / "v6-unit-final"
destination.mkdir(exist_ok=True)
for report in reports:
    shutil.copy2(report, destination / report.name)
print(json.dumps({"apk": apk.name, "sha256": digest, "jvmTests": counts["tests"], "androidTests": int(match.group(1)),
                  "upgradePreserved": True, "nativeLibrariesUnchanged": len(libs)}, indent=2))
