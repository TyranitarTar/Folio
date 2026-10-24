"""Actual GPU-language-model candidates and dedicated GPU allocation samples."""
from pathlib import Path
import os,sys,time,subprocess
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from tools.narration.core import save_json
from tools.studio.processes import active_narrators,keep_awake

def main():
    if active_narrators(ROOT,ROOT/'output/FolioStudio-data'):raise RuntimeError('Another renderer is active')
    audit=ROOT/'android-reader/audit/studio-0.3-gpu'
    lease=ROOT/'tools/narration/jobs/benchmark-studio-0.3/job.lock'
    with lease.open('x') as f:__import__('json').dump(dict(pid=os.getpid(),started=time.time()),f)
    cases=[]
    cases.append(('hift-real-decoder',ROOT/'tools/narration/gpu_wave_probe.py',['--stage','hift-real','--output',str(audit/'hift-real-decoder.json')]))
    for model in ('pico-fp32','plapre3'):
        name='speech-gpu-'+model
        cases.append((name,ROOT/'tools/narration/gpu_onnx_speech.py',['--model',model,'--backend','dml','--placement','both','--io-binding','--threads','2','--samples','3','--output',str(audit/(name+'.json'))]))
    for engine in ('pico','plapre3','roest','chatterbox-nano'):
        name='gpu-memory-'+engine;folder=audit/name;folder.mkdir(exist_ok=True)
        cases.append((name,ROOT/'tools/narration/benchmark_parallel.py',['--engine',engine,'--backend','onnx-directml' if engine in ('pico','plapre3') else 'directml-acoustic','--threads','2','--parallel','1','--count','1','--output',str(folder)]))
    keep_awake(True)
    try:
        for name,script,arguments in cases:
            if (audit/(name+'-exit.json')).exists():continue
            save_json(audit/'candidate-status.json',dict(state='running',trial=name))
            with (audit/(name+'.log')).open('w',encoding='utf-8') as log:
                try:
                    child=subprocess.run([str(ROOT/'tools/narration/.venv-dml/Scripts/python.exe'),'-u',str(script),*arguments],cwd=ROOT,
                        stdout=log,stderr=subprocess.STDOUT,timeout=240,creationflags=subprocess.CREATE_NO_WINDOW)
                    code=child.returncode
                except subprocess.TimeoutExpired:code='timeout (240 seconds)'
            save_json(audit/(name+'-exit.json'),dict(exitCode=code))
        save_json(audit/'candidate-status.json',dict(state='complete'))
    finally:keep_awake(False);lease.unlink(missing_ok=True)

if __name__=='__main__':main()
