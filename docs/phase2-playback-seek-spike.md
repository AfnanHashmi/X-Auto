# Phase 2 — Live Playback + DVR Seek Spike

Verified 2026-09-17 ~11:06–11:08 local device time (device: Galaxy S25-family, `SM-S938B`,
physical device over USB/adb, debug build).

## What was built

Minimal spike per the plan — no custom buffering/seek code:

- `data/stream/PlaylistRepository` — interface; `ConfigPlaylistRepository` reads a manually-minted
  signed `q1080.m3u8` URL from `BuildConfig.STREAM_URL` (sourced from gitignored
  `local.properties`, ~12h TTL). Real login/access-session flow is deferred to Phase 5/6 per the
  Phase 0 findings note.
- `di/PlayerModule` — `OkHttpClient` with an interceptor adding the required
  `x-app-platform: Streamflix-App` header, wired into `OkHttpDataSource.Factory` →
  `HlsMediaSource.Factory`.
- `ui/cut/CutViewModel` — owns a plain `ExoPlayer` built with that `MediaSource.Factory`, loads
  the playlist URL, `prepare()` + `playWhenReady = true`. Released in `onCleared()`.
- `CutScreen` — `PlayerView` via `AndroidView`, using its **default controller** — no custom
  scrubber. This is throwaway for Phase 2; Phase 4 replaces it with the real cut UI.

## Results — all pass

| Check | Result |
|---|---|
| Player reports a seekable window matching DVR depth | **Pass** — seek bar showed a live-growing duration (38:59 → 40:21 across ~2 min of testing), matching the stream's actual elapsed runtime, not a fixed/short buffer. |
| Seek backward lands correctly and resumes playback | **Pass** — tapped seek bar at ~27% and ~14% across; content visibly jumped to a different scene each time (concert clip → garden/gym scene) and continued playing forward from the new position (confirmed via position readout: 39:24 → seek → 23:50, still incrementing). |
| Jump-to-live after scrubbing back | **Pass** — tapping the far right of the seek bar returned playback to the live edge (position tracked duration exactly: 40:17/40:21, thumb at far right). |
| LL-HLS tag tolerance (`EXT-X-PART`, `EXT-X-PRELOAD-HINT`, `EXT-X-RENDITION-REPORT`) | **Pass** — zero ExoPlayer/HLS parser warnings or exceptions in logcat across the whole session. Closes the open concern from the Phase 0 note. |
| No crashes | **Pass** — clean logcat, no `FATAL EXCEPTION`/`AndroidRuntime` entries. |

Not yet measured: exact playlist-refetch byte counts (plan's "watch actual bytes" check) — visual/
functional verification took priority for this spike. Given the Phase 0 finding that segments and
init files hit CDN cache on repeat and playlists stay in the tens-of-KB range at this stream
duration, this is deprioritized rather than skipped — worth a quick Android Studio Network
Profiler pass before Phase 4 if actual data usage numbers become important.

## Outcome

**Gate A is now also confirmed at the client/player level**, not just via server config: ExoPlayer
plus a plain `HlsMediaSource` genuinely seeks within the live DVR window on real hardware, with no
custom buffering or seek-window code. The hard part of the app, per the plan, is done.

Proceed to Phase 3 (export spike — Gate B) next.
