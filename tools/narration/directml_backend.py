"""Experimental fixed-voice DirectML backends with explicit CPU-only operations."""
from __future__ import annotations
from types import MethodType
from contextlib import nullcontext
from .core import NarrationError

PROFILE = 'directml-t3-v1'
ACOUSTIC_PROFILE = 'directml-acoustic-v1'


def enable_directml(model, device_index: int = 0, acoustic: bool = False, token_inference=None, gpu_gate=None) -> dict:
    import torch
    try:
        import torch_directml
    except (ImportError, OSError) as exc:
        raise NarrationError('DirectML requires the separate tested GPU environment; the CPU environment has not been changed.') from exc
    if not torch_directml.is_available() or device_index >= torch_directml.device_count():
        raise NarrationError('The selected desktop DirectML GPU is unavailable; no automatic CPU fallback was used.')
    from chatterbox.models.t3.t3 import T3
    raw = token_inference or getattr(T3.inference_turbo, '__wrapped__', None)
    if raw is None:
        raise NarrationError('This Chatterbox token-generator version is not compatible with the validated DirectML adapter.')
    device = torch_directml.device(device_index)
    # Compute the constant reference embedding once on CPU. This also avoids an
    # empty-tensor concatenation unsupported by the tested DirectML plugin.
    with torch.no_grad():
        conditioning = model.t3.prepare_conditioning(model.conds.t3).detach()
        repeat = model.t3.prepare_conditioning(model.conds.t3).detach()
    if not torch.equal(conditioning, repeat):
        raise NarrationError('Fixed voice conditioning is not stable; refusing the cached DirectML path.')
    model.t3.tfmr.set_attn_implementation('eager')
    model.t3.to(device)
    conditioning = conditioning.to(device)
    model.t3.prepare_conditioning = lambda _cond: conditioning
    forward = MethodType(torch.no_grad()(raw), model.t3)

    def infer(*args, **kwargs):
        # Reference conditioning is already cached on GPU. Keep the original
        # condition object on CPU, and transfer only the changing text tokens.
        with gpu_gate if gpu_gate is not None else nullcontext():
            kwargs['text_tokens'] = kwargs['text_tokens'].to(device)
            return forward(*args, **kwargs).cpu()

    model.t3.inference_turbo = infer
    # The caller installs its EOS/token-budget guard around this function.
    execution = dict(profile=PROFILE, accelerator='directml', adapter=torch_directml.device_name(device_index).rstrip('\0'),
                tokenDevice=str(device), waveformDevice='cpu', conditioning='fixed-voice-cached',
                precision='float32', attention='eager', torch=torch.__version__)
    execution['gpuScheduling']='serialized-device-stages' if gpu_gate is not None else 'independent-stage-gates'
    if acoustic:
        enable_acoustic(model.s3gen, device, gpu_gate)
        execution.update(profile=ACOUSTIC_PROFILE, accelerator='directml-acoustic',
                         waveformDevice='mixed', flowDevice=str(device),
                         waveformConvolutionsDevice=str(device), fftDevice='cpu', excitationDevice='cpu',
                         cpuFallback='DirectML upsample_nearest1d; FFT/excitation explicitly on CPU')
    return execution


def enable_acoustic(s3gen, device, gpu_gate=None):
    import inspect
    import torch
    # Bind compatible decorators on this model only, leaving installed classes
    # and any separately loaded CPU model unchanged.
    for module in s3gen.modules():
        replaced = set()
        for cls in type(module).__mro__:
            for name, func in cls.__dict__.items():
                if name in replaced: continue
                replaced.add(name)
                if inspect.isfunction(func) and hasattr(func, '__wrapped__') and func.__module__.startswith('chatterbox'):
                    setattr(module, name, MethodType(torch.no_grad()(func.__wrapped__), module))

    def transfer(value, destination):
        if torch.is_tensor(value): return value.to(destination)
        if isinstance(value, tuple): return tuple(transfer(x,destination) for x in value)
        if isinstance(value, dict): return {k:transfer(v,destination) for k,v in value.items()}
        return value

    # The S3 tokenizer remains on CPU, so its device property keeps orchestration,
    # the reference and the outer meanflow-noise creation on CPU as before.
    flow = s3gen.flow
    original_flow = flow.inference
    flow.to(device)
    def gpu_flow(*args, **kwargs):
        with gpu_gate if gpu_gate is not None else nullcontext():
            return transfer(original_flow(*transfer(args,device), **transfer(kwargs,device)), 'cpu')
    flow.inference = gpu_flow

    hift = s3gen.mel2wav
    # Materialize inference weights once instead of invoking unsupported
    # DirectML weight-normalization operations on every convolution.
    for module in hift.modules():
        if torch.nn.utils.parametrize.is_parametrized(module, 'weight'):
            torch.nn.utils.parametrize.remove_parametrizations(module, 'weight', leave_parametrized=True)
    original_decode, original_stft, original_istft = hift.decode, hift._stft, hift._istft
    def cpu_stft(source): return transfer(original_stft(source.cpu()),device)
    def cpu_istft(magnitude,phase): return original_istft(magnitude.cpu(),phase.cpu())
    hift._stft, hift._istft = cpu_stft, cpu_istft
    # F0/excitation use the original CPU algorithm. Only learned waveform
    # convolutions move; real FFT inputs/results cross the boundary explicitly.
    for name in ('conv_pre','ups','source_downs','source_resblocks','resblocks','conv_post'):
        getattr(hift,name).to(device)
    def gpu_decode(*args, **kwargs):
        with gpu_gate if gpu_gate is not None else nullcontext():
            return original_decode(*transfer(args,device), **transfer(kwargs,device)).cpu()
    hift.decode = gpu_decode
