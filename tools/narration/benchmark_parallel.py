"""Bounded, authored-text throughput trials; never opens a private book."""
import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import sys
import threading
import time

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from tools.narration.core import ENGINES,save_json,_as_float_samples,_write_wav
from tools.narration.engines import load_engine
from tools.narration.parallel import PassagePool

DA=[
 'Regnen var holdt op, da hun lukkede bogen. På den anden side af vinduet sad katten og ventede på, at døren blev åbnet.',
 'Bibliotekaren fandt et gammelt brev mellem siderne. Det var dateret den syttende april, men ingen af dem kunne genkende navnet nederst på papiret.',
 'Du kommer vel tilbage i morgen, sagde han. Hun nikkede og lagde nøglen på bordet, hvor morgensolen tegnede en lys stribe over træet.',
 'På stationen kostede en kop kaffe femogtyve kroner. Toget mod København var forsinket, så de satte sig på bænken og delte den sidste bolle.',
 'Miso skubbede forsigtigt til koppen med sin pote. Da den ikke flyttede sig, så han op på hende med et udtryk af dyb fornærmelse.',
 'Hun havde læst den samme sætning tre gange uden at forstå den. Nu lukkede hun øjnene et øjeblik og lyttede til vinden i træerne.',
 'Der var hverken lys eller mennesker i huset. Alligevel kunne han tydeligt høre en melodi fra værelset for enden af den smalle gang.',
 'Vi tager den lange vej hjem, foreslog hun. Der er stadig tid nok, og jeg vil gerne vise dig broen, før mørket falder på.',
]
EN=[
 'The rain had stopped by the time she closed her book. Across the garden, a small cat waited patiently beside the gate.',
 'I thought you would come back tomorrow, he said. She placed the key on the table and smiled, but did not answer him immediately.',
 'At the station, a cup of coffee cost three pounds and fifty pence. Their train was late, so they settled down to wait together.',
 'There was an old letter between the pages. Neither of them recognised the handwriting, although the name at the bottom seemed strangely familiar.',
 'The librarian looked over her glasses. You may borrow the book, she said, provided you promise to bring it back before Friday afternoon.',
 'Miso touched the empty bowl with one careful paw. When nothing happened, he turned towards her with an expression of considerable disappointment.',
 'They took the longer road home, past the river and the little wooden bridge. The evening air smelled of wet grass and distant smoke.',
 'She read the final sentence slowly. Then she shut the book and listened to the wind moving through the trees outside her window.',
]


def memory():
    from ctypes import wintypes as w
    class Counters(ctypes.Structure):
        _fields_=[('cb',w.DWORD),('pageFaults',w.DWORD)]+[(n,ctypes.c_size_t) for n in
            ('peakWorking','working','peakPaged','paged','peakNonPaged','nonPaged','pagefile','peakPagefile','private')]
    counter=Counters();counter.cb=ctypes.sizeof(counter)
    kernel=ctypes.WinDLL('kernel32');kernel.GetCurrentProcess.restype=w.HANDLE
    psapi=ctypes.WinDLL('psapi');psapi.GetProcessMemoryInfo.argtypes=[w.HANDLE,ctypes.c_void_p,w.DWORD]
    if not psapi.GetProcessMemoryInfo(kernel.GetCurrentProcess(),ctypes.byref(counter),counter.cb):raise ctypes.WinError()
    return dict(working=counter.working,peakWorking=counter.peakWorking,private=counter.private)


def main():
    p=argparse.ArgumentParser();p.add_argument('--engine',required=True,choices=['pico','plapre3','roest','chatterbox-nano'])
    p.add_argument('--backend',default='onnx-directml');p.add_argument('--threads',type=int,default=2)
    p.add_argument('--parallel',type=int,choices=[1,2,4],default=1);p.add_argument('--repeat',type=int,default=1)
    p.add_argument('--count',type=int,default=8,choices=range(1,9))
    p.add_argument('--reference-loader',action='store_true',help='Røst upstream loader parity check only')
    p.add_argument('--output',type=Path,required=True);args=p.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    data=ROOT/'output/FolioStudio-data';folder=data/'models'/args.engine if args.engine in ('pico','plapre3') else ROOT/'tools/narration/models'/args.engine
    reference=ROOT/'tools/narration/voices/mic_trimmed.wav' if args.engine=='roest' else None
    settings={k:ENGINES[args.engine][k] for k in ('temperature','top_p','top_k','repetition_penalty')}
    settings['voice']='ida'
    from tools.narration.gpu_memory import GpuMemorySampler
    gpu_memory=GpuMemorySampler()
    stop=threading.Event();peaks=dict(working=0,peakWorking=0,private=0)
    def monitor():
        while not stop.wait(.1):
            sample=dict(memory(),**gpu_memory.sample())
            for k,v in sample.items():peaks[k]=max(peaks.get(k,0),v)
    watcher=threading.Thread(target=monitor,daemon=True);watcher.start();started=time.monotonic()
    try:
        if args.reference_loader:
            if args.engine!='roest':raise ValueError('Reference loader comparison is Røst-only')
            from chatterbox.tts_turbo import ChatterboxTurboTTS
            from tools.narration import nano_backend
            nano_backend.load_roest=lambda folder:ChatterboxTurboTTS.from_local(str(folder),'cpu')
        model=load_engine(args.engine,folder,reference,settings,args.threads,args.backend)
        if args.reference_loader:model.execution['loader']='upstream-ordinary-reference'
        load_seconds=time.monotonic()-started
        if args.engine in ('roest','chatterbox-nano'):
            import chatterbox.models.t3.t3 as t3module
            import chatterbox.models.s3gen.flow_matching as flowmodule
            t3module.tqdm=lambda iterator,*_a,**_k:iterator
            if hasattr(flowmodule,'tqdm'):flowmodule.tqdm=lambda iterator,*_a,**_k:iterator
        texts=(EN if args.engine=='chatterbox-nano' else DA)[:args.count]*args.repeat
        def render(item):
            index,text=item;seed=20261022+index
            audio=model.synthesize(text,seed=seed)
            diagnostic = getattr(model.state.local,'last_request',{}) if hasattr(model,'state') else {}
            return _as_float_samples(audio), diagnostic
        # Warm-up is separate and is counted in total wall time.
        render((999,texts[0]));rows=[];pool=PassagePool(render,args.parallel)
        if hasattr(model,'state'):model.state.reset_metrics()
        if hasattr(model,'state'):
            import torch
            global_random_before=torch.random.get_rng_state().clone()
        synthesis_start=time.monotonic()
        pool.begin([(i,(i,text)) for i,text in enumerate(texts)])
        try:
            for i,text in enumerate(texts):
                audio,diagnostic=pool.get(i,(i,text));path=args.output/f'passage-{i+1:03d}.wav'
                _write_wav(path,audio,24000)
                if len(audio)<2400:raise ValueError('Implausibly short returned passage')
                rows.append(dict(index=i,text=text,samples=len(audio),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),**dict(diagnostic,seed=20261022+i)))
                print(f'{args.engine} {args.parallel} passages / {args.threads} threads: {i+1}/{len(texts)}',flush=True)
        finally:pool.close()
        synth_seconds=time.monotonic()-synthesis_start;audio_seconds=sum(r['samples'] for r in rows)/24000
        result=dict(engine=args.engine,backend=args.backend,threads=args.threads,parallel=args.parallel,
            benchmarkVersion='shared-stages-v2',count=args.count,repeat=args.repeat,
            modelCopies=1,peakConcurrentPassages=pool.peak_active,loadSeconds=load_seconds,
            synthesisSeconds=synth_seconds,audioSeconds=audio_seconds,rtf=synth_seconds/audio_seconds,
            audioMinutesPerComputeMinute=audio_seconds/synth_seconds,totalSeconds=time.monotonic()-started,
            memory=peaks,gpuMemoryCounterError=gpu_memory.error,execution=model.execution,passages=rows)
        if hasattr(model,'metrics'):result['stageMetrics']=model.metrics()
        if hasattr(model,'state'):
            result['globalRandomStatePreserved']=bool(torch.equal(global_random_before,torch.random.get_rng_state()))
            if not result['globalRandomStatePreserved']:raise RuntimeError('A passage changed global Torch random state')
        save_json(args.output/'result.json',result)
        print(json.dumps({k:v for k,v in result.items() if k!='passages'},indent=2),flush=True)
    finally:stop.set();watcher.join(timeout=1);gpu_memory.close()


if __name__=='__main__':main()
