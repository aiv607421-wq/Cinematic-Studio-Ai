# Cinematic Studio AI

A native Android, offline-first video editor. This repository now contains a
**buildable Phase 1 application**: you can create a project, import media, edit
on a multitrack timeline, undo/redo, autosave/recover, and export a real
rendered MP4.

Built from the *Cinematic Studio AI — Master Development Prompt & Feature
Implementation Roadmap*.

---

## Build status

| Check | Result |
| --- | --- |
| `gradle :app:assembleDebug` | ✅ passes — produces `app/build/outputs/apk/debug/app-debug.apk` (~23 MB) |
| `gradle :app:testDebugUnitTest` | ✅ passes — 26 tests (timeline ops, undo/redo, project file format, timecode) |
| Install / on-device playback & export | ⏳ **not verified here** — this environment has no Android device or emulator |

Nothing in this README claims a feature works that has not been compiled and
unit-tested. Device-level verification is tracked as an open item (see
*Known limitations*).

## Requirements

* JDK 17
* Android SDK with **Platform 36** and **Build-Tools 36.0.0** (`compileSdk = 36`, `minSdk = 23`)
* Gradle 8.13 (wrapper scripts are included; the wrapper JAR is **not** committed —
  see *Known limitations* #8, or use an installed Gradle 8.13)

## Build the app

```bash
# from the repository root
./gradlew :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:installDebug           # install on a connected device/emulator
```

If your SDK lives somewhere non-standard, set it with `sdk.dir` in
`local.properties` (git-ignored) or the `ANDROID_HOME` environment variable.

### Release builds

Release builds are minified but intentionally **unsigned** — no keystore or
signing credentials are committed to this repository. To sign:

```bash
./gradlew :app:assembleRelease
apksigner sign --ks your.keystore app/build/outputs/apk/release/app-release-unsigned.apk
```

## Toolchain (pinned and verified against Google's Maven repo)

| Component | Version |
| --- | --- |
| Android Gradle Plugin | 8.13.2 |
| Gradle | 8.13 |
| Kotlin | 2.2.21 |
| Compose BOM | 2025.12.01 |
| AndroidX Media3 (all modules) | 1.11.1 |
| lifecycle (viewmodel/runtime-compose) | 2.9.4 |

All Media3 artifacts intentionally share one version.

---

## What is implemented (Phase 1 — Foundation)

**Home screen & projects**
- Create project (name, aspect ratio 16:9 / 9:16 / 1:1 / 4:3 / 21:9, frame rate 24/25/30/60)
- Open, search, rename, duplicate, delete (with confirmation)
- Thumbnails, clip counts, durations, last-edited time, missing-media badges
- Atomic saves (`temp file → rename`), autosave after every committed edit,
  crash recovery that prefers the newest autosave snapshot

**Media import & organization**
- Storage Access Framework picker (video / image / audio) — **no storage
  permission is requested at all**; files are never copied or deleted
- Metadata probe: duration, resolution, file size, display name
- Duplicate detection by URI, missing-media detection, tap-to-relink
- Media browser grid with thumbnails

**Multitrack timeline**
- Video, audio and text tracks with mute / hide / lock per track
- Tap to select, long-press-drag to move (with snapping to clip edges and the
  playhead), vertical drag to another compatible track
- Edge-handle trimming that respects source in/out bounds and neighbours
- Split at playhead, ripple delete, duplicate, add track
- Pinch zoom, ruler scrubbing, playhead overlay, frame-step transport

**History & recovery**
- Snapshot undo/redo (bounded stack), autosave checkpoints, explicit save

**Preview**
- Media3 `CompositionPlayer` renders the *same* `Composition` used for export,
  so preview and output agree — including timeline gaps (black video / silence)

**Export (real render, not a mock)**
- Media3 `Transformer` → MP4 / H.264 / AAC, written to
  `Android/data/.../files/Movies/Cinematic Studio AI/`
- Genuine progress polling (`Transformer.getProgress`), cancellation, error
  messages derived from the actual `ExportException`
- Optional "save to gallery" via MediaStore (Android 10+; no permission needed)
- Gaps between clips are preserved via `addGap()` and the dialog tells you how
  many exist before you start

---

## Architecture

```
app/src/main/java/com/cinematicstudio/ai/
├── data/
│   ├── model/Project.kt        immutable project/track/clip model (schema v1)
│   ├── ProjectJson.kt          versioned JSON (org.json), migration guard
│   ├── ProjectRepository.kt    atomic save, autosave, recovery, relink checks
│   ├── MediaLibrary.kt         SAF import + MediaMetadataRetriever probing
│   └── MediaStorePublisher.kt  gallery publication (MediaStore, Android 10+)
├── engine/                     pure, unit-tested logic
│   ├── TimelineOps.kt          split / trim / move / delete / duplicate / snap
│   ├── History.kt              snapshot undo & redo
│   ├── ExportPlanner.kt        project → Media3 Composition (shared by preview & export)
│   ├── ExportRenderer.kt       Transformer wrapper: progress, cancel, errors
│   ├── PreviewController.kt    CompositionPlayer wrapper
│   └── Timecode.kt             timecode & label formatting
├── viewmodel/                  HomeViewModel, EditorViewModel (session state)
└── ui/                         Compose: theme, home, editor, timeline, export
```

Design rules followed from the spec: edits are non-destructive metadata only,
every mutation produces a new immutable project value (so history can never
half-apply), and the preview and the exporter consume one shared mapping
(`ExportPlanner`) so what you see is what renders.

## Dependencies & licences

* **AndroidX Media3** (Apache-2.0) — playback, `Transformer` rendering pipeline
* **Jetpack Compose / Material 3** (Apache-2.0)
* **kotlinx-coroutines** (Apache-2.0)
* **JUnit 4** and `org.json` (test-only)

No API keys, signing material or other secrets are stored in this repository or
embedded in the APK. The app itself declares no permissions — editing is local.
Media3 contributes `ACCESS_NETWORK_STATE` and `WAKE_LOCK` through manifest
merging; **no `INTERNET` permission is present**, so the app cannot talk to the
network.

## Known limitations (honest status)

1. **Not yet verified on a physical device.** Compilation and 26 unit tests
   pass here; playback, export rendering and touch interaction still need a
   device or emulator run.
2. **Single video render track.** Extra video tracks exist in the timeline
   model and are editable, but only the first video track is composited into
   the export (overlays/PiP arrive with Phase 2 compositing).
3. **Text tracks are structural only** — clips can live on a text track, but
   glyph rasterisation onto the picture is Phase 2 (Media3 `BitmapOverlay`).
4. **Export output resolution follows the source clips.** Media3 1.11.1 exposes
   no resolution/bitrate controls, so the export dialog reports the real output
   size instead of offering settings the pipeline would ignore.
5. **Mixed-resolution sources** are handed to Media3 as-is; normalising
   differing resolutions is not yet implemented.
6. **No `androidTest` / instrumentation tests** yet — Phase 7 scope.
7. Release APK is unsigned (no keystores in the repo).
8. `gradle/wrapper/gradle-wrapper.jar` is a binary that is not committed; run
   `gradle wrapper --gradle-version 8.13` once after cloning to materialise it,
   or use an installed Gradle 8.13 directly.

## Roadmap

Phases 2–7 (core editing, color/effects, advanced audio/motion, AI features,
optimization, release) are described in the roadmap document and are **planned,
not implemented**. Every phase must leave the project buildable.

## Roadmap source

Based on the supplied *Cinematic Studio AI — Master Development Prompt & Feature
Implementation Roadmap*.
