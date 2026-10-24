"""Run bounded Studio trials sequentially, excluding competing book renderers."""
from pathlib import Path
import json
import os
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from tools.narration.core import save_json
from tools.studio.processes import active_narrators,live_lease,keep_awake


def main():
    audit=ROOT/'android-reader/audit/studio-0.2.0';audit.mkdir(parents=True,exist_ok=True)
    data=ROOT/'output/FolioStudio-data'
    if active_narrators(ROOT,data):raise RuntimeError('A narration job is already active; do not compete for its resources')
    lease=ROOT/'tools/narration/jobs/benchmark-studio-0.2.0/job.lock';lease.parent.mkdir(parents=True,exist_ok=True)
    if lease.exists():
        if live_lease(lease):raise RuntimeError('Benchmark already active')
        lease.unlink()
    with lease.open('x') as f:json.dump(dict(pid=os.getpid(),started=time.time()),f)
    trials=[(engine,'onnx-directml',parallel,threads) for engine in ('pico','plapre3') for parallel,threads in ((1,4),(2,2),(2,4),(4,2))]
    trials += [('chatterbox-nano','cpu',1,4),('chatterbox-nano','directml-acoustic',1,2),('chatterbox-nano','directml-acoustic',1,4),('chatterbox-nano','directml-acoustic',1,8)]
    results=[];keep_awake(True)
    try:
        for index,(engine,backend,parallel,threads) in enumerate(trials):
            name=f'{engine}-{backend}-p{parallel}-t{threads}';folder=audit/name
            result=folder/'result.json'
            save_json(audit/'status.json',dict(state='running',trial=name,index=index+1,total=len(trials),pid=os.getpid()))
            print('Trial',index+1,'/',len(trials),name,flush=True)
            if result.is_file():
                results.append(json.loads(result.read_text(encoding='utf-8')));continue
            runtime=ROOT/('tools/narration/.venv/Scripts/python.exe' if backend=='cpu' else 'tools/narration/.venv-dml/Scripts/python.exe')
            args=[str(runtime),'-u',str(ROOT/'tools/narration/benchmark_parallel.py'),'--engine',engine,'--backend',backend,
                  '--parallel',str(parallel),'--threads',str(threads),'--output',str(folder)]
            with (audit/(name+'.log')).open('w',encoding='utf-8') as log:
                run=subprocess.run(args,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=600,
                    creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
            if run.returncode or not result.is_file():
                results.append(dict(engine=engine,backend=backend,parallel=parallel,threads=threads,failed=True,log=name+'.log'))
            else:results.append(json.loads(result.read_text(encoding='utf-8')))
            save_json(audit/'summary.json',results)
        save_json(audit/'summary.json',results)
        save_json(audit/'status.json',dict(state='complete',total=len(trials)))
        print('All trials finished',flush=True)
    finally:keep_awake(False);lease.unlink(missing_ok=True)


if __name__=='__main__':main()
