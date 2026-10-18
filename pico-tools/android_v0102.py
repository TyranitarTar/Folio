"""Run uncached Pico checks in Folio's separate debug package on a selected USB device.

Never installs over production Folio and never clears phone/user library data.
"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess

root=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser()
parser.add_argument('--serial',required=True)
parser.add_argument('--label',default='device')
parser.add_argument('--suite',choices=['native','threads','continuity','prepare','storage'],default='native')
parser.add_argument('--install',action='store_true')
parser.add_argument('--fixture',action='store_true')
args=parser.parse_args()
assert args.label.replace('-','').isalnum()
adb=root/'android-reader/toolchain/sdk/platform-tools/adb.exe'
app='app.folio.reader.debug'
results=root/'tools/pico/results/v0102'/args.label
results.mkdir(parents=True,exist_ok=True)
def call(*command,**kwargs):
    return subprocess.run([str(adb),'-s',args.serial,*map(str,command)],check=True,**kwargs)
if args.install:
    call('install','-r',root/'android-reader/app/build/outputs/apk/debug/app-debug.apk')
    call('install','-r',root/'android-reader/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if args.fixture:
    archive=root/'output/voice-packs-pico-v1/Folio-Voice-Pico-v1.zip'
    remote='/data/local/tmp/folio-pico-v0102.zip'
    call('push',archive,remote)
    try:
        call('shell','run-as',app,'cp',remote,'cache/pico-input.zip')
        actual=call('shell','run-as',app,'sha256sum','cache/pico-input.zip',stdout=subprocess.PIPE).stdout.decode().split()[0]
        expected=hashlib.sha256()
        with archive.open('rb') as source:
            for block in iter(lambda:source.read(1024*1024),b''):expected.update(block)
        assert actual==expected.hexdigest(),'USB fixture checksum mismatch; do not run tests against an incomplete archive.'
    finally:call('shell','rm',remote)
classes={
    'native':('PicoInstrumentedTest',4),
    'threads':('PicoThreadsInstrumentedTest',1),
    'continuity':('PicoContinuityInstrumentedTest#coldPicoGeneratesAheadDuringTwentyPassagesOfServicePlayback',1),
    'prepare':('PicoContinuityInstrumentedTest#prepareAheadKeepsBookmarkAndResumeUsesGeneratedCache',1),
    'storage':('VoiceStorageInstrumentedTest',2),
}
name,count=classes[args.suite]
log=results/(args.suite+'.log')
powerBefore=call('shell','settings','get','global','low_power',stdout=subprocess.PIPE).stdout.decode().strip()
with log.open('w',encoding='utf-8') as target:
    call('shell','am','instrument','-w','-r','-e','class','app.folio.reader.'+name,
         app+'.test/androidx.test.runner.AndroidJUnitRunner',stdout=target,stderr=subprocess.STDOUT,timeout=1800)
text=log.read_text(encoding='utf-8')
print(text,flush=True)
assert f'OK ({count} test' in text and 'FAILURES!!!' not in text and 'shortMsg=' not in text,'Instrumentation failed; inspect '+str(log)
files={
    'native':['pico-native-validation.json','pico-playback-validation.json','pico-service-validation.json',
              'pico-native-da.wav','pico-padding-voices-validation.json',*[f'pico-padding-voice-{i}.wav' for i in range(5)]],
    'threads':['pico-thread-benchmark.json'],
    'continuity':['pico-continuity-validation.json'],
    'prepare':['pico-prepare-ahead-validation.json'],
    'storage':[],
}[args.suite]
for name in files:
    with (results/name).open('wb') as target:
        call('exec-out','run-as',app,'cat','files/'+name,stdout=target)
powerAfter=call('shell','settings','get','global','low_power',stdout=subprocess.PIPE).stdout.decode().strip()
(results/(args.suite+'-environment.json')).write_text(json.dumps(dict(
    hostObservedPowerSavingSettingBefore=powerBefore,hostObservedPowerSavingSettingAfter=powerAfter,
    usbConnected=True,notes='Power-saving setting recorded by ADB, separate from raw on-device benchmark measurements.'),indent=2)+'\n')
print('Saved results to '+str(results),flush=True)
