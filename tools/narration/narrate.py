"""Plan, resume, inspect, and export private Folio audiobook jobs."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))
RUNTIME_CACHE = Path(__file__).resolve().parent / "cache"
os.environ.setdefault("HF_HOME", str(RUNTIME_CACHE))
os.environ.setdefault("TRANSFORMERS_CACHE", str(RUNTIME_CACHE / "transformers"))
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")

from tools.narration.core import (  # noqa: E402
    ENGINES,
    NarrationError,
    NarrationPaused,
    create_job,
    format_duration,
    generate_job,
    job_path,
    load_json,
    private_jobs_root,
    status_job,
)


def _plan(args: argparse.Namespace) -> int:
    root = Path(args.jobs_dir).expanduser() if args.jobs_dir else None
    job_dir, job = create_job(
        Path(args.input).expanduser(),
        args.engine,
        jobs_root=root,
        language=args.language,
        model_revision=args.model_revision,
        voice_reference=Path(args.voice_reference).expanduser() if args.voice_reference else None,
        max_chars=args.max_chars,
        accelerator=args.accelerator,
        voice=args.voice,
    )
    total_words = sum(len(cue["text"].split()) for chapter in job["chapters"] for cue in chapter["cues"])
    total_sentences = sum(len(chapter["cues"]) for chapter in job["chapters"])
    print(f"Job: {job['jobId']}")
    print(f"Private job directory: {job_dir}")
    print(f"Title: {job['title']}")
    print(f"Language: {job['language']}")
    print(f"Engine: {args.engine} ({ENGINES[args.engine]['repository']})")
    print(f"Accelerator: {job.get('accelerator', 'cpu')}")
    print(f"Source SHA-256: {job['sourceSha256']}")
    print(f"Chapters: {len(job['chapters'])}")
    print(f"Sentence cues: {total_sentences}")
    print(f"Words: {total_words}")
    print("Estimated render time: unknown until a measured sample is generated")
    return 0


def _generate(args: argparse.Namespace) -> int:
    root = Path(args.jobs_dir).expanduser() if args.jobs_dir else None
    directory = job_path(args.job, root)
    model_dir = Path(args.model_dir).expanduser()
    job = load_json(directory / "job.json")
    if args.model_revision and args.model_revision != job["model"].get("revision"):
        raise NarrationError("--model-revision differs from the revision captured in this plan")
    if args.cpu_threads < 1 or args.cpu_threads > 128:
        raise NarrationError("--cpu-threads must be between 1 and 128")
    if args.progress_every < 1 or args.progress_every > 1000:
        raise NarrationError("--progress-every must be between 1 and 1000")

    def report_progress(rate: dict[str, object], chapter_index: int, cue_index: int) -> None:
        done = int(rate["doneSentences"])
        if cue_index == 1 or done % args.progress_every == 0 or done == int(rate["totalSentences"]):
            eta = format_duration(rate["estimatedSecondsRemaining"])
            audio_ms = float(rate.get("lastCueAudioMs") or 0)
            synth_seconds = float(rate.get("lastCueSynthesisSeconds") or 0)
            confidence = ""
            if rate.get("estimateStatus") == "provisional":
                confidence = " (provisional ETA; recalibrates after 20 cues and 60 s of audio)"
            print(
                f"Chapter {chapter_index}, sentence {cue_index}: {done}/{rate['totalSentences']} cues; "
                f"last cue {audio_ms:.0f} ms audio in {synth_seconds:.1f} s; ETA {eta}{confidence}",
                flush=True,
            )

    target = {'cpu': 'CPU', 'directml': 'DirectML token model + CPU waveform',
              'directml-acoustic': 'DirectML token/acoustic networks + CPU FFT/excitation',
              'onnx-directml': 'CPU token model + DirectML acoustics',
              'rocm': 'AMD HIP FP32 token model + GPU acoustics'}.get(job.get('accelerator','cpu'),'unknown')
    print(f"Loading local {job['engine']} model for {target}; first load may take a while...", flush=True)
    output, status = generate_job(
        directory,
        model_dir,
        output_dir=Path(args.output_dir).expanduser() if args.output_dir else None,
        ffmpeg=args.ffmpeg,
        bitrate=args.bitrate,
        max_sentences=args.limit_sentences,
        max_chapters=args.limit_chapters,
        cpu_threads=args.cpu_threads,
        progress_callback=report_progress,
        control_file=Path(args.control_file) if args.control_file else None,
        stop_after_seconds=args.stop_after_seconds,
        through_sentences=args.through_sentences,
        parallel_passages=args.parallel_passages,
        selected_chapters=[int(i) for i in args.chapters.split(',')] if args.chapters else None,
    )
    print(f"Bundle: {output}")
    print(f"Progress: {status['doneSentences']}/{status['totalSentences']} sentence cues; {status['completedChapters']}/{status['totalChapters']} complete chapters")
    print(f"Rendered audio: {status['audioSeconds']:.1f} seconds")
    if status["realTimeFactor"] is not None:
        print(f"Measured real-time factor: {status['realTimeFactor']:.2f}x")
    print(f"Estimated remaining compute: {format_duration(status['estimatedSecondsRemaining'])}")
    if status["estimateStatus"] == "provisional":
        print(
            f"Early estimate uses {status['estimateSampleSentences']} cues and "
            f"{status['estimateSampleAudioSeconds']:.1f} seconds of generated audio; "
            "it can shift as more varied text is rendered."
        )
    if status["completed"]:
        print("Job complete.")
    else:
        print("This is a clearly marked preview. Run the same job again without a limit to resume and finish it.")
    return 0


def _status(args: argparse.Namespace) -> int:
    root = Path(args.jobs_dir).expanduser() if args.jobs_dir else None
    directory = job_path(args.job, root)
    job, status = status_job(directory)
    progress = load_json(directory / "progress.json")
    print(f"Job: {job['jobId']}")
    print(f"Title: {job['title']}")
    print(f"Language/engine: {job['language']} / {job['engine']}")
    print(f"Progress: {status['doneSentences']}/{status['totalSentences']} sentence cues")
    print(f"Chapters: {status['completedChapters']}/{status['totalChapters']} complete")
    if status["realTimeFactor"] is None:
        print("Measured real-time factor: not available yet")
    else:
        print(f"Measured real-time factor: {status['realTimeFactor']:.2f}x")
    print(f"Estimated remaining compute: {format_duration(status['estimatedSecondsRemaining'])}")
    if status["estimateStatus"] == "provisional":
        print(
            f"Early estimate uses {status['estimateSampleSentences']} cues and "
            f"{status['estimateSampleAudioSeconds']:.1f} seconds of generated audio; "
            "it can shift as more varied text is rendered."
        )
    if progress.get("lastError"):
        print(f"Last error: {progress['lastError']}")
    print(f"Private job directory: {directory}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Create a local, synchronized Folio audiobook bundle from an EPUB.")
    sub = parser.add_subparsers(dest="command", required=True)

    plan = sub.add_parser("plan", help="Safely inspect an EPUB and save a resumable private job plan")
    plan.add_argument("--input", required=True, help="Source EPUB path")
    plan.add_argument("--engine", choices=sorted(ENGINES), required=True, help="roest for Danish, chatterbox for English")
    plan.add_argument("--language", help="Override EPUB language metadata only after checking the body text")
    plan.add_argument("--model-revision", help="Pinned Hugging Face model revision; defaults to the reviewed snapshot SHA")
    plan.add_argument("--voice-reference", help="Optional authorized local narrator reference audio")
    plan.add_argument("--max-chars", type=int, default=350, help="Maximum TTS input length (default: 350)")
    plan.add_argument('--accelerator', choices=['cpu','directml','directml-acoustic','onnx-directml','rocm'], default='cpu', help='Backend must match the engine; CPU remains available.')
    plan.add_argument('--voice', choices=['ida','tor','liv','ask','kaj'], help='Plapre named voice')
    plan.add_argument("--jobs-dir", help="Private job root; default is the current user's app-data directory")
    plan.set_defaults(run=_plan)

    generate = sub.add_parser("generate", help="Generate or resume a private audio job and export .foliobook")
    generate.add_argument("--job", required=True, help="Job ID or private job directory")
    generate.add_argument("--model-dir", required=True, help="Already downloaded local model snapshot directory")
    generate.add_argument("--model-revision", help="Assert that this is the planned model revision")
    generate.add_argument("--output-dir", help="Private output directory; defaults to the job directory")
    generate.add_argument("--jobs-dir", help="Private job root when --job is an ID")
    generate.add_argument("--ffmpeg", help="FFmpeg executable path; PATH and imageio-ffmpeg are checked by default")
    generate.add_argument("--bitrate", default="96k", help="Mono MP3 bitrate, for example 96k or 128k")
    generate.add_argument("--limit-sentences", type=int, help="Generate a measured preview of this many additional sentences")
    generate.add_argument('--through-sentences',type=int,help='Absolute passage selection; resuming never extends a fixed preview')
    generate.add_argument('--parallel-passages',type=int,choices=[1,2,4],default=1,help='Shared-model concurrency for Pico/Plapre 3/Røst/Nano; 1 is sequential')
    generate.add_argument("--limit-chapters", type=int, help="Generate a preview through this many chapters")
    generate.add_argument('--chapters',help='Explicit comma-separated chapter numbers; excluded chapters keep their cached audio.')
    generate.add_argument("--progress-every", type=int, default=5, help="Print a concise update every N sentences (default: 5)")
    generate.add_argument("--cpu-threads", type=int, default=max(1, min(8, os.cpu_count() or 1)), help="CPU inference threads")
    generate.add_argument('--control-file', help='Local cooperative pause/cancel control JSON')
    generate.add_argument('--stop-after-seconds', type=float, help='Checkpoint and pause after this active run duration')
    generate.set_defaults(run=_generate)

    status = sub.add_parser("status", help="Inspect progress and measured throughput without printing book text")
    status.add_argument("--job", required=True, help="Job ID or private job directory")
    status.add_argument("--jobs-dir", help="Private job root when --job is an ID")
    status.set_defaults(run=_status)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.run(args)
    except NarrationPaused as exc:
        print(str(exc), flush=True)
        return 10
    except NarrationError as exc:
        print(f"Narration error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

