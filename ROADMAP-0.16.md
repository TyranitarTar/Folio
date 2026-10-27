# Folio 0.16 — audiobook player and library follow-up

27 October 2026. Authorized implementation, following Android 0.15.0. Continue
the active Studio 0.5.1 default-recognition update as well. Studio-specific
research, cancelled voice-model research and Cat Café redesign stay deferred.

- [x] Remove Library display and Shelves from More; keep scoped library controls.
- [x] Remove Open sample book. Remove duplicate audiobook Library settings from
  overflow; retain the toolbar control. Add Bluetooth/headset playback settings.
- [x] eBooks/Audiobooks naming, shelf selectors/Manage shelves and full-width
  menu rows must agree. Remove per-title New badges. Limit/migrate grids to 2–4
  fixed columns, align them at the top and reduce toolbar-to-status-tab spacing.
- [x] Mark FolioBooks unobtrusively in grid and list layouts.
- [x] Redesign the listening page: read-only book progress at the top, elapsed /
  duration plus remaining listening time at current speed, prominent Play/Pause,
  four deliberate −60 / −10 / +10 / +60 second controls, speed and Stop.
- [x] Keep current spoken passage. Avoid duplicate title/filename. Make chapter
  progress compact and lower on the page; guard deliberate seek from accidental
  taps. Chapter/bookmark lists stay hidden until requested. History is a recovery
  action in overflow, not a permanent tab. Sleep/bookmark tools are secondary.
- [x] Larger library list play controls. Long-press actions are full-width rows:
  Mark new / Mark reading / Mark finished, open/read, relevant source actions.
- [x] Delete media from device as the main removal action with explicit target
  confirmation. Enforce provider permissions and ownership; refuse ambiguous
  shared/root/series-folder deletion. Keep progress/catalog until deletion succeeds.
- [x] Playback settings: headset previous/next and play/pause, configurable
  transport, pause for transient notification focus, stop/pause for competing
  media, resume after transient call focus only if Folio was playing and the user
  has not paused/stopped/disconnected. No notification-listener/phone-log access.
- [x] Synchronize FolioBook reading and listening through actual matched cues;
  distinguish text percentage from audio-time percentage. Do not overwrite a
  manually browsed reading location unexpectedly or invent links in unaligned gaps.
- [ ] Continue A22/A23 source grouping/artwork investigation using representative
  local fixtures. Revisit page-count performance measurements and target-phone
  playback/upgrade acceptance where devices and evidence permit.
- [x] Tests: seek safety and speed-adjusted remaining time, exact skip boundaries,
  focus interruption state, source deletion safety, linked cursor sync, library
  layouts/menu targets, regression tests, lint, release build and emulator checks.

The supplied Smart AudioBook Player APK is a behaviour/layout reference. Folio
uses its own code; reference disassemblies/assets are not shipped. Device/provider
and actual Bluetooth/call tests require physical acceptance beyond emulator tests.

## Qualification completed

205 JVM tests passed with one existing platform skip; lint has no errors.
All 18 final-build emulator cases passed, including generated Pico audio, M4B
chapters/artwork, root rescans, independent page-count references, focus recovery,
protected player navigation, linked reading cursors and library menu/grid targets.
The signed 0.15.0 to 0.16.0 in-place upgrade retained all 14 checked durable files.

A22/A23 have explicit split correction and improved artwork selection, with
regression fixtures. The actual M1/M2/M3 phone files and missing covers still
need source/device acceptance. Small-fixture page-cache reuse was reverified;
exact uncached totals still need layout. Physical Bluetooth/call and large-library
performance checks remain open. Studio 0.5.1 and its selected recognition default
are delivered separately; next Studio queue/removal work is saved for later.
