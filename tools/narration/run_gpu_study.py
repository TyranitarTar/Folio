"""Bounded, resumable GPU-placement qualification; never writes production jobs."""
from pathlib import Path
import json
import subprocess
import sys
import time
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from tools.narration.core import save_json
from tools.studio.processes import active_narrators,keep_awake,live_lease

def main():
    audit=ROOT/'android-reader/audit/studio-0.3-gpu';audit.mkdir(exist_ok=True)
    if active_narrators(ROOT,ROOT/'output/FolioStudio-data'):raise RuntimeError('Another renderer is active')
    lease=ROOT/'tools/narration/jobs/benchmark-studio-0.3/job.lock';lease.parent.mkdir(exist_ok=True)
    if lease.exists():
        if live_lease(lease):raise RuntimeError('Study already running')
        lease.unlink()
    save_json(lease,dict(pid=__import__('os').getpid(),started=time.time()))
    python=ROOT/'tools/narration/.venv-dml/Scripts/python.exe'
    results=[];keep_awake(True)
    try:
        for component in ('pico-int8','plapre3','kanade','hift'):
            name='strict-'+component;dest=audit/(name+'.json')
            if not dest.exists():
                save_json(audit/'status.json',dict(state='running',trial=name))
                with (audit/(name+'.log')).open('w',encoding='utf-8') as log:
                    try:
                        run=subprocess.run([str(python),'-u',str(ROOT/'tools/narration/gpu_probe.py'),'onnx',
                            '--model',component,'--backend','dml','--strict-gpu','--threads','2',
                            '--tokens','20','--samples','3','--output',str(dest)],cwd=ROOT,
                            stdout=log,stderr=subprocess.STDOUT,timeout=180,creationflags=subprocess.CREATE_NO_WINDOW)
                        code=run.returncode
                    except subprocess.TimeoutExpired:code='timeout'
                if not dest.exists():save_json(dest,dict(passed=False,error=str(code)))
            record=json.loads(dest.read_text(encoding='utf-8'))
            results.append(dict(component=component,passed=record.get('passed',False),error=record.get('error'),wallSeconds=record.get('wallSeconds'),evidence=dest.name))
            save_json(audit/'qualification.json',results)
        save_json(audit/'status.json',dict(state='qualification complete',results=results))
    finally:keep_awake(False);lease.unlink(missing_ok=True)

if __name__=='__main__':main()
