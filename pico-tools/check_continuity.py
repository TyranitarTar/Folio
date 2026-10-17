"""Run cold Pico service continuity and existing-engine checks on the local emulator."""
import json
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parent
reader = root.parents[1] / "android-reader"
if not reader.is_dir():
    reader = root.parent
adb = reader / "toolchain/sdk/platform-tools/adb.exe"
app = "app.folio.reader.debug"


def call(*args, **kwargs):
    return subprocess.run([str(adb), "-s", "emulator-5554", *args], check=True, **kwargs)


if call("shell", "getprop", "ro.kernel.qemu", capture_output=True, text=True).stdout.strip() != "1":
    raise SystemExit("This test only runs against the local emulator")

checks = [
    ("android-continuity-v0101.log", "app.folio.reader.PicoContinuityInstrumentedTest#coldPicoGeneratesAheadDuringTwentyPassagesOfServicePlayback", 1),
    ("android-regressions-v0101.log", ",".join([
        "app.folio.reader.SpeechV7InstrumentedTest#supertonicLiveRatesReuseWaveformCheckpointAndSurvivePauseRestart",
        "app.folio.reader.ContinuousSpeechInstrumentedTest#kokoroPlaysSixColdPassagesWithoutMultiSecondPreparationGaps",
        "app.folio.reader.VoicePacksInstrumentedTest#predownloadSampleDoesNotLoadModelsOrChangeVoiceOrBookCheckpoint",
    ]), 3),
]
for name, classes, expected in checks:
    print("Running", name, flush=True)
    with (root / name).open("w", encoding="utf-8") as output:
        call("shell", "am", "instrument", "-w", "-r", "-e", "networkVoiceSamples", "true", "-e", "class", classes,
             app + ".test/androidx.test.runner.AndroidJUnitRunner", stdout=output, stderr=subprocess.STDOUT, timeout=600)
    result = (root / name).read_text(encoding="utf-8")
    print(result, flush=True)
    if f"OK ({expected} test" not in result or "FAILURES!!!" in result or "INSTRUMENTATION_STATUS_CODE: -4" in result:
        raise SystemExit(2)
    if "continuity" in name:
        target = root / "results/v0101/pico-continuity-validation.json"
        with target.open("wb") as output:
            call("exec-out", "run-as", app, "cat", "files/pico-continuity-validation.json", stdout=output)
        report = json.loads(target.read_text(encoding="utf-8"))
        print(json.dumps({key: value for key, value in report.items() if key != "events"}, indent=2), flush=True)
