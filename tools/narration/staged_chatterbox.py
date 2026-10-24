"""Per-request random state and bounded, shared-weight Chatterbox stages.

Uses the installed MIT Chatterbox math unchanged. Function copies route random
draws through a request-local CPU Generator; installed classes/globals are never
patched. Each model stage has one lock, allowing token/audio stages to overlap.
"""
from contextlib import contextmanager
import inspect
import hashlib
import struct
import secrets
import threading
import time
from types import FunctionType, MethodType

from .engines import ChatterboxAdapter


class StageState:
    def __init__(self):
        import torch
        self.torch = torch
        self.local = threading.local()
        self.metrics_lock = threading.Lock()
        self.locks = {name: threading.Lock() for name in ('text', 'tokens', 'acoustics', 'watermark')}
        self.reset_metrics()

    def reset_metrics(self):
        with self.metrics_lock:
            self.active = set()
            self.last_change = time.monotonic()
            self.overlap_seconds = 0.0
            self.token_acoustic_overlap = 0.0
            self.peak_stages = 0
            self.timing = {name: dict(calls=0, seconds=0.0, waitingSeconds=0.0) for name in self.locks}

    def _change(self, name, enter):
        with self.metrics_lock:
            now = time.monotonic()
            if len(self.active) > 1:
                self.overlap_seconds += now - self.last_change
            if {'tokens','acoustics'} <= self.active:
                self.token_acoustic_overlap += now - self.last_change
            self.last_change = now
            if enter:
                self.active.add(name)
                self.peak_stages = max(self.peak_stages, len(self.active))
            else:
                self.active.remove(name)

    def wrap(self, name, function):
        def invoke(*args, **kwargs):
            queued = time.monotonic()
            with self.locks[name]:
                started = time.monotonic()
                self._change(name, True)
                try:
                    with self.torch.no_grad():
                        return function(*args, **kwargs)
                finally:
                    self._change(name, False)
                    with self.metrics_lock:
                        record = self.timing[name]
                        record['calls'] += 1
                        record['seconds'] += time.monotonic() - started
                        record['waitingSeconds'] += started - queued
        return invoke

    def snapshot(self):
        with self.metrics_lock:
            return dict(maxConcurrentStages=self.peak_stages, overlapSeconds=self.overlap_seconds,
                        tokenAcousticOverlapSeconds=self.token_acoustic_overlap,
                        stages={name: record.copy() for name, record in self.timing.items()})

    @contextmanager
    def request(self, seed=None):
        previous = getattr(self.local, 'generator', None)
        self.local.token_hash = hashlib.sha256()
        self.local.generator = self.torch.Generator(device='cpu').manual_seed(
            secrets.randbits(63) if seed is None else int(seed))
        try:
            yield
        finally:
            self.local.last_request = dict(tokenSha256=self.local.token_hash.hexdigest(),seed=seed)
            self.local.generator = previous

    def generator(self):
        generator = getattr(self.local, 'generator', None)
        if generator is None:
            raise RuntimeError('A model random draw occurred outside its passage context')
        return generator

    def function(self, function):
        """Copy a function's globals, replacing only its RNG entry points."""
        raw = inspect.unwrap(function)
        scope = dict(raw.__globals__, torch=RequestTorch(self))
        if 'tqdm' in scope:
            scope['tqdm'] = lambda iterable, *_args, **_kwargs: iterable
        if 'Uniform' in scope:
            state = self
            class RequestUniform:
                def __init__(self, low, high):
                    self.low, self.high = low, high

                def sample(self, sample_shape=()):
                    values = state.torch.empty(sample_shape).uniform_(0, 1, generator=state.generator())
                    return self.low + (self.high - self.low) * values
            scope['Uniform'] = RequestUniform
        copy = FunctionType(raw.__code__, scope, raw.__name__, raw.__defaults__, raw.__closure__)
        copy.__kwdefaults__ = raw.__kwdefaults__
        return copy

    def token_function(self, model):
        raw = self.function(model.t3.inference_turbo)
        model.t3.inference_turbo = MethodType(self.torch.no_grad()(raw), model.t3)
        return raw

    def protect(self, model):
        # DirectML's instance wrappers must be installed before routing random
        # draws in the acoustic modules. Preserve ordinary non-random wrappers.
        routed = []
        for module in model.s3gen.modules():
            seen = set()
            for cls in type(module).__mro__:
                for name, function in cls.__dict__.items():
                    if name in seen:
                        continue
                    seen.add(name)
                    if not inspect.isfunction(function):
                        continue
                    raw = inspect.unwrap(function)
                    if not raw.__module__.startswith('chatterbox.'):
                        continue
                    if {'randn', 'randn_like', 'multinomial', 'Uniform'} & set(raw.__code__.co_names):
                        setattr(module, name, MethodType(self.torch.no_grad()(self.function(raw)), module))
                        routed.append(type(module).__name__ + '.' + name)
        if not any(name.endswith('.flow_inference') for name in routed):
            raise RuntimeError('Unsupported acoustic version: request noise was not isolated')
        token_inference = model.t3.inference_turbo
        def tokens(*args, **kwargs):
            result = token_inference(*args, **kwargs)
            payload = result.detach().cpu().contiguous().numpy().tobytes()
            self.local.token_hash.update(struct.pack('<Q',len(payload)))
            self.local.token_hash.update(payload)
            return result
        model.t3.inference_turbo = self.wrap('tokens', tokens)
        model.s3gen.inference = self.wrap('acoustics', model.s3gen.inference)
        model.watermarker.apply_watermark = self.wrap('watermark', model.watermarker.apply_watermark)
        model.tokenizer = LockedTokenizer(model.tokenizer, self.wrap('text', model.tokenizer))
        return routed


class LockedTokenizer:
    """Fast tokenizers configure padding/truncation internally on first use."""
    def __init__(self, tokenizer, invoke):
        self.tokenizer, self.invoke = tokenizer, invoke

    def __call__(self, *args, **kwargs):
        return self.invoke(*args, **kwargs)

    def __getattr__(self, name):
        return getattr(self.tokenizer, name)

    def __len__(self):
        return len(self.tokenizer)


class RequestTorch:
    """Delegate tensor math; explicitly route supported random draws locally."""
    def __init__(self, state):
        self.state = state

    def __getattr__(self, name):
        return getattr(self.state.torch, name)

    def randn(self, *size, **kwargs):
        device = kwargs.pop('device', 'cpu')
        kwargs['generator'] = self.state.generator()
        return self.state.torch.randn(*size, device='cpu', **kwargs).to(device)

    def randn_like(self, tensor, **kwargs):
        dtype = kwargs.pop('dtype', tensor.dtype)
        device = kwargs.pop('device', tensor.device)
        return self.randn(tensor.shape, dtype=dtype, device=device, **kwargs)

    def multinomial(self, probabilities, num_samples, replacement=False, **kwargs):
        kwargs['generator'] = self.state.generator()
        return self.state.torch.multinomial(probabilities.cpu(), num_samples,
                                            replacement=replacement, **kwargs).to(probabilities.device)


class StagedChatterboxAdapter(ChatterboxAdapter):
    supports_parallel = True

    def __init__(self, *args, state, **kwargs):
        super().__init__(*args, **kwargs)
        self.state = state

    def synthesize(self, text, seed=None):
        with self.state.request(seed):
            return super().synthesize(text)

    def metrics(self):
        return self.state.snapshot()
