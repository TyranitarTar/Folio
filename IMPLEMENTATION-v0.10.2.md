# Folio 0.10.2: Pico throughput and voice storage

Pico can synthesize upcoming passages while the current passage plays. The persistent
AudioTrack FIFO already orders those clips. Once playback consumes that buffer faster
than inference replaces it, another wait is unavoidable. Playback at 1.5× requires an
effective synthesis real-time factor below 0.67; 2× requires below 0.5. Padding removal
and queueing alone cannot establish those sustained rates.

## Thread selection and measurement

Read aloud / Speech settings offers automatic, 1, 2, 4, 6 and 8 Pico inference threads.
Automatic retains the previous cap of four until measurements justify another default.
Explicit choices are saved and included in backups and source-audio cache identity.
Changing them safely repeats the current passage; playback-speed changes keep the
waveform and sample offset. Existing automatic-mode 0.10.1 audio bookmarks remain
compatible. The voice pack and download checksums are unchanged.

The foreground Pico performance test performs warm measurements at each thread count
in two reversed orders. Its original fixed Danish sentence avoids personal book text.
The native bridge separately times prompt prefill, autoregressive decoding, sampling,
Kanade and HiFT. Reports include wall time, generated audio duration, real-time factor,
aggregate process CPU time, process PSS, battery temperature and Android thermal state.
Process CPU/wall is an approximate active-core measure including other app work and
runtime spinning; it is not exclusive inference utilization. A thread limit is not a
promise that every core contributes useful work.

The test also compares 20, 30 and 35 words, ending on sentence boundaries, with separate
sentence requests. The native 20-second / natural-EOS guard remains enforced. Production
passage grouping is kept conservative until the measurements and listening checks
support a change. Benchmark admission is serialized and checked again in the speech
service. Closing the benchmark, leaving the app or cancelling interrupts generation;
the screen stays awake while the test is visible. No best setting is silently applied.

### S10 measurement, 18 October 2026

The connected SM-G975F (Exynos 9820, Android 12) completed the warm comparison.
Power saving remained on throughout; USB power was connected. These are two trials
per count for one fixed phrase, not a Flip7 result or a battery-life test.

| Threads | Mean generation | Mean real-time factor | Mean process CPU / wall |
| --- | --- | --- | --- |
| 1 | 10.41 s | 2.65 | 1.01 cores |
| 2 | 7.31 s | 1.86 | 2.01 cores |
| 4 | 8.14 s | 2.07 | 4.01 cores |
| 6 | 9.90 s | 2.52 | 5.90 cores |
| 8 | 16.04 s | 4.08 | 6.59 cores |

Two threads were about 10% faster than four and used substantially less process CPU.
Eight were about 2.2 times slower than two. Two threads are a useful explicit S10
choice under these conditions. The automatic cap stays at four because Flip7 has not
been measured and changing it globally from one phone would be premature.

At two threads, HiFT accounted for about 3.47 s and autoregressive decode 2.82 s;
prefill was 0.35 s, Kanade 0.65 s and sampling 0.009 s. Optimizing token sampling
would have very little impact on this workload. The final observed process PSS was
about 1.11 GiB, battery temperature 32.4°C and thermal status 0. This records a point
in time, not peak memory, energy consumption or a long-session thermal guarantee.

One pair per grouping size at four threads gave separate/grouped generation times:
20 words, 15.60 / 12.39 s; 30 words, 22.45 / 22.67 s; 35 words, 35.74 / 32.12 s.
Generated durations also differed, so lower request time does not directly imply
the same improvement in sustained narration. The 20-word example improved about
21% in request time; the longer examples were inconsistent. Production chunking
therefore stays unchanged. Human group-pronunciation acceptance is not claimed.

## Prepare audio ahead

For Pico, Prepare chapter ahead starts at the saved listening cursor, pauses output
and generates into the existing disk audio cache. Live lookahead and preparation share
the exact source-boundary function, so Resume can reuse the clips. The scan cursor is
local to preparation and never advances the listening cursor. The operation reports
passages and minutes ready, releases the engine afterward and requires explicit Resume.
Pause, Stop, navigation or a new listening request cancels it. Resume during preparation
cancels remaining work and uses the audio already available.

Preparation is bounded to roughly 48 MiB, about eight minutes of 24 kHz float PCM, with
headroom for the last clip. It retains only one generated clip at a time and does not
hold a whole chapter in RAM. Long chapters can reach the limit; the UI says so. Prepared
audio uses an evictable cache, not a permanent audiobook export. It can bridge slower
inference for a session, but does not make inference itself faster.

## Storage and follow-up

See [storage implementation](IMPLEMENTATION-v0.10.2-storage.md). Current catalog models,
loaded engines, shared components, rollback and unknown folders are protected. The
same leases also prevent installation from replacing a model held by a standalone
benchmark. Cleanup confirmation freezes the reviewed set of files, then rechecks it
after cancellation, native work and open download streams have drained.

Galaxy Flip7 is the priority for future heavier Danish models. The user accepts Pico's
remaining S10 limitations. Flip7 sustained 1× / 1.5× / 2× throughput, long-session heat,
unplugged battery use, Bluetooth routes and human Danish listening checks remain device
acceptance tasks unless explicitly measured in the release evidence. Plapre 3, Røst v3
and the reading cat game remain future work.

Validation evidence is recorded separately in the release validation JSON and review.
Eight distinct physical S10 checks cover native Pico, threads/groups, cold generation
overlap, prepared-cache resumption and filesystem cleanup. Five emulator checks cover
Kokoro, Supertonic, updater transitions, samples and storage controls. The phone's
secure lock blocked its storage UI case; that case was verified on the unlocked
emulator instead. The Windows symlink unit case skipped, and physical Android
exercised the linked-path rejection. The benchmark UI also cancelled on backgrounding.

The existing full public 0.9.0 → 0.10.1 update was exercised on the populated emulator:
the app downloaded and verified the APK, Android installed it, and exact library rows,
annotation, saved reading state and existing speech/appearance preferences survived.
This is recorded in `audit/v0101-real-upgrade-result.json`; it does not by itself prove
the subsequent 0.10.2 installation.
