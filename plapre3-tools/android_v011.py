"""Run actual native/service checks only in the isolated Folio Test package."""
from pathlib import Path
import argparse,hashlib,json,subprocess
ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser()
parser.add_argument('--serial',required=True)
parser.add_argument('--label',default='emulator')
parser.add_argument('--suite',choices=['native','prepare','continuity','threads','regressions','samples','download'],default='native')
parser.add_argument('--install',action='store_true');parser.add_argument('--fixture',action='store_true')
args=parser.parse_args();assert args.label.replace('-','').isalnum()
adb=ROOT/'android-reader/toolchain/sdk/platform-tools/adb.exe';app='app.folio.reader.debug'
out=ROOT/'tools/plapre3/results/android'/args.label;out.mkdir(parents=True,exist_ok=True)
def call(*args_,**kwargs):return subprocess.run([str(adb),'-s',args.serial,*map(str,args_)],check=True,**kwargs)
def capture(*args_):return call(*args_,stdout=subprocess.PIPE).stdout.decode().strip()
if args.install:
    call('install','-r',ROOT/'android-reader/app/build/outputs/apk/debug/app-debug.apk')
    call('install','-r',ROOT/'android-reader/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if args.fixture:
    archive=ROOT/'output/voice-packs-plapre3-v1/Folio-Voice-Plapre3-FP32-v1.zip'
    remote='/data/local/tmp/folio-plapre3-v011.zip';call('push',archive,remote)
    try:
        call('shell','run-as',app,'cp',remote,'cache/plapre3-input.zip')
        expected=json.loads((archive.parent/'manifest.json').read_text(encoding='utf-8'))['sha256']
        assert capture('shell','run-as',app,'sha256sum','cache/plapre3-input.zip').split()[0]==expected
    finally:call('shell','rm',remote)
regressions=['PicoInstrumentedTest#nativeWaveformCancellationAndCheckpointRecovery',
             'PicoInstrumentedTest#realPicoAudioAppendsInOrderAtOnePointFiveWithoutEarlyCompletion',
             'SupertonicSpeechInstrumentedTest','ContinuousSpeechInstrumentedTest#kokoroPlaysSixColdPassagesWithoutMultiSecondPreparationGaps',
             'AppUpdatesInstrumentedTest','VoiceStorageInstrumentedTest']
names,count={
    'native':(['Plapre3InstrumentedTest'],4),
    'prepare':(['Plapre3ContinuityInstrumentedTest#prepareAheadKeepsBookmarkAndResumeUsesGeneratedCache'],1),
    'continuity':(['Plapre3ContinuityInstrumentedTest#coldPlapre3GeneratesAheadDuringTwentyPassagesOfServicePlayback'],1),
    'threads':(['Plapre3ThreadsInstrumentedTest'],1),
    'regressions':(regressions,7),
    'samples':(['VoicePacksInstrumentedTest#predownloadSampleDoesNotLoadModelsOrChangeVoiceOrBookCheckpoint'],1),
    'download':(['VoicePacksInstrumentedTest#actualPublicPacksCancelResumeVerifyInstallAndSynthesize'],1),
}[args.suite]
command=['shell','am','instrument','-w','-r','-e','class',','.join('app.folio.reader.'+n for n in names)]
if args.suite=='samples':command+=['-e','networkVoiceSamples','true']
if args.suite=='download':command+=['-e','downloadVoicePacks','true','-e','packBackend','plapre3']
command+=[app+'.test/androidx.test.runner.AndroidJUnitRunner']
log=out/(args.suite+'.log')
with log.open('w',encoding='utf-8') as target:call(*command,stdout=target,stderr=subprocess.STDOUT,timeout=2400)
text=log.read_text(encoding='utf-8');print(text,flush=True)
assert f'OK ({count} test' in text and 'FAILURES!!!' not in text and 'shortMsg=' not in text
assert text.count('INSTRUMENTATION_STATUS_CODE: 0')==count and 'INSTRUMENTATION_STATUS_CODE: -4' not in text
files={
    'native':['plapre3-native-validation.json','plapre3-playback-validation.json','plapre3-service-validation.json',
              'plapre3-padding-voices-validation.json','plapre3-native-da.wav',*[f'plapre3-padding-voice-{i}.wav' for i in range(5)]],
    'prepare':['plapre3-prepare-ahead-validation.json'],
    'continuity':['plapre3-continuity-validation.json'],
    'threads':['plapre3-thread-benchmark.json'],
    'download':['v9-voice-download-validation.json'],
}.get(args.suite,[])
for name in files:
    with (out/name).open('wb') as target:call('exec-out','run-as',app,'cat','files/'+name,stdout=target)
if args.suite=='native':call('shell','run-as',app,'rm','-f','cache/plapre3-input.zip')
print('Saved validated results to '+str(out),flush=True)
