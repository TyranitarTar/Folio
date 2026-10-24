# Folio 0.13.1 — audiobook folders and desktop GPU investigation

Planned together with the user on 19 October 2026, for the next working session
on **20 October 2026**. This is a saved plan, not an implemented release or an
automatic scheduled run. Updated 20 October: the CPU chapter render was interrupted
by a desktop restart at 318/657 cues; its files remain intact. Desktop GPU tooling
and measurements are implemented in [the GPU report](IMPLEMENTATION-v0.13.1-GPU.md).
The user's current scope is GPU performance; audiobook-folder UI work below remains
planned. Miso's rejected animation/writing is recorded for a later substantial
redesign in `design/cat-cafe/TODO.md`.

## Outcomes

1. Select an audiobook library folder once and see the books inside it, including
   nested book folders. Adding a book should not require importing each chapter.
2. Make **All / Unread / Reading / Read** the primary audiobook library filters.
3. Test real desktop GPU inference, measuring actual finished-audio throughput.
   Report whether it works on this PC and replace speculative estimates with data.

## Desktop GPU tests

These tests run on the **physical Windows desktop and its Radeon RX 5700 XT**.
The Android emulator is only for Android functional checks. It cannot establish
the desktop GPU's inference performance.

The RX 5700 XT is absent from AMD's current official Windows ROCm support table.
DirectML now has a tested experimental Røst backend: token/acoustic networks on
GPU with explicit CPU FFT/excitation. See the report for speed and limitations.
The current Røst Python environment remains separate from experimental runtimes.

### Test sequence

- [x] Check whether the existing chapter render finished, verify its state and
  retain its exact wall time, synthesis time and audio duration as the CPU baseline.
  Preserve the original EPUB, voice reference, rendered chunks and listening data.
- [x] Inspect the actual GPU, driver, available dedicated memory and supported
  compute APIs. Close the isolated Android emulator and idle build workers during
  performance measurements. Record competing host load.
- [x] Create a separate, pinned per-user GPU test environment. Check Python,
  PyTorch, torch-directml, Chatterbox and audio dependency compatibility first.
  Do not replace the working CPU environment or change GPU drivers as a routine step.
- [x] First investigate **Røst 350M through PyTorch/DirectML**: test loading,
  reference conditioning, T3 token generation and the S3Gen waveform stage.
  Timebox the initial compatibility investigation to about an hour. If a required
  operator or dependency blocks it, record the exact failure and next viable step.
- [x] Separately test the existing **Plapre Pico / Plapre 3 ONNX components with
  DirectML**, if their operators and runtime are compatible. This can establish
  whether GPU acceleration is useful even if the Røst PyTorch route fails.
  Plapre 3 keeps its validated FP32 precision initially.
- [ ] Consider Vulkan/llama.cpp for compatible Pico/Nano GGUF models as a later
  alternative. This is a separate token-model integration and does not automatically
  accelerate Røst or the complete waveform pipeline. Do not count it as a successful
  Røst GPU test or quantize Plapre 3 merely to obtain a faster timing.
- [x] Verify that work actually executes on the desktop GPU. Record device/provider
  assignment, available runtime profiling, GPU utilization and memory use. Report
  CPU fallback and transfers explicitly; selecting a provider alone is insufficient.
  Actual GPU work and placement verified; Windows counter percentages were invalid
  and rejected. No trustworthy total utilization percentage is claimed.
- [x] Compare CPU and GPU using the same Danish passages, model, voice and settings:
  short startup sample followed by several minutes of varied text. Include names,
  numbers and longer sentences. Use separate experimental outputs, not the active
  chapter job's caches. Capture cold load and warm generation separately.
- [x] Measure token generation, acoustic decoding/vocoding, watermarking where
  applicable, trimming/encoding and complete end-to-end time. Synchronize completion
  before stopping timers. Report generation time per finished audio hour and a
  new projection for the supplied 22 chapters, with the assumptions stated.
  Stage probes and total synthesis/export timings recorded; watermarking is included
  in full synthesis time, not separately isolated. Book projection is provisional.
- [ ] Check waveform validity, full text coverage, stopping, pronunciation and
  sentence boundaries. A faster but truncated or degraded voice does not pass.
  Use listening review and ASR as complementary checks, not an ASR score alone.
  Automated waveform/coverage/stopping/export checks and offline CPU/GPU ASR spot
  comparisons completed. Human listening/pronunciation acceptance remains pending.

### Utilization and practical limits

Use the GPU's available capacity at normal hardware settings. Aim for the best
correct end-to-end throughput, rather than an artificial 100% utilization reading.
Sequential autoregressive generation, CPU preprocessing, unsupported operators,
memory bandwidth, device transfers and available VRAM can limit utilization.
Test batching or CPU thread choices only where profiling identifies useful work.
Do not change clocks, voltage or power limits to create a favorable benchmark.

The agent can run command-line probes, isolated runtime setup and benchmarks.
The user should not need to operate them manually. If a necessary next step needs
an administrator prompt, a driver change or a restart, explain that concrete step
then. A stock hardware limit or a missing operator is not solved by giving the
agent broader browser access.

GPU acceleration is a desktop experiment for this version. Compatible improvements
can feed later narration exports. The audiobook app update can ship independently
if an RX 5700 XT GPU route is blocked or fails to improve complete generation.

## Audiobook library: choose a folder once

### Main flow

The empty Audiobooks tab has one primary **Choose audiobook folder** action.
The user chooses a dedicated library folder, for example:

```text
Audiobooks/
  Author/
    Book A/
      CD 1/01.mp3
      CD 2/01.mp3
    Book B/book.m4b
  Book C/book.foliobook
```

Folio shows Book A, Book B and Book C as separate books. It retains the selected
folder across restarts, scans new additions and offers **Refresh** and folder
management. Existing individual-file and narrated-bundle imports remain available
from the menu. A normal refresh should not require another permission prompt.

### Access and storage

- [ ] Persist the user-selected Android document-tree permission and root record.
  Use Android's scoped folder picker; explain unavailable roots through that flow.
  Support adding another root without duplicating books in overlapping trees.
- [ ] Index ordinary local audio **in place** rather than copying an entire library
  into app storage. Play through retained content URIs/file descriptors. Keep
  positions, bookmarks, metadata and thumbnails in Folio's own storage.
- [ ] Extend the existing track-source abstraction to support both the current
  private files and linked document sources. Preserve 0.13 imported recordings.
- [ ] Prepare `.foliobook` content through the existing checked extraction path
  when needed; ZIP-contained audio cannot be treated as an ordinary seekable file.
  Show bundles in the library without eagerly extracting every book.
- [ ] Distinguish **Remove from Folio / Disconnect folder** from source deletion.
  Disconnecting must not delete original audio. An unavailable SD card, moved file
  or revoked permission shows a reconnect/missing state and preserves listening data.
- [ ] Check seekability and provider support before playback. Offer an explicit
  local copy when a provider cannot supply usable offline/seekable media; do not
  imply cloud-only content is already downloaded.

### Discovery and grouping

- [ ] Find separate book directories beneath author/series folders. Recognize
  common CD/Disc subfolders as tracks within their parent book, with natural order.
- [ ] Treat standalone M4B books individually. Use album/title metadata and filename
  structure for loose MP3s. Keep ambiguous groups visible and correctable rather
  than silently merging an entire author or library folder into one book.
- [ ] Use embedded metadata and cover images, with folder/file names as fallbacks.
  Keep different recordings of a title distinct; deduplicate exact existing sources.
- [ ] Use one supported-format policy for discovery, file import and playback.
  The 0.13 folder helper currently finds some audio extensions the importer rejects;
  align those policies and report unsupported files without rejecting unrelated books.
- [ ] Use cancellable background indexing, cached metadata, incremental change
  checks and progressive cover loading. Show discovered books promptly. Avoid
  decoding, fully hashing or copying every unchanged audio file on every refresh.
- [ ] Keep stable source/recording identities so rescanning cannot reset progress.
  Handle moved/renamed tracks through verified matching or an explicit relink;
  do not trust a matching filename alone. Missing entries are not silently purged.

## Status filters and library presentation

- [ ] Primary filter chips: **All / Unread / Reading / Read**, with counts and a
  remembered selection. Search title/author within the selected status. Keep
  Audio only / Read + listen as secondary type options.
- [ ] **Unread:** no presented listening progress. **Reading:** started, unfinished.
  **Read:** actual completion of the final track, or an explicit manual mark-read.
  Opening a player, buffering, or approaching 99% must not mark a book read.
- [ ] Add **Mark read**, **Mark unread** and **Restart** actions with clear behavior.
  Resetting to unread/restarting affects the listening cursor, keeps bookmarks and
  does not reset the EPUB's reading position. Manual status survives rescanning.
- [ ] Show cover, title, author, progress, duration/remaining time and Resume/Listen.
  Remember sort choices such as last played, title and author. Keep previews labelled.
- [ ] Keep Books and Audiobooks as the bottom destinations; hybrids remain linked
  in both. Reading and listening status are separate; opening a recording alone
  must not declare the linked EPUB read.
- [ ] Migrate 0.13 audio records without changing their IDs, track order, saved
  positions, speeds, sleep behavior or bookmarks. Derive initial status from
  existing reliable cursor data; do not infer completion merely from lastPlayed.

## Verification and release gate

- [ ] Test a root containing several books, author nesting, CD folders, loose M4B,
  mixed supported/unsupported files, a narrated bundle and overlapping roots.
- [ ] Test first scan, unchanged refresh, new book, rename, missing card, revoked
  access, reconnect, reboot and interrupted scan. No duplicate cards or lost marks.
- [ ] Exercise every status, manual overrides, filters/search, actual final-track
  completion at different speeds, and reopening a completed book.
- [ ] Verify linked-source seeking, paused resume, track transitions, screen-off
  playback, headphone disconnect, process recovery and TTS/recorded interlocks.
- [ ] Measure responsiveness on a large synthetic library without real user audio
  in test assets. Report emulator versus physical measurements accurately.
- [ ] Run relevant existing reader/café/voice/update regressions, build/lint and
  the public in-app 0.13 -> 0.13.1 upgrade against populated test data before
  calling release verification complete. Keep the existing signing certificate.

## Next-session order

1. Check chapter 1 and record CPU baseline; begin bounded desktop GPU compatibility
   probes and retain their evidence.
2. Implement persistent root folders, source playback and book discovery/grouping.
3. Add status persistence/filters, progress cards and migration.
4. Run acceptance and performance checks; summarize GPU results separately.
5. Build and publish 0.13.1 with release notes after the applicable gates pass.

No GPU runtime, driver change, APK implementation or new scheduled automation was
started while writing this plan.

## Primary implementation references

- [AMD Windows ROCm hardware support](https://rocm.docs.amd.com/projects/radeon-ryzen/en/latest/docs/compatibility/compatibilityrad/windows/windows_compatibility.html)
- [PyTorch with DirectML on Windows](https://learn.microsoft.com/en-us/windows/ai/directml/pytorch-windows)
- [llama.cpp build and GPU backends](https://github.com/ggml-org/llama.cpp/blob/master/docs/build.md)
- [Android document-tree access and persisted permissions](https://developer.android.com/training/data-storage/shared/documents-files)
