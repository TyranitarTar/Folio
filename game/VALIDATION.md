# Folio 0.18.0 · local validation, 15 November 2026

The signed APK is output/Folio-0.18.0.apk, versionCode 27, SHA-256
`c3e8390e84c50521f9672b40c304221fa0388a87297140b86b2f735d7ed668d3`.
It uses the existing Folio release signing certificate. This build is prepared for the requested v0.18.0 GitHub release.

- Both laptop ZIP copies match their OneDrive sources by SHA-256; see
  transfer-checksums.json. All 234 extracted implementation manifest entries
  verify. The 27 staged baseline runtime files remain unchanged.
- Supplied asset verification passes: 289 native frames, 34 clips, 277 retained
  pre-review frames and 29 retained clips unchanged, protected runs/sprints,
  frozen master/palette/alpha, exact tile joins and 1.30 display scale.
- Supplied engine simulation passes: 21 reachable activities, 441 ordered
  transitions, 625 jumps, five speeds, no walk collisions, safe queuing/cancel,
  elevated naps/waking, feeding approaches and dedicated tunnel art.
- Supplied actual-renderer layer tests pass for all four feeding approaches,
  both tower levels, elevated naps, window landing and tunnel conceal/reveal.
  The supplied standalone controller's DOM/Canvas checks also pass.
- game/check_cafe.cjs passes against the actual shipped engine, director and
  host with native Canvas: all four repeating routines, random selection,
  queued-command preservation, safe stop, bounded history, DPR sizing,
  validated native-save input, pause, background/visibility/resume, platform
  transitions, feeding, tunnel and complete listener/RAF/canvas cleanup.
- JVM: **227 tests, zero failures/errors, one platform skip** (226 passes),
  including new room preference/resource-boundary tests and existing café
  wallet/reward/storage tests. Debug lint: **zero errors**. Signed release,
  debug and instrumentation builds succeed.
- Actual Android WebView: **four emulator tests pass**. They check painted room
  pixels and loading-overlay removal, shelf naps/waking/descent/tunnel, all-action
  menu access, routine/pace controls, durable discoveries, pause/resume,
  backgrounding, three repeated navigation/remount cycles, recreation and
  landscape controls. Existing earned café export remains unchanged throughout.
- Legacy café UI: **two emulator regressions pass**. Test-mode purchases/replays,
  story choices/pause/finish/reset remain isolated; normal chapter recreation
  retains book state and wallet. The helper now enters via room settings.
- Signed emulator upgrade from the local 0.19.0 build to 0.18.0 preserves all four selected
  existing durable files byte-for-byte at installation, including café SQLite,
  library SQLite, speech state and speech preferences. No clear/uninstall.
  The upgraded release opens the rendered room. This check compares files
  before first launch, not every possible later app migration or user action.
- Inspected actual emulator screenshots of the release room, bookshelf nap,
  tunnel exit, action menu and landscape. The integration fixes WebView's
  zero-height CSS viewport under Compose by explicitly assigning MATCH_PARENT
  layout parameters. The tests now reject a blank/one-pixel room canvas.

Evidence stays in android-reader/audit/: v019-final-checks.log,
v019-final-test-build.log, v019-host-check.log, v019-device-final.log,
v019-legacy-device.log, v019-validation.json and v019-upgrade/result.json.
The screenshot folder is audit/v019/; the installed signed-release preview is
audit/v019-upgrade/release-cafe.png. validate_release_v018.py checks APK identity,
signature, version, bundled asset hashes, source content and recorded results.

No physical Galaxy S10 was available. Device battery, thermals, sustained frame
rate and user acceptance of motion/art remain open. Landscape uses the existing
vertical app chrome and a smaller fitted room; lower controls scroll. Existing
outline/marking shimmer and approximate collision/occlusion volumes remain.
The new room's counts/preferences use their own namespace and are not included
in the legacy café JSON backup. No new stories, furniture editor or economy is
claimed.
