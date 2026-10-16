"""Synthesis-only Kanade/HiFT reference and portable ONNX export experiments.

Uses pinned Kanade source (MIT, with upstream per-file notices) and 42futures'
CC-BY-4.0 real-valued export adaptations. No audio encoders or reference downloads.
"""
import ast
import json
import pathlib
import sys
import types

import numpy as np
import torch
import torch.nn as nn
import yaml
from safetensors.torch import load_file

ROOT = pathlib.Path(__file__).resolve().parent
# Import synthesis modules without the upstream package initializer's training/encoder imports.
package = types.ModuleType("kanade_tokenizer")
package.__path__ = [str(ROOT / "vendor/kanade/src/kanade_tokenizer")]
sys.modules.setdefault("kanade_tokenizer", package)
from kanade_tokenizer.module import transformer
from kanade_tokenizer.module.fsq import FiniteScalarQuantizer
from kanade_tokenizer.module.postnet import PostNet
from kanade_tokenizer.module.hift import HiFTGenerator

def mobile_adaptations():
    source = ROOT / "artifacts/mobile/scripts/convert_audio.py"
    tree = ast.parse(source.read_text(encoding="utf-8"))
    names = {"_apply_rotary_emb_precomputed", "_patched_attention_forward_v2", "_convert_freqs_cis_to_real", "FullVocoderWrapper"}
    selected = [n for n in tree.body if isinstance(n, (ast.FunctionDef, ast.ClassDef)) and n.name in names]
    module = ast.Module(body=selected, type_ignores=[])
    namespace = {"torch": torch, "nn": nn, "F": torch.nn.functional}
    exec(compile(module, str(source), "exec"), namespace)
    return namespace

class KanadeSynthesis(nn.Module):
    def __init__(self):
        super().__init__()
        args = yaml.safe_load((ROOT / "artifacts/kanade/config.yaml").read_text())["model"]["init_args"]
        self.local_quantizer = FiniteScalarQuantizer(**args["local_quantizer"]["init_args"])
        self.mel_prenet = transformer.Transformer(**{**args["mel_prenet"]["init_args"], "use_flash_attention": False, "use_xavier_init": False})
        self.mel_conv_upsample = nn.ConvTranspose1d(768, 768, kernel_size=2, stride=2)
        self.mel_decoder = transformer.Transformer(**{**args["mel_decoder"]["init_args"], "use_flash_attention": False, "use_xavier_init": False})
        self.mel_postnet = PostNet(**args["mel_postnet"]["init_args"])
        all_weights = load_file(str(ROOT / "artifacts/kanade/model.safetensors"))
        required = self.state_dict()
        subset = {k: v for k, v in all_weights.items() if k in required}
        self.load_state_dict(subset, strict=True)
        self.eval()
        # Upstream SDPA passes self.dropout even in eval; mirror the intended
        # FlashAttention inference path, which explicitly uses dropout_p=0.
        for layer in self.modules():
            if isinstance(layer, transformer.Attention):
                layer.dropout = 0.0

    def portable(self):
        adaptations = mobile_adaptations()
        transformer.Attention.forward = adaptations["_patched_attention_forward_v2"]
        for model in (self.mel_prenet, self.mel_decoder):
            adaptations["_convert_freqs_cis_to_real"](model)
        return self

    def forward(self, tokens, speaker):
        content = self.local_quantizer.decode(tokens).unsqueeze(0)
        hidden = self.mel_prenet(content)
        hidden = self.mel_conv_upsample(hidden.transpose(1, 2)).transpose(1, 2)
        mel = self.mel_decoder(hidden, condition=speaker.unsqueeze(1)).transpose(1, 2)
        return self.mel_postnet(mel)

def load_hift():
    vocoder = HiFTGenerator()
    weights = torch.load(ROOT / "artifacts/hift/hift.pt", map_location="cpu", weights_only=True)
    vocoder.load_state_dict({k.removeprefix("generator."): v for k, v in weights.items()}, strict=True)
    vocoder.eval()
    for module in list(vocoder.modules()):
        if torch.nn.utils.parametrize.is_parametrized(module, "weight"):
            torch.nn.utils.parametrize.remove_parametrizations(module, "weight", leave_parametrized=True)
    return vocoder

class PortableHiFT(nn.Module):
    """HiFT with real convolutional STFT/iSTFT and explicit random inputs.

    Preserve upstream excitation, voiced/unvoiced noise and magnitude clipping.
    No complex tensors, fixed-duration overlap-add or truncated output tails.
    """
    def __init__(self, net):
        super().__init__()
        self.net = net
        n = net.istft_n_fft
        k = torch.arange(n // 2 + 1, dtype=torch.float32)[:, None]
        t = torch.arange(n, dtype=torch.float32)[None]
        angle = 2 * torch.pi * k * t / n
        window = net.stft_window
        self.register_buffer("stft_kernel", torch.cat([torch.cos(angle), -torch.sin(angle)], 0)[:, None] * window)
        factors = torch.full((n // 2 + 1, 1), 2 / n)
        factors[0] = factors[-1] = 1 / n
        self.register_buffer("istft_kernel", torch.cat([torch.cos(angle) * factors, -torch.sin(angle) * factors], 0)[:, None] * window)
        self.register_buffer("window_squared", (window ** 2)[None, None])
        self.register_buffer("harmonics", torch.arange(1, net.m_source.l_sin_gen.harmonic_num + 2, dtype=torch.float32)[None, None])

    def source(self, mel, noise, initial_phase):
        v = self.net
        gen = v.m_source.l_sin_gen
        f0 = v.f0_predictor(mel)
        f0 = v.f0_upsamp(f0[:, None]).transpose(1, 2)
        rad = torch.remainder(f0 * self.harmonics / gen.sampling_rate, 1.0)
        rad = torch.cat([rad[:, :1] + initial_phase[:, None], rad[:, 1:]], dim=1)
        small = torch.nn.functional.interpolate(rad.transpose(1, 2), scale_factor=1 / gen.upsample_scale, mode="linear")
        phase = torch.cumsum(small.transpose(1, 2), dim=1) * 2 * np.pi
        phase = torch.nn.functional.interpolate(phase.transpose(1, 2) * gen.upsample_scale, scale_factor=gen.upsample_scale, mode="linear").transpose(1, 2)
        uv = (f0 > gen.voiced_threshold).float()
        amplitude = uv * gen.noise_std + (1 - uv) * gen.sine_amp / 3
        sines = torch.sin(phase) * gen.sine_amp * uv + amplitude * noise
        return v.m_source.l_tanh(v.m_source.l_linear(sines)).transpose(1, 2)

    def decode(self, mel, source):
        F = torch.nn.functional
        v = self.net
        padding = v.istft_n_fft // 2
        stft = F.conv1d(F.pad(source, (padding, padding), mode="reflect"), self.stft_kernel, stride=v.istft_hop_len)
        x = v.conv_pre(mel)
        for i in range(v.num_upsamples):
            x = v.ups[i](F.leaky_relu(x, v.lrelu_slope))
            if i == v.num_upsamples - 1:
                x = v.reflection_pad(x)
            x = x + v.source_resblocks[i](v.source_downs[i](stft))
            x = sum(v.resblocks[i * v.num_kernels + j](x) for j in range(v.num_kernels)) / v.num_kernels
        x = v.conv_post(F.leaky_relu(x))
        bins = v.istft_n_fft // 2 + 1
        magnitude = torch.exp(x[:, :bins]).clamp(max=100)
        phase = torch.sin(x[:, bins:])
        spectrum = torch.cat([magnitude * torch.cos(phase), magnitude * torch.sin(phase)], dim=1)
        waveform = F.conv_transpose1d(spectrum, self.istft_kernel, stride=v.istft_hop_len)
        norm = F.conv_transpose1d(torch.ones_like(spectrum[:, :1]), self.window_squared, stride=v.istft_hop_len)
        waveform = waveform[:, :, padding:-padding] / norm[:, :, padding:-padding].clamp(min=1e-8)
        return waveform[:, 0].clamp(-v.audio_limit, v.audio_limit)

    def forward(self, mel, noise, initial_phase):
        return self.decode(mel, self.source(mel, noise, initial_phase))

def main():
    import argparse
    import soundfile as sf
    p = argparse.ArgumentParser()
    p.add_argument("--input", default="lm-int8-42.json")
    p.add_argument("--export", action="store_true")
    args = p.parse_args()
    torch.set_num_threads(4)
    result = json.loads((ROOT / "results" / args.input).read_text(encoding="utf-8"))
    if result["stop"] != "eos":
        raise ValueError("Refusing incomplete generation")
    tokens = torch.tensor(result["tokens"], dtype=torch.int64)
    speakers = json.loads((ROOT / "artifacts/pico/speakers.json").read_text())
    speaker = torch.tensor(speakers[result["speaker"]], dtype=torch.float32)[None]
    model = KanadeSynthesis()
    with torch.no_grad():
        reference = model(tokens, speaker)
    print("Reference mel", reference.shape, flush=True)
    model.portable()
    with torch.no_grad():
        mel = model(tokens, speaker)
    print("Portable mel error", (mel - reference).abs().max().item(), flush=True)
    vocoder = load_hift()
    portable_vocoder = PortableHiFT(vocoder).eval()
    with torch.no_grad():
        torch.manual_seed(42)
        reference_wave = vocoder.inference(mel)
        torch.manual_seed(42)
        initial_phase = torch.rand((1, 9))
        initial_phase[:, 0] = 0
        noise = torch.randn((1, mel.shape[-1] * 480, 9))
        portable_wave = portable_vocoder(mel, noise, initial_phase)
        print("Portable waveform error", (portable_wave - reference_wave).abs().max().item(), flush=True)
        waveform = portable_wave.numpy().reshape(-1)
    sf.write(ROOT / "results" / (args.input.removesuffix(".json") + ".wav"), waveform, 24000)
    print("Waveform", len(waveform), "peak", np.abs(waveform).max(), flush=True)
    if args.export:
        target = ROOT / "export"
        target.mkdir(exist_ok=True)
        torch.onnx.export(model, (tokens, speaker), str(target / "kanade.onnx"), opset_version=17,
                          input_names=["tokens", "speaker"], output_names=["mel"],
                          dynamic_axes={"tokens": {0: "tokens"}, "mel": {2: "mel_frames"}})
        print("Kanade exported", flush=True)
        torch.onnx.export(portable_vocoder, (mel, noise, initial_phase), str(target / "hift.onnx"), opset_version=17,
                          input_names=["mel", "noise", "initial_phase"], output_names=["waveform"],
                          dynamic_axes={"mel": {2: "mel_frames"}, "noise": {1: "samples"}, "waveform": {1: "samples"}})
        print("HiFT exported", flush=True)

if __name__ == "__main__":
    main()
