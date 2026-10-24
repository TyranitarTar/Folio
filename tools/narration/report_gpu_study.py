"""Publish measured CPU/hybrid controls and GPU construction experiments."""
from pathlib import Path
import json,statistics,pprint,array,wave,math
ROOT=Path(__file__).resolve().parents[2]
AUDIT=ROOT/'android-reader/audit/studio-0.3-gpu'
NAMES={'pico':'Plapre Pico','plapre3':'Plapre 3','roest':'Røst 3','chatterbox-nano':'Chatterbox Nano'}

def main():
    records=json.loads((AUDIT/'baselines.json').read_text(encoding='utf-8'));rows=[];checks=[]
    for engine,name in NAMES.items():
        cpu=[r for r in records if r['engine']==engine and r['backend']=='cpu']
        hybrid=[r for r in records if r['engine']==engine and r['backend']!='cpu']
        assert len(cpu)==len(hybrid)==3
        def median(rows,key):return statistics.median(r[key] for r in rows)
        memory=json.loads((AUDIT/('gpu-memory-'+engine)/'result.json').read_text(encoding='utf-8'))
        assert not memory.get('gpuMemoryCounterError')
        rows.append(dict(engine=engine,name=name,cpuRtf=median(cpu,'rtf'),hybridRtf=median(hybrid,'rtf'),
            cpuRange=[min(r['rtf'] for r in cpu),max(r['rtf'] for r in cpu)],hybridRange=[min(r['rtf'] for r in hybrid),max(r['rtf'] for r in hybrid)],
            speedup=median(cpu,'synthesisSeconds')/median(hybrid,'synthesisSeconds'),audioSeconds=median(cpu,'audioSeconds'),
            cpuLoad=median(cpu,'loadSeconds'),hybridLoad=median(hybrid,'loadSeconds'),
            residentGb=memory['memory']['peakWorking']/1e9,commitGb=memory['memory']['private']/1e9,
            dedicatedGb=memory['memory']['dedicatedGpuBytes']/1e9,sharedGb=memory['memory']['sharedGpuBytes']/1e9))
        reference=cpu[0]
        for actual in cpu[1:]+hybrid:
            comparison=dict(engine=engine,trial=actual['trial'],sampleCountsEqual=True,tokensEqual=True,maxPcmDifference=0,relativeRmsError=0.0)
            for index,(a,b) in enumerate(zip(reference['passages'],actual['passages']),1):
                assert a['text']==b['text'] and a['seed']==b['seed']
                comparison['sampleCountsEqual'] &= a['samples']==b['samples']
                if a.get('tokenSha256') is not None:comparison['tokensEqual'] &= a['tokenSha256']==b.get('tokenSha256')
                if a['sha256']==b['sha256']:continue
                if a['samples']!=b['samples']:continue
                def pcm(trial):
                    with wave.open(str(AUDIT/trial['trial']/f'passage-{index:03d}.wav'),'rb') as reader:
                        result=array.array('h');result.frombytes(reader.readframes(reader.getnframes()));return result
                left,right=pcm(reference),pcm(actual)
                maximum=max(abs(a-b) for a,b in zip(left,right))
                rms=math.sqrt(sum(float(a)*a for a in left)/len(left))
                relative=math.sqrt(sum((a-b)**2 for a,b in zip(left,right))/len(left))/max(1,rms)
                comparison['maxPcmDifference']=max(comparison['maxPcmDifference'],maximum)
                comparison['relativeRmsError']=max(comparison['relativeRmsError'],relative)
            checks.append(comparison)
    fft=json.loads((AUDIT/'real-fft.json').read_text(encoding='utf-8'))
    decoder=json.loads((AUDIT/'hift-decoder-comparison.json').read_text(encoding='utf-8'))
    def timing(stage):return statistics.median(r['seconds'] for r in decoder['samples'] if r['stage']==stage and r['run']>=2)
    candidate=json.loads((AUDIT/'speech-gpu-plapre3.json').read_text(encoding='utf-8'))
    quality_summary={engine:dict(maxPcmDifference=max(q['maxPcmDifference'] for q in checks if q['engine']==engine),
        relativeRmsError=max(q['relativeRmsError'] for q in checks if q['engine']==engine),
        sampleCountsEqual=all(q['sampleCountsEqual'] for q in checks if q['engine']==engine),
        tokensEqual=all(q['tokensEqual'] for q in checks if q['engine']==engine)) for engine in NAMES}
    data=dict(date='23 October 2026',rows=rows,baselineTrials=len(records),qualitySummary=quality_summary,
        gpuOnlySpeechAvailable=False,fftParity=fft['passed'],decoderParity=decoder['passed'],
        fftGpuMedian=fft['gpuMedian'],fftCpuMedian=fft['cpuMedian'],
        decoderGpuReal=timing('hift-decode-dml-real-fft'),decoderHybrid=timing('hift-decode-dml-cpu-fft'),
        gpuLanguageModelRtf=sum(r['totalSeconds'] for r in candidate['samples'])/sum(r['audioSeconds'] for r in candidate['samples']),
        quality=checks)
    (AUDIT/'findings.json').write_text(json.dumps(data,indent=2),encoding='utf-8')
    bundled={k:v for k,v in data.items() if k!='quality'}
    (ROOT/'tools/studio/gpu_findings.py').write_text('"""Measured on the desktop; no automatic model/profile substitution."""\nSTUDY = '+pprint.pformat(bundled,width=110,sort_dicts=False)+'\n',encoding='utf-8')
    lines=['# Studio 0.3 GPU-pipeline evaluation','',
        '23 October 2026. Physical Ryzen 7 5800X / 16 GB host RAM / RX 5700 XT 8 GB, Windows 10; AMD driver 32.0.21030.2001. No drivers, installed runtime packages or model weights were changed.',
        '', '## Result', '',
        '**Keep the existing hybrid profiles as production defaults. No complete GPU-only speech pipeline is approved.** We tested actual language-model GPU placement and built/tested a real-valued GPU FFT and HiFT-decoder prototype. This is construction evidence, not merely a roadmap or a GPU-utilization screenshot.',
        '', '## Controlled CPU/hybrid timing', '',
        'Same existing DirectML Python/runtime for both CPU and hybrid, two CPU threads, one passage in flight, four authored passages, fixed voices/seeds. Three independent process trials per engine/backend; model load and one warm-up are excluded from synthesis RTF. RTF is seconds of synthesis per second of audio; lower is faster. These sequential controls do not replace the separately confirmed 0.2.1 parallel recommendations.', '',
        '| Engine | CPU median RTF (range) | Hybrid median RTF (range) | Speedup | CPU load s | Hybrid load s |',
        '|---|---:|---:|---:|---:|---:|']
    for r in rows:lines.append(f"| {r['name']} | {r['cpuRtf']:.3f} ({r['cpuRange'][0]:.3f}–{r['cpuRange'][1]:.3f}) | {r['hybridRtf']:.3f} ({r['hybridRange'][0]:.3f}–{r['hybridRange'][1]:.3f}) | {r['speedup']:.2f}× | {r['cpuLoad']:.2f} | {r['hybridLoad']:.2f} |")
    lines+=['','CPU-mode and hybrid per-engine inputs/seeds were checked. Sample counts and the recorded Røst/Nano speech-token hashes match. Pico/Plapre 3 cross-backend waveforms are **not numerically equivalent**: maximum relative RMS differences are about 1.4%, with a maximum PCM16 difference of 1870. Do not describe these as bounded floating-point roundoff or certified equal voice quality. The existing production hybrid algorithm was not changed by this study. Human listening remains open; see `findings.json` for per-trial values.','',
        '## Measured GPU allocations', '',
        'Separate single-passage GPU-profile runs sampled Windows per-process GPU Process Memory counters every 100 ms. These are sampled dedicated/shared GPU allocations, not a driver-budget/residency trace or a sustained-book peak. Host working-set and private-commit figures have different meanings; do not add them. Short allocations between samples can be missed.', '',
        '| Engine | Host peak resident GB | Sampled private commit GB | Sampled dedicated GPU GB | Sampled shared GPU GB |','|---|---:|---:|---:|---:|']
    for r in rows:lines.append(f"| {r['name']} | {r['residentGb']:.2f} | {r['commitGb']:.2f} | {r['dedicatedGb']:.2f} | {r['sharedGb']:.2f} |")
    lines+=['','## Placement and construction findings','',
        '- Pico INT8 and the existing FP32 LM export both failed DirectML session initialization with an invalid-parameter error. This qualifies these exports/runtime, not every possible Pico GPU implementation.',
        '- Plapre 3 and Kanade failed strict CPU-fallback-disabled qualification. Normal provider traces show CPU Gather/Unsqueeze/Concat work; Kanade also has CPU Mod and explicit memcpy events. HiFT passed strict qualification. Profile traces are retained, so provider selection is not being mistaken for operator placement.',
        f"- The complete Plapre 3 GPU-LM/acoustics candidate with GPU-bound KV cache produced 16.56 s of authored audio at RTF {data['gpuLanguageModelRtf']:.2f}. Its Python sampler/logit readback still runs on the host, so it is not pure GPU. This screening corpus differs from the four-passage baseline; do not claim an exact speed ratio between those tables. The candidate does not demonstrate a production throughput win.",
        f"- A new 16-point real-valued STFT/ISTFT uses GPU convolution and normalized overlap-add instead of unsupported complex tensors. On a 10-second signal, maximum inverse error was {fft['istftMaxAbsoluteError']:.3g} and relative RMS error {fft['istftRelativeRmsError']:.3g}; inverse-transform medians were {fft['gpuMedian']*1000:.2f} ms GPU and {fft['cpuMedian']*1000:.2f} ms CPU. This is transform-only work, not an audiobook speedup.",
        f"- The prototype also ran the actual Røst HiFT decoder on captured real inputs. After two warm-up runs, five-run medians were {data['decoderGpuReal']*1000:.2f} ms with GPU transforms and {data['decoderHybrid']*1000:.2f} ms with the current CPU-FFT bridge. Both passed the stated 1e-4 absolute/relative numeric bounds against the CPU decoder. The saved source/excitation is held constant within this comparison; this is not full end-to-end speech or a listening test.",
        '', '## What building a GPU-resident pipeline requires next', '',
        '1. Specialize/re-export Pico for supported DirectML shapes/operators, including speaker projection; test the smallest failing subgraph rather than replacing the model with an unreviewed voice.',
        '2. For the Plapre decoder, test bounded static-shape/KV-cache buckets and move embedding lookup, sampling and feedback off the per-token CPU round trip. Shape/control nodes need separate tracing; eliminating a CPU Shape/Gather label alone is not proof of a throughput gain.',
        '3. Extend the proven real-valued HiFT transforms to on-device F0/excitation, nearest-neighbor resize, reference conditioning and watermark stages for Røst/Nano. Preserve voice/randomness and device-wide serialization. Recheck whether old reference-only components can be released to reduce host RAM.',
        '4. Compare matched text/voice/seed candidates in repeated warm trials and a sustained chapter, with full export, pause/resume, memory and human listening checks, before promoting a new backend. Changed precision/model execution requires a distinct captured profile/cache identity.',
        '', 'Do not switch this RX 5700 XT/Windows 10 setup to an assumed supported ROCm installation: it is absent from AMD’s published Windows PyTorch support matrix, which lists newer GPUs and Windows 11. See [AMD’s matrix](https://rocm.docs.amd.com/projects/radeon-ryzen/en/latest/docs/compatibility/compatibilityrad/windows/windows_compatibility.html). A community port/other OS is a separate unmeasured investigation.',
        '', 'The fixed-shape and GPU-cache direction follows [DirectML execution-provider guidance](https://onnxruntime.ai/docs/execution-providers/DirectML-ExecutionProvider.html) and [ONNX Runtime I/O Binding](https://onnxruntime.ai/docs/performance/tune-performance/iobinding.html). No unsupported driver/runtime migration was attempted.',
        '', '## Evidence and reproduction','',
        '`android-reader/audit/studio-0.3-gpu`: `baselines.json`, per-trial WAVs/logs, `strict-*.json`, `placement-*.json` and provider traces, `speech-gpu-*.json`, `real-fft.json`, `hift-decoder-comparison.json`, `gpu-memory-*/result.json`, `findings.json`.',
        '', 'Use `run_gpu_study.py`, `run_gpu_baselines.py`, `run_gpu_candidates.py`, and the explicit `gpu_wave_probe.py --stage hift-real` comparison. Run one study at a time with no production renderer. The 24 baseline timing runs preceded emulator/build work. Construction/placement/screening probes are separate diagnostics and may include background activity; do not turn their small timing differences into whole-book speed guarantees. Completed baseline results are reused. The initial strict qualification is compatibility evidence, not speech timing. Model warm-up/phase counters, GPU allocations and process working set do not prove 100% utilization or a fully GPU-resident pipeline.',
        '', 'A sustained ten-minute GPU-only chapter and end-to-end GPU-only MP3/export timing remain untested because no complete candidate passed qualification. Human listening remains open. Production synthesis keeps the previously validated hybrid path; the new transforms are an isolated experiment, not silently enabled in users’ jobs.']
    (ROOT/'tools/studio/PERFORMANCE-0.3.0.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print('Published 24 timing trials, four allocation samples and GPU construction findings.')

if __name__=='__main__':main()
