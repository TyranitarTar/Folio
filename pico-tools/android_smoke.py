"""Install the debug candidate, stream the public fixture into app-private cache, run real native checks."""
import pathlib
import subprocess
import sys
from probe_lm import ROOT

project=ROOT.parent.parent
adb=project/"android-reader/toolchain/sdk/platform-tools/adb.exe"
app="app.folio.reader.debug"
def call(*args, **kwargs):
    return subprocess.run([str(adb),"-s","emulator-5554",*map(str,args)],check=True,**kwargs)

call("install","-r",project/"android-reader/app/build/outputs/apk/debug/app-debug.apk")
call("install","-r",project/"android-reader/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
archive=project/"output/voice-packs-pico-v1/Folio-Voice-Pico-v1.zip"
print("Streaming checksum-pinned fixture into debug cache",flush=True)
if "--reuse-fixture" not in sys.argv:
    with archive.open("rb") as source:
        call("exec-in","run-as",app,"dd","of=cache/pico-input.zip","bs=65536",stdin=source,stdout=subprocess.DEVNULL)
print("Running real Pico native checks",flush=True)
with (ROOT/"android-instrumentation.log").open("w",encoding="utf-8") as output:
    call("shell","am","instrument","-w","-r","-e","class","app.folio.reader.PicoInstrumentedTest",
         app+".test/androidx.test.runner.AndroidJUnitRunner",stdout=output,stderr=subprocess.STDOUT,timeout=600)
text=(ROOT/"android-instrumentation.log").read_text(encoding="utf-8")
print(text,flush=True)
if "OK (3 tests)" not in text or "FAILURES!!!" in text:
    raise SystemExit(2)
for name in ["pico-native-validation.json","pico-playback-validation.json","pico-service-validation.json","pico-native-da.wav"]:
    with (ROOT/"results"/name).open("wb") as target:
        call("exec-out","run-as",app,"cat","files/"+name,stdout=target)
