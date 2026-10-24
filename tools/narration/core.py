"""EPUB extraction, resumable jobs, audio assembly, and .foliobook packaging.

Only the original EPUB is parsed; its contents are never executed. Job data and
audio stay in a per-user private directory by default.
"""

from __future__ import annotations

import hashlib
import json
import os
import posixpath
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import uuid
import wave
import zipfile
from dataclasses import dataclass
from html import unescape
from pathlib import Path
from typing import Any, Iterable, Iterator, Sequence
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET


FORMAT_NAME = "folio-audiobook"
FORMAT_VERSION = 1
JOB_SCHEMA = 1
MAX_SENTENCE_CHARS = 350
DEFAULT_BITRATE = "96k"
TARGET_SAMPLE_RATE = 24_000
SILENCE_BETWEEN_CUES_MS = 80
MAX_CONTEXT_CHARS = 80
MAX_XML_MEMBER_BYTES = 64 * 1024 * 1024
MAX_ARCHIVE_ENTRIES = 100_000
IGNORED_TAGS = {"script", "style", "nav", "noscript", "template"}
BLOCK_TAGS = {"p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "dt", "dd"}
TTS_SOURCE_FORMAT = "chatterbox-tts==0.1.7"
DEFAULT_MODEL_REVISIONS = {
    "chatterbox-nano": "71ccd1d0081b430592cea481f4307e764e07bc64",
    "pico": "40060c99ec388c19ec89b0c18030005e89c694ed",
    "plapre3": "5300281a08c692a5ad18c9f00a4a47aa3dff9148",
    "roest": "2a12d9c0be51fbebd6ae100ab5578caef8b6bd4f",
    "chatterbox": "749d1c1a46eb10492095d68fbcf55691ccf137cd",
}

ENGINES: dict[str, dict[str, Any]] = {
    "chatterbox-nano": {
        "repository": "ResembleAI/chatterbox-nano", "language": "en",
        "temperature": 0.8, "top_p": 0.95, "top_k": 1000, "repetition_penalty": 1.2,
        "runtime": "chatterbox-tts==0.1.7+folio-nano-v1",
    },
    "pico": {
        "repository": "varsan-g/plapre-pico-ONNX", "language": "da", "temperature": 0.7,
        "top_p": 0.95, "top_k": 50, "repetition_penalty": 1.0,
        "runtime": "folio-pico-onnx-v1",
    },
    "plapre3": {
        "repository": "syvai/plapre-3", "language": "da", "temperature": 0.7,
        "top_p": 0.95, "top_k": 50, "repetition_penalty": 1.0,
        "runtime": "folio-plapre3-onnx-v1",
    },
    "chatterbox": {
        "repository": "ResembleAI/chatterbox-turbo",
        "language": "en",
        "temperature": 0.8,
        "top_p": 0.95,
        "top_k": 1000,
        "repetition_penalty": 1.2,
    },
    "roest": {
        "repository": "CoRal-project/roest-v3-chatterbox-350m",
        "language": "da",
        "temperature": 0.7,
        "top_p": 0.95,
        "top_k": 600,
        "repetition_penalty": 1.2,
    },
}

_SENTENCE_END = set(".!?…")
_CLOSERS = set('"\'”’»)]}')
_ABBREVIATIONS = {
    "mr.", "mrs.", "ms.", "dr.", "prof.", "sr.", "jr.", "st.", "vs.",
    "etc.", "e.g.", "i.e.", "no.", "fig.", "inc.", "ltd.", "co.",
}


class NarrationError(RuntimeError):
    """An expected, user-correctable narration job error."""


class NarrationPaused(NarrationError):
    """A cooperative checkpoint, not a failed or completed export."""


@dataclass
class TextBlock:
    text: str
    fragment: str
    css_selector: str
    tag: str


@dataclass
class ChapterSource:
    title: str
    href: str
    sentences: list[dict[str, Any]]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1].lower() if "}" in tag else tag.lower()


def _text_content(element: ET.Element) -> str:
    """Collect visible text from an XHTML subtree and collapse XML whitespace."""
    pieces: list[str] = []

    def visit(node: ET.Element) -> None:
        if _local_name(node.tag) in IGNORED_TAGS or "hidden" in node.attrib:
            return
        epub_type = next((v for k, v in node.attrib.items() if k.endswith("}type")), "")
        classes = set(node.attrib.get("class", "").lower().split())
        if any(t in epub_type.lower().split() for t in ("pagebreak", "footnote", "endnote")) or classes.intersection({"pagebreak", "footnote", "endnote"}):
            return
        if node.text:
            pieces.append(node.text)
        for child in list(node):
            visit(child)
            if child.tail:
                pieces.append(child.tail)

    visit(element)
    return " ".join("".join(pieces).split())


def _css_string(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\a ") + '"'


def _css_path(element: ET.Element, parent_of: dict[ET.Element, ET.Element]) -> str:
    """Build a stable structural selector without adding spans or changing EPUB bytes."""
    ident = element.attrib.get("id") or element.attrib.get("{http://www.w3.org/XML/1998/namespace}id")
    if ident:
        return f'{_local_name(element.tag)}[id={_css_string(ident)}]'
    parts: list[str] = []
    current: ET.Element | None = element
    while current is not None:
        tag = _local_name(current.tag)
        parent = parent_of.get(current)
        if parent is None:
            parts.append(tag)
            break
        peers = [child for child in list(parent) if _local_name(child.tag) == tag]
        nth = peers.index(current) + 1
        parts.append(f"{tag}:nth-of-type({nth})")
        if tag == "body":
            break
        current = parent
    return " > ".join(reversed(parts))


def _body_blocks(root: ET.Element) -> list[TextBlock]:
    body = next((node for node in root.iter() if _local_name(node.tag) == "body"), None)
    if body is None:
        return []
    parent_of: dict[ET.Element, ET.Element] = {}

    def index_parents(node: ET.Element) -> None:
        for child in list(node):
            parent_of[child] = node
            index_parents(child)

    index_parents(body)
    blocks: list[TextBlock] = []

    def walk(node: ET.Element) -> None:
        if _local_name(node.tag) in IGNORED_TAGS or "hidden" in node.attrib:
            return
        epub_type = next((v for k, v in node.attrib.items() if k.endswith("}type")), "")
        classes = set(node.attrib.get("class", "").lower().split())
        if any(t in epub_type.lower().split() for t in ("pagebreak", "footnote", "endnote")) or classes.intersection({"pagebreak", "footnote", "endnote"}):
            return
        local = _local_name(node.tag)
        if local in BLOCK_TAGS:
            text = _text_content(node)
            if text:
                fragment = node.attrib.get("id") or node.attrib.get(
                    "{http://www.w3.org/XML/1998/namespace}id", ""
                )
                selector = "" if fragment else _css_path(node, parent_of)
                blocks.append(TextBlock(text, fragment, selector, local))
            return
        for child in list(node):
            walk(child)

    walk(body)
    if not blocks:
        text = _text_content(body)
        if text:
            blocks.append(TextBlock(text, "", _css_path(body, parent_of), "body"))
    return blocks


def _safe_member(base: str, href: str) -> tuple[str, str, str]:
    """Resolve an EPUB-relative resource safely, returning ZIP path, href path, fragment."""
    parts = urlsplit(href)
    if parts.scheme or parts.netloc or parts.query:
        raise NarrationError(f"EPUB resource reference is not a local path: {href!r}")
    raw_path = unquote(parts.path)
    if not raw_path or raw_path.startswith(("/", "\\")) or "\\" in raw_path:
        raise NarrationError(f"Unsafe EPUB resource path: {href!r}")
    joined = posixpath.normpath(posixpath.join(base, raw_path))
    if joined in ("", ".", "..") or joined.startswith("../") or joined.startswith("/"):
        raise NarrationError(f"EPUB resource escapes its archive: {href!r}")
    # Folio Readium locators use archive-root paths, so retain the canonical ZIP path.
    href_path = joined
    return joined, href_path, unquote(parts.fragment)


def _attr_by_local(element: ET.Element, name: str) -> str:
    for key, value in element.attrib.items():
        if key == name or key.endswith("}" + name):
            return value
    return ""


def _read_xml_member(zf: zipfile.ZipFile, archive_path: str) -> ET.Element:
    try:
        info = zf.getinfo(archive_path)
    except KeyError as exc:
        raise NarrationError(f"The EPUB references a missing resource: {archive_path}") from exc
    if info.file_size > MAX_XML_MEMBER_BYTES:
        raise NarrationError(f"EPUB XML document is too large to inspect safely: {archive_path}")
    try:
        return ET.fromstring(zf.read(archive_path))
    except ET.ParseError as exc:
        raise NarrationError(f"Malformed EPUB XML in {archive_path}: {exc}") from exc


def _element_label(element: ET.Element) -> str:
    return _text_content(element)


def _sentences(text: str) -> list[tuple[str, int, int]]:
    """Split normalized source text at sentence punctuation, retaining exact cue text."""
    if not text:
        return []
    boundaries: list[int] = []
    index = 0
    while index < len(text):
        if text[index] not in _SENTENCE_END:
            index += 1
            continue
        punct_end = index + 1
        while punct_end < len(text) and text[punct_end] in _SENTENCE_END:
            punct_end += 1
        close_end = punct_end
        while close_end < len(text) and text[close_end] in _CLOSERS:
            close_end += 1
        next_index = close_end
        while next_index < len(text) and text[next_index].isspace():
            next_index += 1
        token = text[max(0, index - 5): punct_end].lower()
        if text[index] == "." and any(token.endswith(abbrev) for abbrev in _ABBREVIATIONS):
            index = punct_end
            continue
        # Periods inside decimal numbers and initials are not sentence boundaries.
        if text[index] == "." and index > 0 and index + 1 < len(text) and text[index - 1].isdigit() and text[index + 1].isdigit():
            index += 1
            continue
        if next_index > close_end or close_end == len(text):
            boundaries.append(close_end)
            index = close_end
        else:
            index = punct_end
    result: list[tuple[str, int, int]] = []
    cursor = 0
    for boundary in boundaries + [len(text)]:
        raw = text[cursor:boundary]
        left = len(raw) - len(raw.lstrip())
        right = len(raw.rstrip())
        sentence = raw[left:right]
        if sentence:
            result.append((sentence, cursor + left, cursor + right))
        cursor = boundary
    return result


def _sentence_parts(text: str, max_chars: int) -> list[str]:
    if len(text) <= max_chars:
        return [text]
    parts: list[str] = []
    remaining = text
    while len(remaining) > max_chars:
        limit = max_chars
        candidate = -1
        # Favor an existing spoken clause boundary, keeping its punctuation on the first part.
        for match in re.finditer(r"[,;:—–]\s+", remaining[:limit + 1]):
            candidate = match.end()
        if candidate < max_chars // 2:
            candidate = remaining.rfind(" ", 0, limit + 1)
        if candidate <= 0:
            candidate = limit
            while candidate < len(remaining) and not remaining[candidate].isspace():
                candidate += 1
        part = remaining[:candidate].strip()
        if not part:
            raise NarrationError("Could not split an unusually long sentence at a safe boundary")
        # Chatterbox adds a terminal period to unpunctuated inputs. A comma keeps a clause
        # continuation audible without changing the source cue text or its locator.
        if part[-1] not in _SENTENCE_END and part[-1] not in ",;:—–" and candidate < len(remaining):
            part += ","
        parts.append(part)
        remaining = remaining[candidate:].lstrip()
    if remaining:
        parts.append(remaining)
    return parts


def _parse_nav(zf: zipfile.ZipFile, opf_dir: str, manifest: dict[str, dict[str, str]]) -> dict[str, str]:
    nav_item = next((item for item in manifest.values() if "nav" in item.get("properties", "").split()), None)
    if not nav_item:
        return {}
    try:
        nav_path, _, _ = _safe_member(opf_dir, nav_item["href"])
        root = _read_xml_member(zf, nav_path)
    except (KeyError, ET.ParseError, NarrationError, zipfile.BadZipFile):
        return {}
    labels: dict[str, str] = {}
    for anchor in root.iter():
        if _local_name(anchor.tag) != "a":
            continue
        href = anchor.attrib.get("href", "")
        if not href:
            continue
        try:
            target, _, _ = _safe_member(posixpath.dirname(nav_path), href)
        except NarrationError:
            continue
        label = _element_label(anchor)
        if target and label and target not in labels:
            labels[target] = label
    return labels


def extract_epub(path: Path, language_override: str | None = None) -> dict[str, Any]:
    if not path.is_file():
        raise NarrationError(f"EPUB file does not exist: {path}")
    try:
        with zipfile.ZipFile(path, "r") as zf:
            entries = zf.namelist()
            if len(entries) > MAX_ARCHIVE_ENTRIES:
                raise NarrationError("The EPUB has too many archive entries to inspect safely")
            if "META-INF/container.xml" not in entries:
                raise NarrationError("This ZIP does not contain META-INF/container.xml")
            # Reject ambiguous/unsafe member names before any reads are resolved.
            for name in entries:
                if name.startswith(("/", "\\")) or "\\" in name or any(part == ".." for part in name.split("/")):
                    raise NarrationError(f"Unsafe EPUB archive member path: {name!r}")
            container = _read_xml_member(zf, "META-INF/container.xml")
            rootfile = next((el.attrib.get("full-path", "") for el in container.iter() if _local_name(el.tag) == "rootfile"), "")
            if not rootfile:
                raise NarrationError("The EPUB container has no package document")
            opf_path, _, _ = _safe_member("", rootfile)
            opf_root = _read_xml_member(zf, opf_path)
            opf_dir = posixpath.dirname(opf_path)
            manifest = {
                item.attrib.get("id", ""): item.attrib
                for item in opf_root.iter()
                if _local_name(item.tag) == "item" and item.attrib.get("id")
            }
            metadata = next((el for el in opf_root.iter() if _local_name(el.tag) == "metadata"), None)
            title = "Untitled"
            author = ""
            language = ""
            if metadata is not None:
                for element in metadata.iter():
                    local = _local_name(element.tag)
                    value = " ".join((element.text or "").split())
                    if value and local == "title" and title == "Untitled":
                        title = value
                    elif value and local in {"creator", "author"} and not author:
                        author = value
                    elif value and local == "language" and not language:
                        language = value.lower()
            language = language_override.lower() if language_override else language
            if not language:
                language = "und"
            spine = next((el for el in opf_root.iter() if _local_name(el.tag) == "spine"), None)
            if spine is None:
                raise NarrationError("The EPUB package has no reading order")
            nav_labels = _parse_nav(zf, opf_dir, manifest)
            chapters: list[ChapterSource] = []
            for ref in list(spine):
                if _local_name(ref.tag) != "itemref":
                    continue
                item = manifest.get(ref.attrib.get("idref", ""))
                if not item or item.get("media-type") != "application/xhtml+xml" or "nav" in item.get("properties", "").split():
                    continue
                try:
                    archive_path, _, _ = _safe_member(opf_dir, item.get("href", ""))
                    root = _read_xml_member(zf, archive_path)
                except (KeyError, ET.ParseError, NarrationError) as exc:
                    raise NarrationError(f"Could not safely read an EPUB spine document ({exc})") from exc
                blocks = _body_blocks(root)
                word_count = sum(len(block.text.split()) for block in blocks)
                # Short chapters and epilogues are still part of the supplied
                # book. Only empty/non-readable spine resources are omitted.
                if word_count == 0:
                    continue
                all_chars = max(1, sum(len(block.text) for block in blocks))
                title_text = nav_labels.get(archive_path, "")
                if not title_text:
                    title_text = next(
                        (_text_content(node) for node in root.iter() if _local_name(node.tag) in {"title", "h1", "h2"} and _text_content(node)),
                        f"Chapter {len(chapters) + 1}",
                    )
                cues: list[dict[str, Any]] = []
                char_offset = 0
                for block_index, block in enumerate(blocks):
                    prev_text = blocks[block_index - 1].text if block_index else ""
                    next_text = blocks[block_index + 1].text if block_index + 1 < len(blocks) else ""
                    for sentence, start, end in _sentences(block.text):
                        before = block.text[max(0, start - MAX_CONTEXT_CHARS):start]
                        after = block.text[end:min(len(block.text), end + MAX_CONTEXT_CHARS)]
                        if not before and prev_text:
                            before = prev_text[-MAX_CONTEXT_CHARS:]
                        if not after and next_text:
                            after = next_text[:MAX_CONTEXT_CHARS]
                        cue: dict[str, Any] = {
                            "text": sentence,
                            "href": archive_path,
                            "fragment": block.fragment,
                            "before": before[-MAX_CONTEXT_CHARS:],
                            "after": after[:MAX_CONTEXT_CHARS],
                            "progression": min(1.0, max(0.0, (char_offset + start) / all_chars)),
                        }
                        if block.css_selector:
                            cue["cssSelector"] = block.css_selector
                        cues.append(cue)
                    char_offset += len(block.text) + 1
                if not cues:
                    continue
                chapters.append(ChapterSource(title_text, archive_path, cues))
            if not chapters:
                raise NarrationError("No readable XHTML chapters were found in the EPUB spine")
            return {
                "title": title,
                "author": author,
                "language": language,
                "sourceSha256": sha256_file(path),
                "sourcePath": str(path.resolve()),
                "chapters": [
                    {"index": n, "title": ch.title, "href": ch.href, "cues": ch.sentences}
                    for n, ch in enumerate(chapters, 1)
                ],
            }
    except zipfile.BadZipFile as exc:
        raise NarrationError("The input file is not a valid EPUB/ZIP archive") from exc
    except ET.ParseError as exc:
        raise NarrationError(f"Malformed EPUB XML: {exc}") from exc


def private_jobs_root() -> Path:
    local = os.environ.get("LOCALAPPDATA")
    if local:
        return Path(local) / "Folio" / "NarrationJobs"
    return Path.home() / ".local" / "share" / "folio" / "narration-jobs"


def create_job(
    source: Path,
    engine: str,
    jobs_root: Path | None = None,
    language: str | None = None,
    model_revision: str | None = None,
    voice_reference: Path | None = None,
    max_chars: int = MAX_SENTENCE_CHARS,
    accelerator: str = 'cpu',
    voice: str | None = None,
) -> tuple[Path, dict[str, Any]]:
    if engine not in ENGINES:
        raise NarrationError(f"Unknown engine {engine!r}; choose from: {', '.join(ENGINES)}")
    if max_chars < 80 or max_chars > 2000:
        raise NarrationError("--max-chars must be between 80 and 2000")
    allowed = {'roest': ('cpu','directml','directml-acoustic'),
               'chatterbox': ('cpu',), 'chatterbox-nano': ('cpu','directml-acoustic'),
               'plapre3': ('cpu','onnx-directml'), 'pico': ('cpu','onnx-directml')}
    if accelerator not in allowed[engine]:
        raise NarrationError('The selected engine does not support that acceleration backend.')
    if voice is not None and (engine not in ('plapre3','pico') or voice not in ('ida','tor','liv','ask','kaj')):
        raise NarrationError('Select an installed Plapre voice: ida, tor, liv, ask or kaj.')
    if engine in ('plapre3','pico') and voice_reference:
        raise NarrationError('This Plapre adapter uses its named voices, not a reference WAV.')
    plan = extract_epub(source, language_override=language)
    expected = ENGINES[engine]["language"]
    actual_primary = plan["language"].split("-", 1)[0]
    if actual_primary not in {expected, "und"}:
        raise NarrationError(
            f"EPUB language is {plan['language']!r}, but {engine} is configured for {expected!r}. "
            "Use --language only if you have confirmed the body text's language."
        )
    text_sha = hashlib.sha256(
        json.dumps(
            [[cue["text"] for cue in chapter["cues"]] for chapter in plan["chapters"]],
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()
    if voice_reference and not voice_reference.is_file():
        raise NarrationError(f"Voice reference file does not exist: {voice_reference}")
    reference_sha = sha256_file(voice_reference) if voice_reference else None
    profile = {key: ENGINES[engine][key] for key in ("temperature", "top_p", "top_k", "repetition_penalty")}
    if engine in ('plapre3','pico'):
        profile['voice'] = voice or 'ida'
    model_revision = model_revision or DEFAULT_MODEL_REVISIONS[engine]
    identity = {
        "sourceSha256": plan["sourceSha256"],
        "textSha256": text_sha,
        "engine": engine,
        "language": plan["language"],
        "modelRevision": model_revision or "unresolved-local-model",
        "voiceReferenceSha256": reference_sha,
        "maxChars": max_chars,
        "profile": profile,
        "parserVersion": JOB_SCHEMA,
    }
    if accelerator != 'cpu':
        identity['executionProfile'] = {'directml': 'directml-t3-v1',
                                        'directml-acoustic': 'directml-acoustic-v1',
                                        'onnx-directml': engine+'-cpu-lm-dml-acoustic-v1'}[accelerator]
    job_id = hashlib.sha256(json.dumps(identity, sort_keys=True).encode("utf-8")).hexdigest()[:20]
    root = jobs_root or private_jobs_root()
    job_dir = root / f"{engine}-{job_id}"
    if job_dir.exists():
        existing = load_json(job_dir / "job.json")
        if existing.get("sourceSha256") != plan["sourceSha256"]:
            raise NarrationError(f"Job directory identity collision: {job_dir}")
        return job_dir, existing
    job_dir.mkdir(parents=True, exist_ok=False)
    (job_dir / "chunks").mkdir()
    (job_dir / "audio").mkdir()
    job = {
        "schema": JOB_SCHEMA,
        "jobId": job_id,
        "createdUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "sourceSha256": plan["sourceSha256"],
        "textSha256": text_sha,
        "sourcePath": plan["sourcePath"],
        "title": plan["title"],
        "author": plan["author"],
        "language": plan["language"],
        "engine": engine,
        "accelerator": accelerator,
        "model": {
            "repository": ENGINES[engine]["repository"],
            "revision": model_revision,
            "runtime": ENGINES[engine].get('runtime', TTS_SOURCE_FORMAT),
            "accelerator": accelerator,
        },
        "voiceReferencePath": str(voice_reference.resolve()) if voice_reference else None,
        "voiceReferenceSha256": reference_sha,
        "sampling": profile,
        "sampleRate": TARGET_SAMPLE_RATE,
        "maxSentenceChars": max_chars,
        "chapters": plan["chapters"],
    }
    save_json(job_dir / "job.json", job)
    save_json(job_dir / "progress.json", initial_progress(job))
    return job_dir, job


def initial_progress(job: dict[str, Any]) -> dict[str, Any]:
    return {
        "jobId": job["jobId"],
        "accelerator": job.get('accelerator', 'cpu'),
        "modelFilesSha256": None,
        "modelFiles": [],
        "startedUtc": None,
        "completedUtc": None,
        "synthesisSeconds": 0.0,
        "generatedSamples": 0,
        "generatedWords": 0,
        "generatedSentences": 0,
        "cues": {},
        "chapters": {},
        "lastError": None,
    }


def load_json(path: Path) -> dict[str, Any]:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise NarrationError(f"Could not read job metadata at {path}: {exc}") from exc


def _atomic_replace(source: Path, target: Path) -> None:
    """Windows readers/antivirus can briefly deny replacing an otherwise valid file."""
    for attempt in range(9):
        try:
            source.replace(target)
            return
        except PermissionError:
            if attempt==8:raise
            time.sleep(min(.025*2**attempt,.2))


def save_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open('w',encoding='utf-8') as stream:
        stream.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
        stream.flush()
        os.fsync(stream.fileno())
    _atomic_replace(tmp,path)


def job_path(job_arg: str, jobs_root: Path | None = None) -> Path:
    candidate = Path(job_arg).expanduser()
    if candidate.exists() and candidate.is_dir():
        return candidate.resolve()
    root = jobs_root or private_jobs_root()
    matches = list(root.glob(f"*-{job_arg}")) if root.exists() else []
    if len(matches) != 1:
        raise NarrationError(f"Could not find one job matching {job_arg!r} under {root}")
    return matches[0]


def _model_component_names(model_dir: Path) -> list[Path]:
    required = ["s3gen_meanflow.safetensors", "t3_turbo_v1.safetensors", "ve.safetensors", "conds.pt"]
    missing = [name for name in required if not (model_dir / name).is_file()]
    if missing:
        raise NarrationError(f"Model directory is missing required Chatterbox Turbo files: {', '.join(missing)}")
    files = [model_dir / name for name in required]
    files.extend(
        p for p in model_dir.iterdir()
        if p.is_file()
        and p.name != "folio-model-provenance.json"
        and p.suffix.lower() in {".json", ".txt", ".model"}
        and p not in files
    )
    return sorted(files, key=lambda p: p.name.casefold())


def fingerprint_model(
    model_dir: Path,
    expected_repository: str | None = None,
    expected_revision: str | None = None,
    require_provenance: bool = False,
) -> tuple[str, list[dict[str, Any]]]:
    if not model_dir.is_dir():
        raise NarrationError(f"Model directory does not exist: {model_dir}")
    records: list[dict[str, Any]] = []
    aggregate = hashlib.sha256()
    index_path = model_dir / 'folio-component-index.json'
    if index_path.exists():
        index = load_json(index_path)
        if index.get('engine') not in ('plapre3','pico','chatterbox-nano'):
            raise NarrationError('Unsupported shared model component index')
        components = {x['name']: Path(x['path']) for x in index.get('files', [])}
        required = ({'language-model.onnx','embeddings.bin','tokenizer.json','voices.json','kanade.onnx','hift.onnx'}
                    if index['engine']=='plapre3' else
                    {'language-model.onnx','embeddings.npy','tokenizer.json','speakers.json','speaker-projection.onnx','kanade.onnx','hift.onnx'}
                    if index['engine']=='pico' else
                    {'t3_nano_v1.safetensors','s3gen_meanflow.safetensors','ve.safetensors','conds.pt',
                     'added_tokens.json','merges.txt','special_tokens_map.json','tokenizer_config.json','vocab.json'})
        if set(components) != required or any(not p.is_file() for p in components.values()):
            raise NarrationError('Shared component index is missing required files')
        named_files = sorted(components.items())
    else:
        named_files = [(p.name,p) for p in _model_component_names(model_dir)]
    for name, file in named_files:
        digest = sha256_file(file)
        size = file.stat().st_size
        records.append({"name": name, "sizeBytes": size, "sha256": digest})
        aggregate.update(name.encode("utf-8"))
        aggregate.update(b"\0")
        aggregate.update(digest.encode("ascii"))
        aggregate.update(b"\0")
    provenance_path = model_dir / "folio-model-provenance.json"
    if require_provenance and not provenance_path.is_file():
        raise NarrationError("Model directory has no folio-model-provenance.json; use a verified pinned snapshot")
    if provenance_path.is_file():
        provenance = load_json(provenance_path)
        if expected_repository and provenance.get("repository") != expected_repository:
            raise NarrationError("Model provenance repository does not match the selected engine")
        if expected_revision and provenance.get("revision") != expected_revision:
            raise NarrationError("Model provenance revision does not match the revision captured in this plan")
        described = {
            item.get("name"): item
            for item in provenance.get("files", [])
            if isinstance(item, dict) and isinstance(item.get("name"), str)
        }
        for record in records:
            item = described.get(record["name"])
            if not item or item.get("bytes") != record["sizeBytes"] or item.get("sha256") != record["sha256"]:
                raise NarrationError(f"Model file does not match its provenance record: {record['name']}")
    return aggregate.hexdigest(), records


def _as_float_samples(audio: Any) -> list[float]:
    if hasattr(audio, "detach"):
        audio = audio.detach().cpu()
    if hasattr(audio, "numpy"):
        audio = audio.numpy()
    if hasattr(audio, "reshape") and hasattr(audio, "tolist"):
        audio = audio.reshape(-1).tolist()
    elif isinstance(audio, (tuple, list)):
        flattened: list[Any] = []
        stack = list(audio)
        while stack:
            item = stack.pop(0)
            if isinstance(item, (tuple, list)):
                stack[0:0] = list(item)
            else:
                flattened.append(item)
        audio = flattened
    else:
        raise NarrationError("The TTS engine returned an unsupported waveform type")
    samples = [float(value) for value in audio]
    if not samples or any(not (x == x and abs(x) != float("inf")) for x in samples):
        raise NarrationError("The TTS engine returned an empty or non-finite waveform")
    return [max(-1.0, min(1.0, sample)) for sample in samples]


def trim_boundary_silence(samples: Sequence[float], sample_rate: int, margin_ms: int = 60) -> list[float]:
    """Trim only detected outer silence, retaining a generous phoneme safety margin."""
    if not samples:
        return []
    peak = max(abs(x) for x in samples)
    if peak <= 0:
        return list(samples)
    threshold = max(0.0005, peak * 0.002)  # -66 dBFS or 54 dB below this clip's peak.
    frame = max(1, int(sample_rate * 0.01))
    active: list[int] = []
    for start in range(0, len(samples), frame):
        segment = samples[start:start + frame]
        if segment and sum(value * value for value in segment) / len(segment) >= threshold * threshold:
            active.append(start)
    if not active:
        return list(samples)
    margin = int(sample_rate * margin_ms / 1000)
    left = max(0, active[0] - margin)
    right = min(len(samples), active[-1] + frame + margin)
    return list(samples[left:right])


def _write_wav(path: Path, samples: Sequence[float], sample_rate: int) -> int:
    if not samples:
        raise NarrationError("Refusing to save an empty synthesis chunk")
    pcm = bytearray()
    for sample in samples:
        bounded = max(-1.0, min(1.0, float(sample)))
        value = int(round(bounded * 32767.0))
        pcm.extend(struct.pack("<h", value))
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix+'.tmp')
    with temporary.open('wb') as stream:
        with wave.open(stream, "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(sample_rate)
            wav.writeframes(pcm)
        stream.flush()
        os.fsync(stream.fileno())
    _atomic_replace(temporary,path)
    return len(samples)


def _read_wav(path: Path, expected_rate: int) -> list[float]:
    try:
        with wave.open(str(path), "rb") as wav:
            if wav.getnchannels() != 1 or wav.getsampwidth() != 2 or wav.getframerate() != expected_rate:
                raise NarrationError(f"Cached audio has unexpected WAV parameters: {path.name}")
            frames = wav.readframes(wav.getnframes())
        return [sample / 32768.0 for (sample,) in struct.iter_unpack("<h", frames)]
    except (wave.Error, OSError, EOFError, struct.error) as exc:
        raise NarrationError(f"Could not read cached audio {path}: {exc}") from exc


def _discover_ffmpeg(explicit: str | None = None) -> str:
    if explicit:
        if Path(explicit).is_file():
            return explicit
        found = shutil.which(explicit)
        if found:
            return found
        raise NarrationError(f"Could not find the requested ffmpeg executable: {explicit}")
    found = shutil.which("ffmpeg")
    if found:
        return found
    try:
        import imageio_ffmpeg  # type: ignore[import-not-found]

        candidate = imageio_ffmpeg.get_ffmpeg_exe()
        if Path(candidate).is_file():
            return candidate
    except ImportError:
        pass
    raise NarrationError("ffmpeg is not on PATH and imageio-ffmpeg is unavailable; install ffmpeg or imageio-ffmpeg in the private runtime")


def _encode_mp3(ffmpeg: str, wav_path: Path, mp3_path: Path, bitrate: str) -> None:
    mp3_path.parent.mkdir(parents=True, exist_ok=True)
    command = [
        ffmpeg, "-y", "-nostdin", "-v", "error", "-i", str(wav_path), "-vn",
        "-ac", "1", "-ar", str(TARGET_SAMPLE_RATE), "-c:a", "libmp3lame",
        "-b:a", bitrate, "-write_xing", "1", "-id3v2_version", "3", str(mp3_path),
    ]
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode != 0 or not mp3_path.is_file() or mp3_path.stat().st_size == 0:
        detail = (result.stderr or "ffmpeg did not create an MP3 file").strip()
        raise NarrationError(f"MP3 encoding failed: {detail[-1200:]}")


def _cue_key(chapter_index: int, cue_index: int) -> str:
    return f"ch{chapter_index:04d}-cue{cue_index:06d}"


def _chapter_wav(job_dir: Path, chapter_index: int, cue_count: int, path: Path, sample_rate: int) -> tuple[int, list[dict[str, Any]]]:
    gap_samples = int(sample_rate * SILENCE_BETWEEN_CUES_MS / 1000)
    cursor = 0
    cue_timing: list[dict[str, Any]] = []
    with wave.open(str(path), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(sample_rate)
        for cue_index in range(1, cue_count + 1):
            chunk_path = job_dir / "chunks" / f"{_cue_key(chapter_index, cue_index)}.wav"
            samples = _read_wav(chunk_path, sample_rate)
            start = cursor
            pcm = bytearray()
            for sample in samples:
                pcm.extend(struct.pack("<h", int(round(max(-1.0, min(1.0, sample)) * 32767))))
            out.writeframes(pcm)
            cursor += len(samples)
            cue_timing.append({"cueIndex": cue_index, "startSample": start, "endSample": cursor})
            if cue_index < cue_count:
                out.writeframes(b"\0\0" * gap_samples)
                cursor += gap_samples
    return cursor, cue_timing


def _lock_job(job_dir: Path) -> Path:
    lock = job_dir / "job.lock"
    try:
        with lock.open("x", encoding="utf-8") as stream:
            stream.write(json.dumps({"pid": os.getpid(), "started": time.time()}))
    except FileExistsError as exc:
        raise NarrationError(f"This job already has a running process (lock file: {lock})") from exc
    return lock


def _validate_source_unchanged(job: dict[str, Any]) -> None:
    source = Path(job["sourcePath"])
    if not source.is_file() or sha256_file(source) != job["sourceSha256"]:
        raise NarrationError("The source EPUB is missing or has changed since the job was planned")
    current_text_sha = hashlib.sha256(
        json.dumps(
            [[cue["text"] for cue in chapter["cues"]] for chapter in job["chapters"]],
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()
    if current_text_sha != job.get("textSha256"):
        raise NarrationError("The private job plan text has changed since it was created")


def _all_cues(job: dict[str, Any]) -> int:
    return sum(len(chapter["cues"]) for chapter in job["chapters"])


def _remaining_words(job: dict[str, Any], progress: dict[str, Any], generated_before_run: set[str]) -> int:
    total = 0
    for chapter_index, chapter in enumerate(job["chapters"], 1):
        for cue_index, cue in enumerate(chapter["cues"], 1):
            key = _cue_key(chapter_index, cue_index)
            if key not in progress["cues"] and key not in generated_before_run:
                total += len(cue["text"].split())
    return total


def _status_rates(job: dict[str, Any], progress: dict[str, Any]) -> dict[str, Any]:
    total_sentences = _all_cues(job)
    done = len(progress.get("cues", {}))
    samples = int(progress.get("generatedSamples", 0))
    sr = int(job.get("sampleRate", TARGET_SAMPLE_RATE))
    audio_seconds = samples / sr if sr else 0
    synth_seconds = float(progress.get("synthesisSeconds", 0))
    words = int(progress.get("generatedWords", 0))
    rtf = synth_seconds / audio_seconds if audio_seconds > 0 else None
    words_per_audio_second = words / audio_seconds if audio_seconds > 0 else None
    remaining_words = max(0, sum(len(cue["text"].split()) for ch in job["chapters"] for cue in ch["cues"]) - words)
    estimated_audio_left = remaining_words / words_per_audio_second if words_per_audio_second else None
    estimated_compute_left = estimated_audio_left * rtf if estimated_audio_left is not None and rtf is not None else None
    completed_chapters = sum(
        1
        for chapter_index, chapter in enumerate(job["chapters"], 1)
        if chapter["cues"]
        and len(progress.get("chapters", {}).get(str(chapter_index), {}).get("cues", [])) == len(chapter["cues"])
    )
    job_complete = done == total_sentences and completed_chapters == len(job["chapters"])
    estimate_status = (
        "unavailable" if estimated_compute_left is None
        else "complete" if job_complete
        else "provisional" if done < 20 or audio_seconds < 60
        else "measured"
    )
    return {
        "doneSentences": done,
        "totalSentences": total_sentences,
        "completedChapters": completed_chapters,
        "totalChapters": len(job["chapters"]),
        "audioSeconds": audio_seconds,
        "synthesisSeconds": synth_seconds,
        "realTimeFactor": rtf,
        "estimatedSecondsRemaining": estimated_compute_left,
        "estimateStatus": estimate_status,
        "estimateSampleSentences": done,
        "estimateSampleAudioSeconds": audio_seconds,
        "lastCueSynthesisSeconds": progress.get("lastCueSynthesisSeconds"),
        "lastCueAudioMs": progress.get("lastCueAudioMs"),
        "completed": job_complete,
    }


def _compose_manifest(job: dict[str, Any], progress: dict[str, Any], partial: bool) -> dict[str, Any]:
    chapters_out: list[dict[str, Any]] = []
    generated_sentence_count = 0
    for chapter_index, chapter in enumerate(job["chapters"], 1):
        record = progress.get("chapters", {}).get(str(chapter_index))
        if not record:
            continue
        cue_records: list[dict[str, Any]] = []
        for item in record["cues"]:
            cue_index = item["cueIndex"]
            source_cue = chapter["cues"][cue_index - 1]
            cue_records.append({
                "startMs": item["startMs"],
                "endMs": item["endMs"],
                "href": source_cue["href"],
                "fragment": source_cue["fragment"],
                "text": source_cue["text"],
                "before": source_cue["before"],
                "after": source_cue["after"],
                "progression": source_cue["progression"],
                **({"cssSelector": source_cue["cssSelector"]} if source_cue.get("cssSelector") else {}),
            })
        generated_sentence_count += len(cue_records)
        chapters_out.append({
            "file": record["file"],
            "title": chapter["title"],
            "durationMs": record["durationMs"],
            "complete": len(cue_records) == len(chapter["cues"]),
            "sentenceCount": len(cue_records),
            "sourceSentenceCount": len(chapter["cues"]),
            "cues": cue_records,
        })
    total_sentence_count = _all_cues(job)
    generated_chapters = sum(1 for ch in chapters_out if ch["complete"])
    return {
        "format": FORMAT_NAME,
        "version": FORMAT_VERSION,
        "title": job["title"],
        "author": job["author"],
        "language": job["language"],
        "sourceSha256": job["sourceSha256"],
        "textSha256": job["textSha256"],
        "epub": "book.epub",
        "preview": partial,
        "generatedChapterCount": generated_chapters,
        "totalChapterCount": len(job["chapters"]),
        "generatedSentenceCount": generated_sentence_count,
        "totalSentenceCount": total_sentence_count,
        "model": {
            "repository": job["model"]["repository"],
            "revision": job["model"].get("revision"),
            "filesSha256": progress.get("modelFilesSha256"),
            "files": progress.get("modelFiles", []),
            "runtime": job["model"]["runtime"],
            "device": job.get('accelerator','cpu'),
            "execution": progress.get('execution',{}),
            "sampleRate": job["sampleRate"],
            "sampling": job["sampling"],
            "voiceReferenceSha256": job.get("voiceReferenceSha256"),
        },
        "timing": {
            "units": "milliseconds",
            "timebase": "decoded-gapless-mp3",
            "alignment": "sentence",
        },
        "chapters": chapters_out,
    }


def validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("format") != FORMAT_NAME or manifest.get("version") != FORMAT_VERSION:
        raise NarrationError("The generated bundle manifest has an unsupported format/version")
    for chapter in manifest.get("chapters", []):
        chapter_file = chapter.get("file", "")
        if not chapter_file.startswith("audio/") or ".." in chapter_file.split("/") or "\\" in chapter_file:
            raise NarrationError(f"Unsafe audio path in manifest: {chapter_file!r}")
        duration = int(chapter.get("durationMs", 0))
        previous_start = -1
        for cue in chapter.get("cues", []):
            href = cue.get("href", "")
            if not href or href.startswith(("/", "\\")) or "\\" in href or ".." in href.split("/") or ":" in href:
                raise NarrationError(f"Unsafe EPUB href in cue: {href!r}")
            start, end = int(cue.get("startMs", -1)), int(cue.get("endMs", -1))
            if start < 0 or end <= start or end > duration or start < previous_start:
                raise NarrationError("A cue has invalid or non-monotonic millisecond timing")
            if not cue.get("text"):
                raise NarrationError("A cue has no quote text")
            for context in (cue.get("before", ""), cue.get("after", "")):
                if len(context) > MAX_CONTEXT_CHARS:
                    raise NarrationError("A cue context exceeds the 80-character limit")
            progression = cue.get("progression")
            if progression is not None and not 0.0 <= float(progression) <= 1.0:
                raise NarrationError("A cue progression must be between 0 and 1")
            previous_start = start


def write_bundle(job_dir: Path, job: dict[str, Any], progress: dict[str, Any], output_dir: Path, partial: bool) -> Path:
    manifest = _compose_manifest(job, progress, partial)
    validate_manifest(manifest)
    export_dir = output_dir / "exports"
    export_dir.mkdir(parents=True, exist_ok=True)
    slug = re.sub(r"[^A-Za-z0-9._-]+", "-", job["title"]).strip("-._") or "narrated-book"
    suffix = ".preview.foliobook" if partial else ".foliobook"
    output = export_dir / f"{slug}{suffix}"
    temp_output = output.with_suffix(output.suffix + ".tmp")
    source = Path(job["sourcePath"])
    with zipfile.ZipFile(temp_output, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as bundle:
        info = zipfile.ZipInfo("manifest.json")
        info.compress_type = zipfile.ZIP_STORED
        bundle.writestr(info, (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
        _zip_stream(bundle, source, "book.epub")
        for chapter in manifest["chapters"]:
            audio_path = job_dir / chapter["file"]
            if not audio_path.is_file():
                raise NarrationError(f"Missing completed chapter audio: {audio_path}")
            _zip_stream(bundle, audio_path, chapter["file"])
    # A final readback checks ZIP central directory and critical file identity before publication.
    with zipfile.ZipFile(temp_output, "r") as bundle:
        if bundle.testzip() is not None:
            raise NarrationError("The newly written audiobook bundle failed ZIP validation")
        if bundle.read("manifest.json") != (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"):
            raise NarrationError("The newly written audiobook manifest did not round-trip")
        if bundle.getinfo("book.epub").file_size != source.stat().st_size:
            raise NarrationError("The source EPUB size changed during export")
    _atomic_replace(temp_output,output)
    return output


def _zip_stream(bundle: zipfile.ZipFile, source: Path, archive_name: str) -> None:
    info = zipfile.ZipInfo(archive_name)
    info.compress_type = zipfile.ZIP_STORED
    info.external_attr = 0o600 << 16
    with source.open("rb") as reader, bundle.open(info, "w", force_zip64=True) as writer:
        shutil.copyfileobj(reader, writer, length=1024 * 1024)


def generate_job(
    job_dir: Path,
    model_dir: Path,
    output_dir: Path | None = None,
    ffmpeg: str | None = None,
    bitrate: str = DEFAULT_BITRATE,
    max_sentences: int | None = None,
    max_chapters: int | None = None,
    fake_synth: Any | None = None,
    progress_callback: Any | None = None,
    cpu_threads: int | None = None,
    control_file: Path | None = None,
    stop_after_seconds: float | None = None,
    through_sentences: int | None = None,
    parallel_passages: int = 1,
    selected_chapters: list[int] | None = None,
) -> tuple[Path, dict[str, Any]]:
    job = load_json(job_dir / "job.json")
    if selected_chapters is not None:
        if not selected_chapters or any(type(i)!=int or not 1<=i<=len(job['chapters']) for i in selected_chapters):
            raise NarrationError('Chapter selection is outside this book.')
        if max_chapters is not None or through_sentences is not None:raise NarrationError('Use chapter selection without prefix limits.')
        selected_chapters=sorted(set(selected_chapters))
    if parallel_passages not in (1,2,4):raise NarrationError('Parallel passages must be 1, 2 or 4')
    if parallel_passages>1 and job['engine'] not in ('pico','plapre3','roest','chatterbox-nano'):
        raise NarrationError('This engine has no verified shared-model passage adapter')
    _validate_source_unchanged(job)
    if job.get("voiceReferencePath"):
        reference = Path(job["voiceReferencePath"])
        if not reference.is_file() or sha256_file(reference) != job.get("voiceReferenceSha256"):
            raise NarrationError("The voice reference is missing or has changed since the job was planned")
    if max_sentences is not None and max_sentences < 1:
        raise NarrationError("--limit-sentences must be at least 1")
    if through_sentences is not None and (through_sentences<1 or max_sentences is not None):
        raise NarrationError('--through-sentences must be positive and cannot combine with --limit-sentences')
    if max_chapters is not None and max_chapters < 1:
        raise NarrationError("--limit-chapters must be at least 1")
    if not re.fullmatch(r"\d+(?:k)?", bitrate, re.IGNORECASE):
        raise NarrationError("--bitrate must look like 96k or 128k")
    model_hash, model_files = fingerprint_model(
        model_dir,
        expected_repository=job["model"]["repository"],
        expected_revision=job["model"].get("revision"),
        require_provenance=fake_synth is None,
    )
    state_path = job_dir / "progress.json"
    progress = load_json(state_path) if state_path.exists() else initial_progress(job)
    if progress.get('accelerator', 'cpu') != job.get('accelerator', 'cpu'):
        raise NarrationError('The accelerator differs from this job cache. Create a separate plan; do not mix CPU and GPU chunks.')
    if progress.get("modelFilesSha256") not in (None, model_hash):
        raise NarrationError("The model files differ from those already used in this job; create a new plan to avoid mixing voices")
    if progress.get("voiceReferenceSha256") not in (None, job.get("voiceReferenceSha256")):
        raise NarrationError("The reference voice changed after the job was planned")
    ffmpeg_path = _discover_ffmpeg(ffmpeg) if fake_synth is None else (ffmpeg or "fake-ffmpeg")
    lock = _lock_job(job_dir)
    output_dir = output_dir or job_dir
    lock_start = time.monotonic()
    pool=None
    def checkpoint_control(chapter_boundary: bool = False) -> None:
        command = load_json(control_file) if control_file and control_file.exists() else {}
        action = command.get('action')
        timed_out = stop_after_seconds is not None and time.monotonic()-lock_start >= stop_after_seconds
        if action in ('pause','cancel') or (chapter_boundary and action == 'pause-chapter') or timed_out:
            progress.update(phase='paused', pausedUtc=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),
                            lastRunSeconds=time.monotonic()-lock_start, lastError=None)
            save_json(state_path, progress)
            raise NarrationPaused('Passage checkpoint saved; resume to continue.')
    try:
        if progress.get("modelFilesSha256") is None:
            progress["modelFilesSha256"] = model_hash
            progress["modelFiles"] = model_files
        if not progress.get("startedUtc"):
            progress["startedUtc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        progress["lastError"] = None
        progress['phase'] = 'loading'
        save_json(state_path, progress)
        checkpoint_control()
        synth = fake_synth
        if synth is None:
            from .engines import load_engine

            synth = load_engine(
                job["engine"], model_dir,
                Path(job["voiceReferencePath"]) if job.get("voiceReferencePath") else None,
                job["sampling"], cpu_threads=cpu_threads,
                accelerator=job.get('accelerator', 'cpu'),
            )
            progress['execution'] = synth.execution
            save_json(state_path, progress)
        if parallel_passages>1 and not getattr(synth,'supports_parallel',False):
            raise NarrationError('This adapter cannot safely share model state between passages')
        from .parallel import PassagePool
        def render_cue(cue):
            started=time.monotonic();audio_parts=[]
            for part_i,part in enumerate(_sentence_parts(cue['text'],int(job['maxSentenceChars']))):
                rendered=synth.synthesize(part)
                values=trim_boundary_silence(_as_float_samples(rendered),int(job['sampleRate']))
                if part_i:audio_parts.extend([0.0]*int(job['sampleRate']*.08))
                audio_parts.extend(values)
            return audio_parts,time.monotonic()-started
        pool=PassagePool(render_cue,parallel_passages)
        progress['parallelExecution']=dict(passages=parallel_passages,weights='shared',commit='ordered',acoustics='serialized')
        if 'execution' in progress:
            progress['execution']['passagesInFlight']=parallel_passages
            progress['execution']['modelCopies']=1
        progress['phase'] = 'generating'
        save_json(state_path, progress)
        processed_this_run = 0
        partial = False
        for chapter_index, chapter in enumerate(job["chapters"], 1):
            if selected_chapters is not None and chapter_index not in selected_chapters:continue
            chapter_offset=sum(len(c['cues']) for c in job['chapters'][:chapter_index-1])
            if through_sentences is not None and chapter_offset>=through_sentences:
                partial=True
                break
            if max_chapters is not None and chapter_index > max_chapters:
                partial = True
                break
            state_key = str(chapter_index)
            chapter_record = progress.get("chapters", {}).get(state_key)
            expected_count = len(chapter["cues"])
            if chapter_record and len(chapter_record.get("cues", [])) == expected_count and (through_sentences is None or chapter_offset+expected_count<=through_sentences):
                # Validate the cached MP3 file exists; cue WAVs remain available for selective repair.
                if (job_dir / chapter_record["file"]).is_file():
                    if max_chapters is not None and chapter_index >= max_chapters and chapter_index < len(job["chapters"]):
                        partial = True
                        break
                    continue
            rendered_cue_count = len(progress.get("chapters", {}).get(state_key, {}).get("cues", []))
            pending=[(_cue_key(chapter_index,i),cue) for i,cue in enumerate(chapter['cues'],1)
                     if (through_sentences is None or chapter_offset+i<=through_sentences)
                     and not (_cue_key(chapter_index,i) in progress['cues'] and (job_dir/'chunks'/f'{_cue_key(chapter_index,i)}.wav').is_file())]
            if max_sentences is not None:pending=pending[:max(0,max_sentences-processed_this_run)]
            generation_clock=time.monotonic();pool.begin(pending)
            for cue_index, cue in enumerate(chapter["cues"], 1):
                if through_sentences is not None and chapter_offset+cue_index>through_sentences:
                    partial=True
                    break
                checkpoint_control()
                key = _cue_key(chapter_index, cue_index)
                cached_path = job_dir / "chunks" / f"{key}.wav"
                if key in progress["cues"] and cached_path.is_file():
                    try:
                        values = _read_wav(cached_path, int(job["sampleRate"]))
                        cached_record = progress['cues'][key]
                        if len(values) != cached_record['samples'] or (cached_record.get('sha256') and sha256_file(cached_path) != cached_record['sha256']):
                            raise NarrationError('Cached passage audio failed integrity verification')
                        continue
                    except NarrationError:
                        cached_path.unlink(missing_ok=True)
                # Missing files and damaged files both invalidate the old
                # record before regeneration, so counters are never doubled.
                invalid=progress['cues'].pop(key,None)
                if invalid:
                    progress['generatedSamples']=max(0,progress.get('generatedSamples',0)-invalid['samples'])
                    progress['generatedWords']=max(0,progress.get('generatedWords',0)-invalid['words'])
                    progress['generatedSentences']=max(0,progress.get('generatedSentences',0)-1)
                if max_sentences is not None and processed_this_run >= max_sentences:
                    partial = True
                    break
                try:audio_parts,worker_elapsed=pool.get(key,cue)
                except Exception as exc:
                    raise NarrationError(f'Synthesis failed at chapter {chapter_index}, sentence {cue_index}: {exc}') from exc
                samples = _write_wav(cached_path, audio_parts, int(job["sampleRate"]))
                elapsed = time.monotonic()-generation_clock if parallel_passages>1 else worker_elapsed
                generation_clock=time.monotonic()
                progress["synthesisSeconds"] = float(progress.get("synthesisSeconds", 0.0)) + elapsed
                progress['workerSynthesisSeconds']=progress.get('workerSynthesisSeconds',0)+worker_elapsed
                progress['parallelExecution']['peakConcurrentPassages']=pool.peak_active
                if hasattr(synth,'metrics'):
                    progress['execution']['stageMetrics']=synth.metrics()
                progress["generatedSamples"] = int(progress.get("generatedSamples", 0)) + samples
                progress["generatedWords"] = int(progress.get("generatedWords", 0)) + len(cue["text"].split())
                progress["generatedSentences"] = int(progress.get("generatedSentences", 0)) + 1
                progress["lastCueSynthesisSeconds"] = elapsed
                progress["lastCueAudioMs"] = samples * 1000.0 / int(job["sampleRate"])
                progress["cues"][key] = {"samples": samples, "words": len(cue["text"].split()), 'sha256': sha256_file(cached_path)}
                processed_this_run += 1
                save_json(state_path, progress)
                if progress_callback:
                    progress_callback(_status_rates(job, progress), chapter_index, cue_index)
            cue_count = len(progress.get("chapters", {}).get(state_key, {}).get("cues", []))
            cue_count = max(cue_count, sum(1 for i in range(1, expected_count + 1) if _cue_key(chapter_index, i) in progress["cues"]))
            if through_sentences is not None:cue_count=min(cue_count,through_sentences-chapter_offset)
            if cue_count == 0:
                if partial:
                    break
                continue
            if max_sentences is not None and processed_this_run >= max_sentences and cue_count < expected_count:
                partial = True
            # An interrupted preview can contain a prefix of the current chapter; full jobs only
            # package a chapter after every cue in it has been synthesized.
            if cue_count < expected_count and not partial:
                continue
            chapter_audio_dir = job_dir / "audio"
            progress['phase'] = 'encoding'
            save_json(state_path, progress)
            chapter_rel = f"audio/chapter-{chapter_index:04d}.mp3"
            wav_path = job_dir / "tmp" / f"chapter-{chapter_index:04d}.wav"
            wav_path.parent.mkdir(parents=True, exist_ok=True)
            sample_count, samples_cues = _chapter_wav(job_dir, chapter_index, cue_count, wav_path, int(job["sampleRate"]))
            mp3_path = job_dir / chapter_rel
            _encode_mp3(ffmpeg_path, wav_path, mp3_path, bitrate)
            wav_path.unlink(missing_ok=True)
            duration_ms = round(sample_count * 1000 / int(job["sampleRate"]))
            cue_timing = []
            for timing in samples_cues:
                cue_timing.append({
                    "cueIndex": timing["cueIndex"],
                    "startMs": round(timing["startSample"] * 1000 / int(job["sampleRate"])),
                    "endMs": round(timing["endSample"] * 1000 / int(job["sampleRate"])),
                })
            progress["chapters"][state_key] = {"file": chapter_rel, "durationMs": duration_ms, "cues": cue_timing}
            save_json(state_path, progress)
            checkpoint_control(chapter_boundary=True)
            progress['phase'] = 'generating'
            if max_chapters is not None and chapter_index >= max_chapters and chapter_index < len(job["chapters"]):
                partial = True
                break
            if partial:
                break
        else:
            partial = False
        # A limited run is a preview even when its cap landed exactly at the end of a chapter.
        if max_sentences is not None and processed_this_run >= max_sentences and len(progress.get("cues", {})) < _all_cues(job):
            partial = True
        status = _status_rates(job, progress)
        if status["completed"]:
            progress["completedUtc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        progress["lastRunSeconds"] = time.monotonic() - lock_start
        progress['phase'] = 'exporting'
        save_json(state_path, progress)
        export_progress=progress
        selection_partial=selected_chapters is not None and len(selected_chapters)<len(job['chapters'])
        if selected_chapters is not None:
            export_progress=dict(progress,chapters={k:v for k,v in progress.get('chapters',{}).items() if int(k) in selected_chapters})
        bundle_path = write_bundle(job_dir, job, export_progress, output_dir, partial or not status["completed"] or selection_partial)
        progress['phase'] = 'complete' if status['completed'] and not selection_partial else 'preview-ready'
        save_json(state_path, progress)
        return bundle_path, status
    except NarrationPaused:
        raise
    except Exception as exc:
        progress["lastError"] = str(exc)
        save_json(state_path, progress)
        raise
    finally:
        if pool:pool.close()
        lock.unlink(missing_ok=True)


def status_job(job_dir: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    job = load_json(job_dir / "job.json")
    progress_path = job_dir / "progress.json"
    progress = load_json(progress_path) if progress_path.exists() else initial_progress(job)
    return job, _status_rates(job, progress)


def format_duration(seconds: float | None) -> str:
    if seconds is None:
        return "unknown until a measured sample is generated"
    remaining = max(0, int(round(seconds)))
    hours, rest = divmod(remaining, 3600)
    minutes, secs = divmod(rest, 60)
    if hours:
        return f"{hours}h {minutes}m"
    if minutes:
        return f"{minutes}m {secs}s"
    return f"{secs}s"

