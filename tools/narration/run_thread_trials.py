"""Resumable authored-text thread sweep and shared-stage concurrency checks."""
from pathlib import Path
import json
import os
import subprocess
import sys
import time
import array
import wave
import math

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from tools.narration.core import load_json,save_json
from tools.studio.processes import active_narrators,live_lease,keep_awake

AUDIT=ROOT/'android-reader/audit/studio-0.2.1'
THREADS=(2,4,6,8,12,16)


def trial(engine,threads,parallel,count=4,repeat=1):
    backend='onnx-directml' if engine in ('pico','plapre3') else 'directml-acoustic'
    name=f'{engine}-p{parallel}-t{threads}-n{count}-r{repeat}'
    folder=AUDIT/name;result=folder/'result.json'
    save_json(AUDIT/'status.json',dict(state='running',trial=name,pid=os.getpid()))
    print(name,flush=True)
    if result.is_file():
        saved=load_json(result)
        if saved.get('benchmarkVersion')=='shared-stages-v2':return saved
        raise RuntimeError('Unexpected old evidence in '+name)
    arguments=[ROOT/'tools/narration/.venv-dml/Scripts/python.exe','-u',ROOT/'tools/narration/benchmark_parallel.py',
               '--engine',engine,'--backend',backend,'--threads',threads,'--parallel',parallel,
               '--count',count,'--repeat',repeat,'--output',folder]
    started=time.monotonic()
    with (AUDIT/(name+'.log')).open('w',encoding='utf-8') as log:
        completed=subprocess.run([str(a) for a in arguments],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,
             timeout=600,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    if completed.returncode or not result.is_file():
        raise RuntimeError('Trial failed: '+name+'; inspect its log before continuing')
    print(f'Finished in {time.monotonic()-started:.1f}s',flush=True)
    return load_json(result)


def compare(reference,actual):
    def name(result):
        return f"{result['engine']}-p{result['parallel']}-t{result['threads']}-n{result['count']}-r{result['repeat']}"
    if len(reference['passages'])!=len(actual['passages']):raise RuntimeError('Passage count differs')
    checks=[]
    for index,(before,after) in enumerate(zip(reference['passages'],actual['passages']),1):
        if before['text']!=after['text'] or before['seed']!=after['seed']:raise RuntimeError('Corpus mismatch')
        if reference['engine'] in ('roest','chatterbox-nano') and not (before.get('tokenSha256') and after.get('tokenSha256')):
            raise RuntimeError('Speech token diagnostics are missing')
        if before.get('tokenSha256')!=after.get('tokenSha256'):raise RuntimeError('Speech token sequence changed')
        if before['samples']!=after['samples']:raise RuntimeError('Audio length changed')
        identical=before['sha256']==after['sha256'];maximum=0;relative=0.0
        if not identical:
            def pcm(result):
                with wave.open(str(AUDIT/name(result)/f'passage-{index:03d}.wav'),'rb') as reader:
                    assert reader.getsampwidth()==2 and reader.getnchannels()==1 and reader.getframerate()==24000
                    values=array.array('h');values.frombytes(reader.readframes(reader.getnframes()));return values
            left,right=pcm(reference),pcm(actual)
            if len(left)!=len(right):raise RuntimeError('Decoded PCM lengths differ')
            maximum=max(abs(a-b) for a,b in zip(left,right))
            error=math.sqrt(sum((a-b)**2 for a,b in zip(left,right))/len(left))
            rms=math.sqrt(sum(a*a for a in left)/len(left))
            relative=error/max(1,rms)
            # Changing CPU reduction/FFT thread counts can change float32
            # rounding. Keep a stricter bound for concurrency at fixed threads.
            absolute_limit=1e-4 if reference['threads']!=actual['threads'] else 1/32768
            if maximum/32768>absolute_limit or relative>1e-4:
                raise RuntimeError('Seeded waveform exceeds the bounded float32 roundoff tolerance')
        checks.append(dict(byteIdentical=identical,maxPcmDifference=maximum,relativeRmsError=relative))
    quality=dict(reference=name(reference),actual=name(actual),passed=True,
        byteIdentical=all(row['byteIdentical'] for row in checks),
        maxPcmDifference=max(row['maxPcmDifference'] for row in checks),passages=checks)
    quality['absoluteAmplitudeTolerance']=1e-4 if reference['threads']!=actual['threads'] else 1/32768
    save_json(AUDIT/'quality'/(name(reference)+'--'+name(actual)+'.json'),quality)
    return quality


def main():
    AUDIT.mkdir(parents=True,exist_ok=True)
    if active_narrators(ROOT,ROOT/'output/FolioStudio-data'):
        raise RuntimeError('A renderer is already active; do not compete')
    lease=ROOT/'tools/narration/jobs/benchmark-studio-0.2.1/job.lock'
    lease.parent.mkdir(parents=True,exist_ok=True)
    if lease.exists():
        if live_lease(lease):raise RuntimeError('Thread benchmark already active')
        lease.unlink()
    with lease.open('x') as stream:json.dump(dict(pid=os.getpid(),started=time.time()),stream)
    results=[]
    def record(result):
        key=(result['engine'],result['parallel'],result['threads'],result['count'],result['repeat'])
        if not any((r['engine'],r['parallel'],r['threads'],r['count'],r['repeat'])==key for r in results):
            results.append(result);save_json(AUDIT/'summary.json',results)
        return result
    keep_awake(True)
    try:
        # Verify the new adapters before spending time on the wider sweep.
        for engine in ('chatterbox-nano','roest'):
            baseline=record(trial(engine,2,1))
            for parallel in (2,4):
                result=record(trial(engine,2,parallel))
                compare(baseline,result)
        recommendations={}
        for engine in ('pico','plapre3','roest','chatterbox-nano'):
            sequential=[record(trial(engine,t,1)) for t in THREADS]
            # Confirm outputs across thread counts as well as concurrency.
            for result in sequential:compare(sequential[0],result)
            best=min(sequential,key=lambda r:r['synthesisSeconds'])
            candidates=sequential[:]
            for threads in sorted({2,best['threads']}):
                baseline=next(r for r in sequential if r['threads']==threads)
                for parallel in (2,4):
                    result=record(trial(engine,threads,parallel))
                    compare(baseline,result);candidates.append(result)
            fastest=min(candidates,key=lambda r:r['synthesisSeconds'])
            # Prefer less lookahead/CPU use when results are within normal
            # short-trial variation, rather than spending memory for a tiny gain.
            nearby=[r for r in candidates if r['synthesisSeconds']<=fastest['synthesisSeconds']*1.03]
            winner=min(nearby,key=lambda r:(r['parallel'],r['threads']))
            # Eight passages confirm the selected configuration at a larger size.
            confirmation=record(trial(engine,winner['threads'],winner['parallel'],count=8))
            reference=record(trial(engine,winner['threads'],1,count=8))
            quality=compare(reference,confirmation)
            if engine in ('roest','chatterbox-nano'):
                # Exercise both exposed concurrent settings beyond the short
                # screening corpus, even when the recommended setting is 1.
                for parallel in (2,4):
                    stress=record(trial(engine,winner['threads'],parallel,count=8))
                    compare(reference,stress)
            recommendations[engine]=dict(threads=winner['threads'],parallel=winner['parallel'],
                selectionPolicy='lowest-concurrency-then-threads-within-3-percent-of-fastest-screening',
                fastestScreening=dict(threads=fastest['threads'],parallel=fastest['parallel'],synthesisSeconds=fastest['synthesisSeconds']),
                synthesisSeconds=confirmation['synthesisSeconds'],audioSeconds=confirmation['audioSeconds'],
                baselineSeconds=reference['synthesisSeconds'],speedup=reference['synthesisSeconds']/confirmation['synthesisSeconds'],
                memory=confirmation['memory'],stageMetrics=confirmation.get('stageMetrics'),
                seededOutputIdentical=quality['byteIdentical'],seededOutputWithinPcmTolerance=True,
                maxPcmDifference=quality['maxPcmDifference'])
            save_json(AUDIT/'recommendations.json',recommendations)
        save_json(AUDIT/'status.json',dict(state='complete',trials=len(results)))
        from tools.studio.recommendations import save_measured
        save_measured(ROOT/'output/FolioStudio-data',recommendations)
        print('All engines verified',flush=True)
    except Exception as exc:
        save_json(AUDIT/'status.json',dict(state='failed',error=str(exc)))
        raise
    finally:
        keep_awake(False);lease.unlink(missing_ok=True)


if __name__=='__main__':main()
