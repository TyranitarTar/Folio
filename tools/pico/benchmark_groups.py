"""Native, warmed AB/BA sentence grouping experiment; production chunking is unchanged."""
import pathlib
import subprocess
import time

root = pathlib.Path(__file__).resolve().parent
reader = root.parents[1] / "android-reader"
if not reader.is_dir():
    reader = root.parent
adb = reader / "toolchain/sdk/platform-tools/adb.exe"
app = "app.folio.reader.debug"


def call(*args, **kwargs):
    return subprocess.run([str(adb), "-s", "emulator-5554", *map(str, args)], check=True, **kwargs)


for attempt in range(60):
    status = call("shell", "getprop", "sys.boot_completed", capture_output=True, text=True)
    if status.stdout.strip() == "1":
        break
    time.sleep(1)
else:
    raise SystemExit("Emulator did not finish booting")
assert call("shell", "getprop", "ro.kernel.qemu", capture_output=True, text=True).stdout.strip() == "1"
call("install", "-r", reader / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
checks = [
    ("android-grouping-v0101.log", "app.folio.reader.PicoContinuityInstrumentedTest#compareBoundedSentenceGroupsWithSeparatePassages"),
    ("android-preview-v0101.log", "app.folio.reader.VoicePacksInstrumentedTest#predownloadSampleDoesNotLoadModelsOrChangeVoiceOrBookCheckpoint"),
]
for name, selected in checks:
    print("Running", name, flush=True)
    with (root / name).open("w", encoding="utf-8") as output:
        call("shell", "am", "instrument", "-w", "-r", "-e", "networkVoiceSamples", "true", "-e", "class", selected,
             app + ".test/androidx.test.runner.AndroidJUnitRunner", stdout=output, stderr=subprocess.STDOUT, timeout=300)
    text = (root / name).read_text(encoding="utf-8")
    print(text, flush=True)
    if "OK (1 test)" not in text or "FAILURES!!!" in text or "INSTRUMENTATION_STATUS_CODE: -4" in text:
        raise SystemExit(2)
    if "grouping" in name:
        for file in ("pico-grouping-validation.json", "pico-grouping-grouped.wav", "pico-grouping-separate.wav"):
            with (root / "results/v0101" / file).open("wb") as output:
                call("exec-out", "run-as", app, "cat", "files/" + file, stdout=output)
