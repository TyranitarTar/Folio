"""Pinned FP32 Plapre 3 HIP backend with request-local cache, RNG and streams.

Acoustics are gated: Kanade mutates its rotary-frequency attributes and MiOpen
has not been qualified for concurrent variable-length vocoder requests here.
Language-model weights are shared and read-only; each call owns its KV cache.
"""
from pathlib import Path
import hashlib
import json
import os
import secrets
import threading
import time

from .core import NarrationError

PROFILE = 'plapre3-rocm-fp32-fast-v1'


class RocmPlapreAdapter:
    sample_rate = 24000
    supports_parallel = True

    def __init__(self, model_dir, sampling, accelerator='rocm', threads=2):
        # Must precede the first HIP convolution; scoped to this worker process.
        os.environ['MIOPEN_FIND_MODE'] = 'FAST'
        os.environ['HF_HUB_OFFLINE'] = '1'
        os.environ['TRANSFORMERS_OFFLINE'] = '1'
        import torch
        import transformers
        from safetensors.torch import load_file
        from tokenizers import Tokenizer
        from transformers import LlamaConfig, LlamaForCausalLM
        from transformers.models.llama.modeling_llama import LlamaRotaryEmbedding
        if accelerator != 'rocm' or not torch.version.hip or not torch.cuda.is_available():
            raise NarrationError('This profile requires the installed HIP runtime and an AMD GPU; no CPU fallback.')
        if not str(torch.__version__).startswith('2.12.0+rocm10.1.0a20260823') or transformers.__version__ != '4.57.6':
            raise NarrationError('Use the validated, pinned Studio ROCm runtime for this narration edition.')
        torch.set_num_threads(threads)
        self.device = torch.device('cuda:0')
        index = json.loads((Path(model_dir)/'folio-component-index.json').read_text(encoding='utf-8'))
        if index.get('pipeline') != PROFILE:
            raise NarrationError('This profile requires the original-weight Plapre 3 ROCm component index.')
        files = {row['name']: Path(row['path']) for row in index['files']}
        config = LlamaConfig.from_dict(json.loads(files['config.json'].read_text(encoding='utf-8')))
        config._attn_implementation = 'sdpa'
        if config.rope_theta != 10000:
            raise NarrationError('Unexpected Plapre rotary configuration.')
        with torch.device('meta'):
            self.lm = LlamaForCausalLM(config)
        weights = load_file(str(files['model.safetensors']), device='cpu')
        weights['lm_head.weight'] = weights['model.embed_tokens.weight']
        self.lm.load_state_dict(weights, assign=True, strict=True)
        self.lm.tie_weights()
        self.lm.model.rotary_emb = LlamaRotaryEmbedding(config, device='cpu')
        del weights
        self.lm.eval().to(self.device)
        self.tokenizer = Tokenizer.from_file(str(files['tokenizer.json']))
        voices = json.loads(files['voices.json'].read_text(encoding='utf-8'))
        self.voice = next((v for v in voices if v['key'] == sampling.get('voice', 'ida')), None)
        if self.voice is None:
            raise NarrationError('The selected Plapre voice is not installed.')
        from tools.pico.audio import KanadeSynthesis, PortableHiFT, load_hift, ROOT
        # These helpers load the shared local originals. Refuse a relink that
        # points at other weights rather than fingerprinting the wrong files.
        for key, expected in [('kanade.safetensors', ROOT/'artifacts/kanade/model.safetensors'),
                              ('kanade.yaml', ROOT/'artifacts/kanade/config.yaml'),
                              ('hift.pt', ROOT/'artifacts/hift/hift.pt')]:
            if files[key].resolve() != expected.resolve():
                raise NarrationError('Relink the shared codec originals in this workspace.')
        self.codec = KanadeSynthesis().to(self.device)
        self.vocoder = PortableHiFT(load_hift()).eval().to(self.device)
        for module in self.codec.modules():
            if isinstance(getattr(module, 'freqs_cis', None), torch.Tensor):
                module.freqs_cis = module.freqs_cis.to(self.device)
        self.prefix = torch.tensor(self.voice['prefix'], device=self.device, dtype=torch.float32).reshape(1, 1, -1)
        self.speaker = torch.tensor(self.voice['embedding'], device=self.device, dtype=torch.float32)[None]
        self.sampling = sampling.copy()
        self.decode_lock = threading.Lock()
        self.local = threading.local()
        self.ready = torch.cuda.Event()
        self.ready.record()
        self.ready.synchronize()
        self.execution = dict(profile=PROFILE, accelerator='rocm', precision='float32',
            runtime=str(torch.__version__), transformers=transformers.__version__, hip=torch.version.hip,
            gpu=torch.cuda.get_device_name(), tokenDevice='gpu', acousticDevice='gpu',
            requestState='independent-gpu-generator-kv-stream', acoustics='serialized', voice=self.voice['key'])
        if any(p.device.type != 'cuda' for net in (self.lm, self.codec, self.vocoder) for p in net.parameters()):
            raise NarrationError('A neural parameter is not on the GPU.')

    def synthesize(self, text, depth=0, seed=None):
        import numpy as np
        from .engines import TokenBudgetExhausted, split_at_safe_clause_boundary
        try:
            return self.render(text, seed)[0]
        except TokenBudgetExhausted:
            parts = split_at_safe_clause_boundary(text)
            if depth >= 4 or not parts:
                raise
            return np.concatenate([self.synthesize(part, depth+1, None if seed is None else seed+i+1)
                                   for i, part in enumerate(parts)])

    def render(self, text, seed=None):
        import torch
        import numpy as np
        from .engines import TokenBudgetExhausted
        if not getattr(self.local, 'stream', None):
            self.local.stream = torch.cuda.Stream(device=self.device)
        stream = self.local.stream
        started = time.perf_counter()
        with torch.inference_mode(), torch.cuda.stream(stream):
            stream.wait_event(self.ready)
            generator = torch.Generator(device=self.device).manual_seed(secrets.randbits(63) if seed is None else seed)
            ids = torch.tensor([[8000]+self.tokenizer.encode(text, add_special_tokens=False).ids+[8001]], device=self.device)
            current = torch.cat([self.prefix, self.lm.get_input_embeddings()(ids)], dim=1)
            past, codes = None, []
            for _ in range(751):
                output = self.lm(inputs_embeds=current, past_key_values=past, use_cache=True)
                past = output.past_key_values
                values, indices = output.logits[0, -1].float().topk(int(self.sampling.get('top_k', 50)))
                probs = (values/float(self.sampling.get('temperature', .7))).softmax(-1)
                probs = probs.masked_fill((probs.cumsum(-1)-probs) >= float(self.sampling.get('top_p', .95)), 0)
                token = indices[torch.multinomial(probs/probs.sum(), 1, generator=generator)]
                number = int(token.item())
                if number in (0, 21223):
                    break
                if not 8002 <= number < 20802:
                    raise NarrationError('Invalid Plapre speech token: '+str(number))
                if len(codes) >= 750:
                    raise TokenBudgetExhausted(len(codes), 750)
                codes.append(token-8002)
                current = self.lm.get_input_embeddings()(token.reshape(1, 1))
            if not codes:
                raise NarrationError('Plapre generated no speech.')
            del past, output, current
            stream.synchronize()  # This request only, never a device-wide barrier.
            lm_done = time.perf_counter()
            token_tensor = torch.cat(codes).long()
            with self.decode_lock:
                acoustic_start = time.perf_counter()
                mel = self.codec(token_tensor, self.speaker)
                phase = torch.rand((1, 9), device=self.device, generator=generator)
                phase[:, 0] = 0
                noise = torch.randn((1, len(codes)*960, 9), device=self.device, generator=generator)
                waveform = self.vocoder(mel, noise, phase).reshape(-1)
                # Keep the gate until kernels finish; a Python lock alone would
                # not serialize asynchronously enqueued GPU convolutions.
                stream.synchronize()
                acoustic_done = time.perf_counter()
            wave = waveform.float().cpu().numpy()
            token_hash = hashlib.sha256(token_tensor.cpu().numpy().tobytes()).hexdigest()
        if len(wave) != len(codes)*960 or not np.isfinite(wave).all() or abs(wave).max() < 1e-6:
            raise NarrationError('Invalid Plapre GPU waveform.')
        done = time.perf_counter()
        return wave, dict(seed=seed, stopToken=number, tokenCount=len(codes), tokenSha256=token_hash,
            started=started, finished=done, synthesisSeconds=done-started, lmSeconds=lm_done-started,
            acousticWaitSeconds=acoustic_start-lm_done, acousticSeconds=acoustic_done-acoustic_start,
            audioSeconds=len(wave)/24000)
