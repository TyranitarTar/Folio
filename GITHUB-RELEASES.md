# Folio GitHub releases

Repository: https://github.com/TyranitarTar/Folio

The app checks `/repos/TyranitarTar/Folio/releases/latest` only when Check for updates is tapped. It accepts stable numeric tags (for example v0.7.1), a matching `Folio-0.7.1.apk` asset and its GitHub-provided SHA-256 digest. Downloads use Android DownloadManager and can continue in the background. Verification checks size, digest, application ID, newer Android versionCode, exact versionName and the installed signing certificate. A verified private copy is passed to Android's installer after user confirmation. No GitHub token is stored in the app. The repository and releases must be public for unauthenticated checks.

For each release, build with the original local signing key, increase both versionCode and versionName, update ReleaseHistory.kt, run tests, then publish a stable release with the signed APK and corresponding application/native source archives. GitHub computes the asset digest. Upload the checksum and installation notes too. Keep books, user data, `signing/`, keystores and passwords out of the repository and release assets.

The included CI workflow runs JVM tests, lint and a debug build. Debug APKs have a separate application ID and cannot update a signed release installation. Signing remains local. CI does not contain signing secrets or create a replacement signing identity. The first v0.7.1 installation is manual; later versions can be offered from inside Folio.

## Optional voice data (0.9+)

`voice-packs-v1` hosts two data-only model archives, 22 small authentic M4A voice/language samples, provenance, licenses and checksums. It is a prerelease with `--latest=false`; infrastructure tags must never become `/releases/latest` because the updater expects numeric app versions. Publish and validate immutable pack/sample URLs before a dependent APK. Changes to models require new versioned directories and archives, updated pinned digests, and checkpoint/cache compatibility review. Do not replace an already referenced asset in place. Model metadata in the signed APK is authoritative.

Use `build_voice_packs.py` and `validate_voice_packs.py`. Generate previews with the exact native models, an original phrase and 1× settings using opt-in VoiceSampleGenerationInstrumentedTest. The release APK excludes model data even when a development debug build uses `-PfolioBundledVoices=true`. App source remains curated, private signing remains local, and native corresponding source still accompanies releases.
