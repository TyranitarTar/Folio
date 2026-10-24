"""Standard-library tests for safe extraction, resume behavior, and .foliobook packaging."""

from __future__ import annotations

import hashlib
import json
import math
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from tools.narration import core
from tools.narration.engines import (
    ChatterboxAdapter,
    TokenBudgetExhausted,
    adaptive_token_budget,
    install_turbo_eos_guard,
    split_at_safe_clause_boundary,
)


def make_epub(path: Path) -> bytes:
    long_sentence = " ".join(["reader"] * 205) + "."
    body = f"<p id='start'>Yes. Yes. {long_sentence}</p><p>{' '.join(['Another sentence.'] * 3)}</p>"
    files = {
        "mimetype": b"application/epub+zip",
        "META-INF/container.xml": b"""<?xml version='1.0'?><container xmlns='urn:oasis:names:tc:opendocument:xmlns:container' version='1.0'><rootfiles><rootfile full-path='EPUB/package.opf' media-type='application/oebps-package+xml'/></rootfiles></container>""",
        "EPUB/package.opf": b"""<?xml version='1.0'?><package xmlns='http://www.idpf.org/2007/opf' version='3.0' unique-identifier='id'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>Test Book</dc:title><dc:creator>Test Author</dc:creator><dc:language>en</dc:language></metadata><manifest><item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/><item id='ch1' href='chapter.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='nav'/><itemref idref='ch1'/></spine></package>""",
        "EPUB/nav.xhtml": b"""<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><body><nav epub:type='toc'><ol><li><a href='chapter.xhtml'>Chapter One</a></li></ol></nav></body></html>""",
        "EPUB/chapter.xhtml": f"""<html xmlns='http://www.w3.org/1999/xhtml'><head><title>Chapter One</title></head><body><section><h1>Chapter One</h1>{body}</section></body></html>""".encode("utf-8"),
    }
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in files.items():
            archive.writestr(name, data, compress_type=zipfile.ZIP_STORED)
    return path.read_bytes()


def make_model_dir(path: Path) -> Path:
    path.mkdir()
    for name in ("s3gen_meanflow.safetensors", "t3_turbo_v1.safetensors", "ve.safetensors", "conds.pt"):
        (path / name).write_bytes(name.encode("ascii"))
    (path / "tokenizer_config.json").write_text("{}", encoding="utf-8")
    return path


class FakeSynth:
    def __init__(self) -> None:
        self.calls: list[str] = []

    def synthesize(self, text: str) -> list[float]:
        self.calls.append(text)
        # A finite voiced interior with quiet guards; sample count is deterministic.
        return [0.0] * 240 + [0.1 * math.sin(i / 9) for i in range(2400)] + [0.0] * 240


class FakeTokenBatch:
    def __init__(self, length: int):
        self.shape = (1, length)


class FakeT3:
    def __init__(self, token_count: int | None = None):
        self.token_count = token_count
        self.should_cap = False
        self.max_lengths: list[int] = []

    def inference_turbo(self, max_gen_len: int = 1000) -> FakeTokenBatch:
        self.max_lengths.append(max_gen_len)
        count = self.token_count
        if count is None:
            count = max_gen_len + 1 if self.should_cap else max_gen_len - 2
        return FakeTokenBatch(count)


class FakeEosModel:
    def __init__(self, always_cap: bool = False):
        self.current_text = ""
        self.always_cap = always_cap
        self.vocoder_calls = 0
        self.text_calls: list[str] = []
        self.t3 = FakeT3()

    def generate(self, text: str, **_settings: object) -> list[float]:
        self.current_text = text
        self.text_calls.append(text)
        self.t3.should_cap = self.always_cap or len(text.split()) > 2
        self.t3.inference_turbo()
        self.vocoder_calls += 1
        return [float(len(text.split()))] * 2


def fake_encode(_ffmpeg: str, wav_path: Path, mp3_path: Path, _bitrate: str) -> None:
    mp3_path.parent.mkdir(parents=True, exist_ok=True)
    mp3_path.write_bytes(b"fake-mp3-for-packaging-test\n" + wav_path.read_bytes())


class NarrationTests(unittest.TestCase):
    def test_untested_english_gpu_model_is_rejected_before_planning(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root/'test.epub'
            make_epub(source)
            for accelerator in ('directml', 'directml-acoustic'):
                with self.subTest(accelerator=accelerator), self.assertRaises(core.NarrationError):
                    core.create_job(source, 'chatterbox', root/'jobs', accelerator=accelerator)
            self.assertFalse((root/'jobs').exists())

    def test_gpu_jobs_are_separate_from_legacy_cpu_jobs(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / 'test.epub'
            make_epub(source)
            cpu, cpu_job = core.create_job(source, 'roest', root/'jobs', language='da')
            gpu, gpu_job = core.create_job(source, 'roest', root/'jobs', language='da', accelerator='directml')
            self.assertNotEqual(cpu, gpu)
            self.assertEqual(gpu_job['accelerator'], 'directml')
            acoustic, acoustic_job = core.create_job(source, 'roest', root/'jobs', language='da', accelerator='directml-acoustic')
            self.assertNotEqual(acoustic, gpu)
            self.assertNotEqual(acoustic, cpu)
            self.assertEqual(acoustic_job['accelerator'], 'directml-acoustic')
            # v0.13 CPU plans did not record an accelerator. Their ID/resume
            # remains valid, and choosing GPU cannot take their cached chunks.
            cpu_job.pop('accelerator')
            core.save_json(cpu/'job.json', cpu_job)
            again, _ = core.create_job(source, 'roest', root/'jobs', language='da')
            self.assertEqual(again, cpu)

    def test_changed_accelerator_cannot_reuse_recorded_chunks(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root/'test.epub'
            make_epub(source)
            folder, job = core.create_job(source, 'roest', root/'jobs', language='da')
            model = make_model_dir(root/'model')
            synth = FakeSynth()
            with patch.object(core, '_encode_mp3', side_effect=fake_encode):
                core.generate_job(folder, model, max_sentences=1, fake_synth=synth)
            job['accelerator'] = 'directml'
            core.save_json(folder/'job.json', job)
            with self.assertRaisesRegex(core.NarrationError, 'accelerator differs'):
                core.generate_job(folder, model, fake_synth=synth)
            self.assertEqual(len(synth.calls), 1)

    def test_sentence_splitter_and_edge_trim(self) -> None:
        self.assertEqual([s[0] for s in core._sentences('Dr. Vale said, "Ready?" Yes!')], ['Dr. Vale said, "Ready?"', "Yes!"])
        samples = [0.0] * 3000 + [0.1] * 4800 + [0.0] * 3000
        trimmed = core.trim_boundary_silence(samples, 24_000, margin_ms=60)
        self.assertGreaterEqual(len(trimmed), 4800)
        self.assertLess(len(trimmed), len(samples))

    def test_turbo_guard_blocks_capped_tokens_and_retries_by_splitting(self) -> None:
        t3 = FakeT3(token_count=1001)
        budget = install_turbo_eos_guard(t3, default_max_gen_len=1000)
        budget["max_gen_len"] = 1000
        with self.assertRaises(TokenBudgetExhausted):
            t3.inference_turbo()
        self.assertEqual(t3.max_lengths, [1000])

        model = FakeEosModel()
        adapter = ChatterboxAdapter(
            model,
            {"temperature": 0.7, "top_p": 0.95, "top_k": 600, "repetition_penalty": 1.2},
            core.TARGET_SAMPLE_RATE,
        )
        audio = adapter.synthesize("one two three four.")
        self.assertEqual(audio, [2.0, 2.0, 2.0, 2.0])
        self.assertEqual(model.text_calls[:2], ["one two three four."] * 2)
        self.assertEqual(model.text_calls[2:], ["one two,", "three four."])
        self.assertEqual(model.vocoder_calls, 2)
        self.assertEqual(model.t3.max_lengths, [1000, 1000, 200, 200])

    def test_turbo_guard_fails_clearly_when_no_safe_split_can_finish(self) -> None:
        model = FakeEosModel(always_cap=True)
        adapter = ChatterboxAdapter(
            model,
            {"temperature": 0.7, "top_p": 0.95, "top_k": 600, "repetition_penalty": 1.2},
            core.TARGET_SAMPLE_RATE,
        )
        with self.assertRaises(TokenBudgetExhausted):
            adapter.synthesize("singleword")
        self.assertEqual(model.vocoder_calls, 0)
        self.assertEqual(model.text_calls, ["singleword", "singleword"])

    def test_adaptive_budget_and_split_preserve_every_source_word(self) -> None:
        self.assertEqual(adaptive_token_budget(" ".join(["word"] * 28)), 680)
        parts = split_at_safe_clause_boundary("First clause, second clause.")
        self.assertIsNotNone(parts)
        self.assertEqual(" ".join(parts or ()).replace(",", "").split(), "First clause second clause.".split())

    def test_plan_resume_preview_and_bundle_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "test.epub"
            original_epub = make_epub(source)
            jobs_root = root / "private-jobs"
            job_dir, job = core.create_job(source, "chatterbox", jobs_root=jobs_root, max_chars=2000)
            self.assertEqual(job["language"], "en")
            self.assertEqual(len(job["chapters"]), 1)
            cues = job["chapters"][0]["cues"]
            self.assertEqual(cues[0]["href"], "EPUB/chapter.xhtml")
            self.assertNotIn("archiveHref", cues[0])
            yes_cues = [cue for cue in cues if cue["text"] == "Yes."]
            self.assertEqual(len(yes_cues), 2)
            self.assertTrue(yes_cues[0]["after"])
            self.assertTrue(yes_cues[1]["before"])
            self.assertTrue(cues[0]["after"])
            self.assertEqual(yes_cues[0]["fragment"], "start")
            self.assertTrue(any("cssSelector" in cue for cue in cues))
            self.assertTrue(all(len(cue["before"]) <= 80 and len(cue["after"]) <= 80 for cue in cues))
            model_dir = make_model_dir(root / "model")
            synth = FakeSynth()
            with patch.object(core, "_encode_mp3", side_effect=fake_encode):
                preview, preview_status = core.generate_job(job_dir, model_dir, max_sentences=2, fake_synth=synth)
                self.assertFalse(preview_status["completed"])
                self.assertEqual(preview_status["completedChapters"], 0)
                self.assertEqual(preview_status["estimateStatus"], "provisional")
                self.assertGreater(preview_status["lastCueAudioMs"], 0)
                self.assertTrue(preview.name.endswith(".preview.foliobook"))
                self.assertEqual(len(synth.calls), 2)
                with zipfile.ZipFile(preview) as archive:
                    manifest = json.loads(archive.read("manifest.json"))
                    self.assertTrue(manifest["preview"])
                    self.assertEqual(manifest["generatedSentenceCount"], 2)
                    self.assertEqual(archive.read("book.epub"), original_epub)
                    self.assertEqual(archive.getinfo("book.epub").compress_type, zipfile.ZIP_STORED)
                    self.assertEqual(archive.getinfo(manifest["chapters"][0]["file"]).compress_type, zipfile.ZIP_STORED)
                final, final_status = core.generate_job(job_dir, model_dir, fake_synth=synth)
                self.assertTrue(final_status["completed"])
                self.assertEqual(final_status["completedChapters"], 1)
                self.assertEqual(final_status["estimateStatus"], "complete")
                self.assertFalse(final.name.endswith(".preview.foliobook"))
                self.assertEqual(len(synth.calls), len(cues))
                with zipfile.ZipFile(final) as archive:
                    manifest = json.loads(archive.read("manifest.json"))
                    self.assertFalse(manifest["preview"])
                    self.assertEqual(manifest["generatedSentenceCount"], manifest["totalSentenceCount"])
                    chapter = manifest["chapters"][0]
                    self.assertTrue(chapter["complete"])
                    self.assertEqual(chapter["cues"][0]["text"], cues[0]["text"])
                    self.assertEqual(chapter["cues"][0]["href"], "EPUB/chapter.xhtml")
                    self.assertNotIn("archiveHref", chapter["cues"][0])
                    self.assertTrue(all(0 <= cue["startMs"] < cue["endMs"] <= chapter["durationMs"] for cue in chapter["cues"]))
                    self.assertEqual(archive.testzip(), None)
                self.assertEqual(hashlib.sha256(original_epub).hexdigest(), job["sourceSha256"])

    def test_model_provenance_binds_repository_revision_and_files(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            model_dir = make_model_dir(Path(temp) / "model")
            _, files = core.fingerprint_model(model_dir)
            provenance = {
                "repository": "CoRal-project/roest-v3-chatterbox-350m",
                "revision": "reviewed-commit",
                "files": [
                    {"name": item["name"], "bytes": item["sizeBytes"], "sha256": item["sha256"]}
                    for item in files
                ],
            }
            (model_dir / "folio-model-provenance.json").write_text(json.dumps(provenance), encoding="utf-8")
            core.fingerprint_model(
                model_dir,
                expected_repository=provenance["repository"],
                expected_revision=provenance["revision"],
                require_provenance=True,
            )
            (model_dir / "conds.pt").write_bytes(b"changed")
            with self.assertRaisesRegex(core.NarrationError, "provenance record"):
                core.fingerprint_model(model_dir, require_provenance=True)

    def test_language_mismatch_requires_explicit_override(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "test.epub"
            make_epub(source)
            with self.assertRaisesRegex(core.NarrationError, "EPUB language is 'en'"):
                core.create_job(source, "roest", jobs_root=root / "jobs")
            job_dir, job = core.create_job(source, "roest", jobs_root=root / "jobs", language="da")
            self.assertEqual(job["language"], "da")
            self.assertTrue((job_dir / "job.json").is_file())


if __name__ == "__main__":
    unittest.main()

