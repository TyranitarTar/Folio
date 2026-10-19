# Folio desktop narration exporter

This local CLI turns a user-owned EPUB into a synchronized `.foliobook` companion. It keeps the
source EPUB byte-for-byte inside the package, creates one MP3 per chapter, and stores sentence
cues against the original XHTML hrefs and existing fragment IDs. When no ID exists, cues include
a structural CSS selector plus short before/after quote context and a document progression value.
No audio is uploaded and no model is downloaded automatically.

Jobs, model runtime files, chunk WAVs, exports, and any source text extracted for a resumable job
belong in private user storage. The default job root is the current user's app-data directory.
The local `.gitignore` also excludes accidental in-repository runtime and output directories.
Never place a private job or `.foliobook` in `publicsource`, a release archive, or GitHub.

## Commands

Create the separate CPU environment once (Python 3.10), then fetch the pinned model:

```powershell
python -m venv tools\narration\.venv
$python = ".\tools\narration\.venv\Scripts\python.exe"
& $python -m pip install torch==2.6.0 torchaudio==2.6.0 --index-url https://download.pytorch.org/whl/cpu
& $python -m pip install -r tools\narration\requirements-cpu.txt
& $python tools\narration\fetch_models.py --engine roest --output "R:\FolioModels\roest"
```

`fetch_models.py --help` describes the verified download options. Use `--engine chatterbox`
for the English snapshot. Røst's author recommends conditioning on a Danish speaker reference;
provide an authorized local reference with `plan --voice-reference "R:\Voices\narrator.wav"`.
The reference is fingerprinted and must remain available when resuming that job.

Plan a Danish EPUB with Røst 3:

```powershell
$python = ".\tools\narration\.venv\Scripts\python.exe"
& $python tools\narration\narrate.py plan --input "R:\Books\book.epub" --engine roest
```

Generate the first chapter as a clearly marked preview, then resume the same job without a limit:

```powershell
& $python tools\narration\narrate.py generate --job JOB_ID --model-dir "R:\FolioModels\roest" --limit-chapters 1
& $python tools\narration\narrate.py generate --job JOB_ID --model-dir "R:\FolioModels\roest"
& $python tools\narration\narrate.py status --job JOB_ID
```

`--limit-sentences N` generates N additional sentences and packages them as a preview. A later
unlimited run resumes cached WAV chunks and finishes the book. `--progress-every N` controls the
concise sentence progress interval (default 5). The CLI announces model loading before inference.
ETA uses measured synthesis time after model load, not loading time, and stays marked provisional
until at least 20 cues and 60 seconds of audio have been rendered; a short sample or first-call
warm-up can still skew it.

English Chatterbox Turbo uses the same CLI with `--engine chatterbox` and the matching English
model snapshot. The tool checks EPUB language metadata before planning. Only use `--language` to
override that check after confirming the source body language. CPU is the supported baseline.

The private Python environment should use Python 3.10 or newer and the pinned CPU dependencies in
`requirements-cpu.txt`. Install CPU PyTorch separately from the official PyTorch CPU wheel index.
Use FFmpeg from PATH or install `imageio-ffmpeg` into the private environment for MP3 encoding.

## Safety and timing

The EPUB parser reads only the container, package, navigation and XHTML documents. It does not run
scripts or follow external links. The source archive is held in place and re-hashed before synthesis.
Model and voice inputs are local paths; the model and voice hashes are bound into the job state so a
resume cannot silently mix narrators. The exporter keeps recoverable PCM chunks in the private job
directory and writes a bundle only after checking cue bounds, ZIP integrity, and source EPUB size.

Sentence cue times come from the rendered PCM sample counts. MP3 is written with Xing/gapless
metadata and the bundle timebase expects a decoder that honors that metadata. There is no claimed
word-level alignment. Silence trimming is restricted to the outer edges of each synthesized chunk
with a safety margin; punctuation and within-sentence timing are preserved.

The CLI and fake-synthesizer tests validate planning, resume, manifest and package mechanics. They do
not establish voice quality, CPU speed, pronunciation, or long-book listening quality; validate those
with an actual first-chapter render before treating an audiobook as finished.
