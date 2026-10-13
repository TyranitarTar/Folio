# Folio 0.6 implementation checklist

Scope: implement the 0.5 adversarial audit, preserve installed book data and both local voice engines, deliver a signed APK, then check usage and run a second evaluation/fix loop.

- [x] Speech clock, checkpoint ownership, model/cache integrity and bounded buffering
- [x] Recoverable transactional library migration and incremental progress/annotations
- [x] Backup/restore, targeted state repair and coordinated book deletion
- [x] Linear compact/shared text indexing and reliable repeated-text anchors
- [x] Lazy guided preparation, visible dwell timing, batched checkpoints, requested presets
- [x] Lazy renderer, incremental pagination, geometry-only identity and generation guards
- [x] Harden imports/resources and incoming requests
- [x] Draft/navigation restoration, shelves/filters/adaptive display and storage controls
- [x] Unit/device verification, upgrade preservation, release packaging
- [x] Usage-limit check and fresh adversarial evaluation/fix loop

Physical Galaxy S10, Bluetooth acoustic latency and thermal/battery validation require that hardware; emulator results will be identified accurately.

Final validation: 74 JVM tests and 20 Android API-35 tests passed; lint has 0 errors (24 documented warnings). Signed v0.5 upgrade preserved exact book/annotation/listening state. Both model packs and all 12 native binaries are retained. The second evaluation/fix loop and account usage checks are complete.
