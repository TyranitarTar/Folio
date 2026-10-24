"""Experimental real-valued HiFT transforms, without complex DirectML tensors.

Not selected by production narration. The 16-point HiFT transform can be
expressed exactly as convolution and normalized overlap-add; model weights,
window, sampling and phase algorithm are unchanged.
"""

class RealHiFTTransform:
    def __init__(self, device, n_fft=16, hop=4):
        import torch
        self.n=n_fft;self.hop=hop
        k=torch.arange(n_fft//2+1,dtype=torch.float64)[:,None]
        t=torch.arange(n_fft,dtype=torch.float64)[None,:]
        angle=2*torch.pi*k*t/n_fft
        window=torch.hann_window(n_fft,periodic=True,dtype=torch.float64)
        forward=torch.cat((torch.cos(angle),-torch.sin(angle)),dim=0)*window
        factor=torch.full((n_fft//2+1,1),2.,dtype=torch.float64);factor[0]=1;factor[-1]=1
        inverse=torch.cat((torch.cos(angle)*factor,-torch.sin(angle)*factor),dim=0)*window/n_fft
        self.forward=forward[:,None].float().to(device)
        self.inverse=inverse[:,None].float().to(device)
        self.envelope=(window*window)[None,None].float().to(device)

    def stft(self, x):
        import torch.nn.functional as F
        padded=F.pad(x[:,None],(self.n//2,self.n//2),mode='reflect')
        result=F.conv1d(padded,self.forward,stride=self.hop)
        return result[:,:self.n//2+1],result[:,self.n//2+1:]

    def istft(self,magnitude,phase):
        import torch
        import torch.nn.functional as F
        magnitude=magnitude.clamp(max=100)
        spectrum=torch.cat((magnitude*torch.cos(phase),magnitude*torch.sin(phase)),dim=1)
        reconstructed=F.conv_transpose1d(spectrum,self.inverse,stride=self.hop)
        denominator=F.conv_transpose1d(torch.ones_like(magnitude[:,:1]),self.envelope,stride=self.hop)
        result=reconstructed/denominator.clamp(min=1e-11)
        return result[:,0,self.n//2:-self.n//2]


def main():
    import argparse,json,time,statistics
    from pathlib import Path
    import torch,torch_directml
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);args=p.parse_args()
    torch.set_num_threads(2);torch.manual_seed(42)
    device=torch_directml.device(0);transform=RealHiFTTransform(device)
    source=torch.randn(1,240000);window=torch.hann_window(16)
    original=torch.stft(source,16,4,16,window=window,return_complex=True)
    real,imag=transform.stft(source.to(device))
    stft_error=max((real.cpu()-original.real).abs().max().item(),(imag.cpu()-original.imag).abs().max().item())
    magnitude=torch.exp(torch.randn(1,9,60001));phase=torch.randn_like(magnitude).sin()
    reference=torch.istft(torch.complex(magnitude.clamp(max=100)*phase.cos(),magnitude.clamp(max=100)*phase.sin()),16,4,16,window=window)
    gm,gp=magnitude.to(device),phase.to(device)
    actual=transform.istft(gm,gp).cpu()
    error=(actual-reference).abs();relative=error.square().mean().sqrt().item()/reference.square().mean().sqrt().item()
    timings=[]
    for _ in range(5):
        started=time.perf_counter();transform.istft(gm,gp).cpu();timings.append(time.perf_counter()-started)
    cpu=[]
    for _ in range(5):
        started=time.perf_counter()
        torch.istft(torch.complex(magnitude.clamp(max=100)*phase.cos(),magnitude.clamp(max=100)*phase.sin()),16,4,16,window=window)
        cpu.append(time.perf_counter()-started)
    from tools.narration.gpu_memory import GpuMemorySampler
    telemetry=GpuMemorySampler();gpu_memory=telemetry.sample();telemetry.close()
    report=dict(adapter=torch_directml.device_name(0).rstrip('\0'),experiment='real-valued-HiFT-transforms-only',gpuMemory=gpu_memory,gpuCounterError=telemetry.error,
        gpuOnlySpeech=False,audioSeconds=10,stftMaxAbsoluteError=stft_error,istftMaxAbsoluteError=error.max().item(),
        istftRelativeRmsError=relative,gpuInverseSeconds=timings,cpuInverseSeconds=cpu,
        gpuMedian=statistics.median(timings),cpuMedian=statistics.median(cpu),
        passed=stft_error<1e-4 and error.max().item()<1e-4 and relative<1e-4,
        limits='Transform parity only; not a complete decoder, model-quality test, or proof of absent runtime CPU fallbacks.')
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report,indent=2));return 0 if report['passed'] else 1

if __name__=='__main__':raise SystemExit(main())
