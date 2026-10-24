"""Offline LM diagnostic. A cap/loop is failure, never a successful speech ending."""
import argparse
import json
import pathlib
import time

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

ROOT = pathlib.Path(__file__).resolve().parent

class PicoLM:
    def __init__(self, precision="int8", threads=4):
        folder = ROOT / "artifacts/pico"
        self.tokenizer = Tokenizer.from_file(str(folder / "tokenizer.json"))
        self.embeddings = np.load(folder / "embed_tokens.npy", mmap_mode="r")
        self.speakers = json.loads((folder / "speakers.json").read_text())
        options = ort.SessionOptions()
        options.intra_op_num_threads = threads
        options.inter_op_num_threads = 1
        name = "model_decode_int8.onnx" if precision == "int8" else "model_decode.onnx"
        corrected = ROOT / "export" / ("pico.onnx" if precision == "int8" else "pico-fp32.onnx")
        self.session = ort.InferenceSession(str(corrected if corrected.is_file() else folder / "onnx" / name), options, providers=["CPUExecutionProvider"])
        self.project = ort.InferenceSession(str(folder / "onnx/speaker_proj.onnx"), options, providers=["CPUExecutionProvider"])
        self.kv_names = [i.name for i in self.session.get_inputs()][3:]
        self.audio_start = self.tokenizer.token_to_id("<audio_0>")
        self.eos = self.tokenizer.token_to_id("<eos>")

    def generate(self, text, speaker="ida", temperature=0.7, seed=42, maximum=500):
        rng = np.random.default_rng(seed)
        voice = np.asarray(self.speakers[speaker], dtype=np.float32)
        projected = self.project.run(None, {self.project.get_inputs()[0].name: voice[None]})[0].reshape(1, 1, -1)
        text_ids = self.tokenizer.encode(text, add_special_tokens=False).ids
        prompt = [self.tokenizer.token_to_id("<text>")] + text_ids + [self.tokenizer.token_to_id("<audio>")]
        cache = [np.zeros((1, 3, 0, 64), np.float32) for _ in self.kv_names]
        position = 0
        def step(embedding):
            nonlocal cache, position
            feed = {"inputs_embeds": np.asarray(embedding, dtype=np.float32).reshape(1, 1, 576),
                    "attention_mask": np.ones((1, position + 1), np.int64),
                    "cache_position": np.asarray([position], np.int64)}
            feed.update(zip(self.kv_names, cache))
            results = self.session.run(None, feed)
            cache = results[1:]
            position += 1
            return results[0][0, -1].astype(np.float64)
        started = time.perf_counter()
        logits = step(projected)
        for token in prompt:
            logits = step(self.embeddings[token])
        audio = []
        stop = "token_limit"
        for _ in range(maximum):
            if temperature <= 0:
                token = int(np.argmax(logits))
            else:
                indices = np.argpartition(logits, -50)[-50:]
                top = logits[indices] / temperature
                p = np.exp(top - top.max())
                p /= p.sum()
                order = np.argsort(p)[::-1]
                keep = order[:np.searchsorted(np.cumsum(p[order]), 0.95) + 1]
                indices, p = indices[keep], p[keep]
                token = int(rng.choice(indices, p=p / p.sum()))
            if token == self.eos:
                stop = "eos"
                break
            if not self.audio_start <= token < self.audio_start + 12800:
                stop = "invalid_token"
                break
            audio.append(token - self.audio_start)
            logits = step(self.embeddings[token])
        elapsed = time.perf_counter() - started
        return dict(text=text, speaker=speaker, stop=stop, tokens=audio, elapsed=elapsed,
                    audio_seconds=len(audio) / 25, lm_rtf=elapsed / max(len(audio) / 25, 0.04))

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--precision", default="int8", choices=["int8", "fp32"])
    p.add_argument("--text", default="Hej, hvordan har du det?")
    p.add_argument("--speaker", default="ida")
    p.add_argument("--temperature", type=float, default=0.7)
    p.add_argument("--seed", type=int, default=42)
    args = p.parse_args()
    result = PicoLM(args.precision).generate(args.text, args.speaker, args.temperature, args.seed)
    destination = ROOT / "results"
    destination.mkdir(exist_ok=True)
    (destination / f"lm-{args.precision}-{args.seed}.json").write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "tokens"}, ensure_ascii=False), flush=True)
    if result["stop"] != "eos":
        raise SystemExit(2)

if __name__ == "__main__":
    main()
