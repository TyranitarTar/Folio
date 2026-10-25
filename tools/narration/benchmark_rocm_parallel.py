"""Bounded HIP concurrency comparison using authored text and fixed seeds."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import argparse
import os
import sys
import threading
import time
import traceback

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--parallel',type=int,choices=(1,2,4),default=1)
    p.add_argument('--copies',type=int,choices=(1,2),default=1)
    p.add_argument('--count',type=int,choices=range(1,9),default=4)
    p.add_argument('--repeat',type=int,default=2)
    args=p.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    from tools.narration.rocm_trial_support import gpu_trial_lease,write_trial_json
    with gpu_trial_lease(ROOT):
        result=dict(passed=False,parallel=args.parallel,copies=args.copies,passages=[],state='starting',started=time.time())
        def save(state=None):
            if state:result['state']=state;print(state,flush=True)
            write_trial_json(args.output/'result.json',result)
        from tools.narration.benchmark_parallel import memory,DA
        from tools.narration.gpu_memory import GpuMemorySampler
        gpu=GpuMemorySampler();stop=threading.Event();peaks={}
        def monitor():
            while not stop.wait(.1):
                for k,v in dict(memory(),**gpu.sample()).items():peaks[k]=max(v,peaks.get(k,0))
        watcher=threading.Thread(target=monitor,daemon=True);watcher.start()
        try:
            import torch
            import soundfile as sf
            from tools.studio.profiles import prepare_rocm_plapre
            from tools.narration.rocm_backend import RocmPlapreAdapter
            torch.set_num_interop_threads(1)
            folder=prepare_rocm_plapre(ROOT,ROOT/'output/FolioStudio-data')
            save('loading')
            before=time.perf_counter()
            models=[RocmPlapreAdapter(folder,dict(voice='ida',top_k=50,temperature=.7,top_p=.95),threads=2)]
            # Two copies remain bounded to two requests; don't multiply models
            # and active KV caches independently on an 8 GB card.
            if args.copies==2:
                if args.parallel!=2:raise ValueError('Two copies require two requests')
                models.append(RocmPlapreAdapter(folder,models[0].sampling,threads=2))
                # Keep the same validated acoustic scheduling as shared-model
                # trials; isolate the effect of duplicating model weights.
                models[1].decode_lock=models[0].decode_lock
            result['loadSeconds']=time.perf_counter()-before
            result['execution']=models[0].execution
            save('warmup')
            for i,model in enumerate(models):model.render('Hun åbner sin bog og læser videre.',20261024)
            local=threading.local();allocation=threading.Lock();assigned=iter(models if len(models)>1 else models*args.parallel)
            def render(item):
                index,repeat,text=item
                if not hasattr(local,'model'):
                    with allocation:local.model=next(assigned)
                wave,row=local.model.render(text,20261022+index)
                row.update(passageIndex=index,repeat=repeat)
                sf.write(args.output/f'passage-{index+1:03d}-repeat-{repeat+1}.wav',wave,24000,subtype='PCM_16')
                return row
            save('generating')
            result['batches']=[]
            for repeat in range(args.repeat):
                # New workers per batch make stream ownership explicit. All
                # requests share weights when copies=1.
                assigned=iter(models if len(models)>1 else models*args.parallel)
                begin=time.perf_counter()
                with ThreadPoolExecutor(max_workers=args.parallel) as pool:
                    rows=list(pool.map(render,[(i,repeat,text) for i,text in enumerate(DA[:args.count])]))
                elapsed=time.perf_counter()-begin
                result['passages'].extend(rows)
                result['batches'].append(dict(wallSeconds=elapsed,audioSeconds=sum(r['audioSeconds'] for r in rows)))
                save()
            result.update(passed=True,wallSeconds=sum(b['wallSeconds'] for b in result['batches']),
                audioSeconds=sum(b['audioSeconds'] for b in result['batches']),
                peakGpuAllocatedBytes=torch.cuda.max_memory_allocated(),peakGpuReservedBytes=torch.cuda.max_memory_reserved())
            save('complete')
        except Exception as exc:
            result.update(error=str(exc),traceback=traceback.format_exc());print(result['traceback'],flush=True);save('failed')
        finally:
            stop.set();watcher.join(timeout=1);gpu.close()
            result.update(memory=peaks,finished=time.time(),gpuMemoryCounterError=gpu.error);save()
        return 0 if result['passed'] else 1


if __name__=='__main__':raise SystemExit(main())
