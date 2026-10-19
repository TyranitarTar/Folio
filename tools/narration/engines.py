"""Lazy CPU adapters for the official Chatterbox Turbo-compatible API."""

from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Any

from .core import ENGINES, NarrationError, TARGET_SAMPLE_RATE


class TokenBudgetExhausted(RuntimeError):
    """Turbo reached its token budget without producing an EOS-terminated sequence."""

    def __init__(self, token_count: int, max_gen_len: int):
        self.token_count = token_count
        self.max_gen_len = max_gen_len
        super().__init__(
            f"T3 returned {token_count} speech tokens at max_gen_len={max_gen_len}; "
            "the sequence may have been truncated before EOS"
        )


def install_turbo_eos_guard(t3: Any, default_max_gen_len: int = 1000) -> dict[str, int]:
    """Reject max-length sequences before ChatterboxTurboTTS can pass them to S3Gen."""
    original = t3.inference_turbo
    budget = {"max_gen_len": default_max_gen_len}

    def guarded(*args: Any, **kwargs: Any) -> Any:
        current_limit = int(kwargs.setdefault("max_gen_len", budget["max_gen_len"]))
        tokens = original(*args, **kwargs)
        shape = getattr(tokens, "shape", None)
        if shape is not None and len(shape):
            token_count = int(shape[-1])
        elif isinstance(tokens, (tuple, list)):
            token_count = len(tokens[-1]) if tokens and isinstance(tokens[-1], (tuple, list)) else len(tokens)
        else:
            raise NarrationError("Could not inspect T3 token output; refusing to vocode unverified speech")
        # In Chatterbox 0.1.7, EOS is removed before return. A capped sequence has
        # max_gen_len + 1 tokens; a sequence ending in EOS has at most max_gen_len.
        if token_count > current_limit:
            raise TokenBudgetExhausted(token_count, current_limit)
        return tokens

    t3.inference_turbo = guarded
    return budget


def adaptive_token_budget(text: str) -> int:
    words = len(text.split())
    return min(1000, max(200, words * 20 + 120))


def split_at_safe_clause_boundary(text: str) -> tuple[str, str] | None:
    """Split speech input without dropping words, preferring punctuation near its center."""
    middle = len(text) // 2
    lower, upper = len(text) * 0.25, len(text) * 0.75
    candidates = [
        match.end()
        for match in re.finditer(r"[,;:—–]\s+", text)
        if lower <= match.end() <= upper
    ]
    if not candidates:
        candidates = [index + 1 for index, char in enumerate(text) if char.isspace() and lower <= index <= upper]
    if not candidates:
        return None
    split_at = min(candidates, key=lambda value: abs(value - middle))
    left, right = text[:split_at].strip(), text[split_at:].strip()
    if not left or not right:
        return None
    if left[-1] not in ".!?…,:;—–":
        left += ","
    return left, right


class ChatterboxAdapter:
    def __init__(self, model: Any, sampling: dict[str, Any], sample_rate: int, guard: dict[str, int] | None = None):
        self.model = model
        self.sampling = sampling
        self.sample_rate = sample_rate
        self.guard = guard or install_turbo_eos_guard(model.t3)
        if sample_rate != TARGET_SAMPLE_RATE:
            raise NarrationError(
                f"This exporter currently expects 24 kHz Chatterbox output; engine reports {sample_rate} Hz"
            )

    def synthesize(self, text: str) -> Any:
        return self._synthesize_with_eos_retry(text, depth=0)

    def _generate(self, text: str, max_gen_len: int) -> Any:
        self.guard["max_gen_len"] = max_gen_len
        return self.model.generate(
            text,
            temperature=float(self.sampling["temperature"]),
            top_p=float(self.sampling["top_p"]),
            top_k=int(self.sampling["top_k"]),
            repetition_penalty=float(self.sampling["repetition_penalty"]),
        )

    def _synthesize_with_eos_retry(self, text: str, depth: int) -> Any:
        budgets = (1000, 1000) if depth == 0 else (adaptive_token_budget(text), 1000)
        last_error: TokenBudgetExhausted | None = None
        for budget in budgets:
            try:
                return self._generate(text, budget)
            except TokenBudgetExhausted as exc:
                last_error = exc
        if depth >= 4:
            raise last_error or NarrationError("T3 did not emit EOS before the token limit")
        parts = split_at_safe_clause_boundary(text)
        if parts is None:
            raise last_error or NarrationError("T3 did not emit EOS, and the text has no safe split boundary")
        left = self._synthesize_with_eos_retry(parts[0], depth + 1)
        right = self._synthesize_with_eos_retry(parts[1], depth + 1)
        return self._join_audio(left, right)

    @staticmethod
    def _join_audio(left: Any, right: Any) -> Any:
        if isinstance(left, list) and isinstance(right, list):
            return left + right
        if isinstance(left, tuple) and isinstance(right, tuple):
            return left + right
        try:
            import torch

            if torch.is_tensor(left) and torch.is_tensor(right):
                return torch.cat((left.reshape(1, -1), right.reshape(1, -1)), dim=1)
        except ImportError:
            pass
        try:
            import numpy as np

            return np.concatenate((np.asarray(left).reshape(-1), np.asarray(right).reshape(-1)))
        except ImportError as exc:
            raise NarrationError("Could not join PCM after splitting an EOS-capped sentence") from exc


def load_engine(engine: str, model_dir: Path, voice_reference: Path | None, sampling: dict[str, Any], cpu_threads: int | None = None) -> ChatterboxAdapter:
    if engine not in ENGINES:
        raise NarrationError(f"Unknown engine {engine!r}")
    runtime_cache = Path(__file__).resolve().parent / "cache"
    os.environ.setdefault("HF_HOME", str(runtime_cache))
    os.environ.setdefault("TRANSFORMERS_CACHE", str(runtime_cache / "transformers"))
    os.environ.setdefault("HF_HUB_OFFLINE", "1")
    os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
    try:
        import torch
        from chatterbox.tts_turbo import ChatterboxTurboTTS
    except ImportError as exc:
        raise NarrationError(
            "The isolated narration runtime is incomplete. Install the pinned CPU runtime "
            "from tools/narration/requirements-cpu.txt and retry."
        ) from exc
    if cpu_threads:
        torch.set_num_threads(cpu_threads)
    try:
        model = ChatterboxTurboTTS.from_local(str(model_dir), "cpu")
        if voice_reference:
            if not voice_reference.is_file():
                raise NarrationError(f"Voice reference file does not exist: {voice_reference}")
            # Compute reference conditionals once; every sentence then uses the same narrator.
            model.prepare_conditionals(str(voice_reference), exaggeration=0.0)
        if model.conds is None:
            raise NarrationError(
                "The selected model has no built-in speaker conditionals. Provide --voice-reference."
            )
    except NarrationError:
        raise
    except Exception as exc:
        raise NarrationError(f"Could not load the local {engine} model on CPU: {exc}") from exc
    token_guard = install_turbo_eos_guard(model.t3)
    return ChatterboxAdapter(model, sampling, int(getattr(model, "sr", 0)), guard=token_guard)

