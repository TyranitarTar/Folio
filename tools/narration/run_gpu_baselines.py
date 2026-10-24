"""Same-runtime CPU/hybrid timing controls for the GPU-residency investigation."""
from pathlib import Path
import json
import os
import subprocess
import sys
import time
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from tools.narration.core import save_json,load_json
from tools.studio.processes import active_narrators,live_lease,keep_awake

def main():
    audit=ROOT/'android-reader/audit/studio-0.3-gpu';audit.mkdir(exist_ok=True)
    deadline=time.monotonic()+1800
    while active_narrators(ROOT,ROOT/'output/FolioStudio-data'):
        save_json(audit/'status.json',dict(state='waiting for saved conversion to exit'))
        if time.monotonic()>deadline:raise RuntimeError('Conversion still holds its model; benchmarks have not started')
        time.sleep(3)
    lease=ROOT/'tools/narration/jobs/benchmark-studio-0.3/job.lock';lease.parent.mkdir(exist_ok=True)
    if lease.exists():
        if live_lease(lease):raise RuntimeError('Study already running')
        lease.unlink()
    save_json(lease,dict(pid=os.getpid(),started=time.time()))
    keep_awake(True);results=[]
    try:
        for engine in ('pico','plapre3','roest','chatterbox-nano'):
            for backend in ('cpu','onnx-directml' if engine in ('pico','plapre3') else 'directml-acoustic'):
                for repeat in range(1,4):
                    name=f'{engine}-{backend}-r{repeat}';folder=audit/name;folder.mkdir(exist_ok=True)
                    result=folder/'result.json'
                    if not result.exists():
                        save_json(audit/'status.json',dict(state='baselines running',trial=name,pid=os.getpid()))
                        with (folder/'trial.log').open('w',encoding='utf-8') as log:
                            completed=subprocess.run([str(ROOT/'tools/narration/.venv-dml/Scripts/python.exe'),'-u',
                                str(ROOT/'tools/narration/benchmark_parallel.py'),'--engine',engine,'--backend',backend,
                                '--threads','2','--parallel','1','--count','4','--output',str(folder)],cwd=ROOT,
                                stdout=log,stderr=subprocess.STDOUT,timeout=600,creationflags=subprocess.CREATE_NO_WINDOW)
                        if completed.returncode:raise RuntimeError(f'{name} failed; inspect trial.log')
                    results.append(dict(trial=name,**load_json(result)))
                    save_json(audit/'baselines.json',results)
        save_json(audit/'status.json',dict(state='baselines complete',trials=len(results)))
        experiments=[('real-fft',ROOT/'tools/narration/gpu_real_fft.py',[])]
        for component in ('pico-int8','plapre3','kanade','hift'):
            experiments.append(('placement-'+component,ROOT/'tools/narration/gpu_probe.py',
                ['onnx','--model',component,'--backend','dml','--threads','2','--tokens','20','--samples','3']))
        for name,script,arguments in experiments:
            dest=audit/(name+'.json')
            if dest.exists():continue
            save_json(audit/'status.json',dict(state='GPU construction/placement experiment',trial=name))
            with (audit/(name+'.log')).open('w',encoding='utf-8') as log:
                try:
                    run=subprocess.run([str(ROOT/'tools/narration/.venv-dml/Scripts/python.exe'),'-u',str(script),*arguments,'--output',str(dest)],
                        cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=180,creationflags=subprocess.CREATE_NO_WINDOW)
                    if not dest.exists():save_json(dest,dict(passed=False,exitCode=run.returncode))
                except subprocess.TimeoutExpired:save_json(dest,dict(passed=False,error='180-second bounded probe timed out'))
        save_json(audit/'status.json',dict(state='complete',baselineTrials=len(results)))
    finally:keep_awake(False);lease.unlink(missing_ok=True)

if __name__=='__main__':main()
