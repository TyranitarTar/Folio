"""Real authored-book exports for all four engines, CPU and GPU registrations."""
from pathlib import Path
import json
import os
import sys
import time
import array
import math
import wave

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from tools.narration.core import create_job,load_json,save_json,sha256_file
from tools.narration import validate_studio_020 as checks
from tools.studio.processes import active_narrators,live_lease,keep_awake

AUDIT=ROOT/'android-reader/audit/studio-0.2.1'


def main():
    if active_narrators(ROOT,ROOT/'output/FolioStudio-data'):
        raise RuntimeError('Wait for the active renderer before validating exports')
    lease=ROOT/'tools/narration/jobs/validation-studio-0.2.1/job.lock'
    lease.parent.mkdir(parents=True,exist_ok=True)
    if lease.exists():
        if live_lease(lease):raise RuntimeError('Validation already active')
        lease.unlink()
    with lease.open('x') as stream:json.dump(dict(pid=os.getpid(),started=time.time()),stream)
    keep_awake(True)
    try:
        checks.AUDIT=AUDIT;checks.authored_epubs()
        recommendations=load_json(AUDIT/'recommendations.json')
        # Compare the low-memory Røst loader to the ordinary upstream loader
        # in separate processes, preserving one model instance at a time.
        folder=AUDIT/'roest-upstream-loader-reference'
        if not (folder/'result.json').is_file():
            checks.run([checks.runtime('directml-acoustic'),ROOT/'tools/narration/benchmark_parallel.py',
                '--engine','roest','--backend','directml-acoustic','--parallel',1,'--threads',2,
                '--count',4,'--reference-loader','--output',folder],AUDIT/'roest-loader-parity.log')
        before=load_json(folder/'result.json')
        after=load_json(AUDIT/'roest-p1-t2-n4-r1/result.json')
        comparisons=[]
        for index,(old,new) in enumerate(zip(before['passages'],after['passages']),1):
            assert old['tokenSha256']==new['tokenSha256'] and old['samples']==new['samples']
            def samples(path):
                with wave.open(str(path),'rb') as reader:
                    values=array.array('h');values.frombytes(reader.readframes(reader.getnframes()));return values
            old_audio=samples(folder/f'passage-{index:03d}.wav')
            new_audio=samples(AUDIT/'roest-p1-t2-n4-r1'/f'passage-{index:03d}.wav')
            error=math.sqrt(sum((a-b)**2 for a,b in zip(old_audio,new_audio))/len(old_audio))
            rms=math.sqrt(sum(a*a for a in old_audio)/len(old_audio))
            peak=max(abs(a-b) for a,b in zip(old_audio,new_audio))/32768
            assert error/max(1,rms)<.001 and peak<.01,(error,rms,peak)
            comparisons.append(dict(relativeRmsError=error/max(1,rms),peakAbsoluteError=peak,
                                    byteIdentical=old['sha256']==new['sha256']))
        save_json(AUDIT/'roest-loader-parity.json',dict(passed=True,comparisons=comparisons,
            upstreamResidentBytes=before['memory']['peakWorking'],lowMemoryResidentBytes=after['memory']['peakWorking']))
        jobs=[]
        for engine in ('pico','plapre3','roest','chatterbox-nano'):
            gpu='onnx-directml' if engine in ('pico','plapre3') else 'directml-acoustic'
            jobs.extend([(engine,'cpu',2,2,False),(engine,gpu,4,recommendations[engine]['threads'],False)])
        jobs.append(('chatterbox-nano','directml-acoustic',2,2,True))
        records=[]
        for engine,backend,parallel,threads,custom in jobs:
            name=engine+'-'+backend+('-reference' if custom else '')
            save_json(AUDIT/'export-status.json',dict(state='running',profile=name))
            print('Export',name,flush=True)
            source=AUDIT/('authored-en.epub' if engine=='chatterbox-nano' else 'authored-da.epub')
            reference=ROOT/'tools/narration/voices/mic_trimmed.wav' if engine=='roest' or custom else None
            directory,plan=create_job(source,engine,jobs_root=AUDIT/'export-jobs',accelerator=backend,
                voice='ida' if engine in ('pico','plapre3') else None,voice_reference=reference)
            model=(ROOT/'output/FolioStudio-data/models'/engine if engine in ('pico','plapre3')
                   else ROOT/'tools/narration/models'/engine)
            python=checks.runtime('onnx-directml' if engine in ('pico','plapre3') else backend)
            arguments=[python,ROOT/'tools/narration/narrate.py','generate','--job',directory,
                '--model-dir',model,'--parallel-passages',parallel,'--cpu-threads',threads,'--through-sentences',3]
            progress=directory/'progress.json'
            if not progress.exists() or load_json(progress).get('phase')!='preview-ready':
                checks.run(arguments,AUDIT/('export-'+name+'.log'))
            record=checks.verify_export(directory,3)
            execution=load_json(progress)['execution']
            record.update(engine=engine,backend=backend,customReference=custom,
                stageMetrics=execution.get('stageMetrics'),gpuScheduling=execution.get('gpuScheduling'))
            assert record['execution']['peakConcurrentPassages']>=2
            records.append(record)
            save_json(AUDIT/'exports-result.json',records)
        # Extend a real staged job and prove existing audio is reused unchanged.
        engine,backend='chatterbox-nano','directml-acoustic'
        directory,_=create_job(AUDIT/'authored-en.epub',engine,jobs_root=AUDIT/'export-jobs',accelerator=backend)
        cached={p.name:(sha256_file(p),p.stat().st_mtime_ns) for p in (directory/'chunks').glob('*.wav')}
        arguments=[checks.runtime(backend),ROOT/'tools/narration/narrate.py','generate','--job',directory,
            '--model-dir',ROOT/'tools/narration/models/chatterbox-nano','--parallel-passages',2,
            '--cpu-threads',2,'--through-sentences',5]
        checks.run(arguments,AUDIT/'export-nano-resume.log')
        assert all((sha256_file(directory/'chunks'/name),(directory/'chunks'/name).stat().st_mtime_ns)==value for name,value in cached.items())
        resumed=checks.verify_export(directory,5)
        save_json(AUDIT/'resume-result.json',dict(passed=True,cachedPassagesReused=len(cached),export=resumed))
        save_json(AUDIT/'export-status.json',dict(state='complete',profiles=len(records),resumedCachedPassages=len(cached)))
        print('All registrations exported and resume preserved cached audio',flush=True)
    except Exception as exc:
        save_json(AUDIT/'export-status.json',dict(state='failed',error=str(exc)))
        raise
    finally:
        keep_awake(False);lease.unlink(missing_ok=True)


if __name__=='__main__':main()
