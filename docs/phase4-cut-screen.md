# Phase 4 — Cut Screen

Replaces the Phase 2/3 spike UI with the real cut screen: video surface, a DVR-window-spanning
timeline scrubber with draggable in/out handles, frame-step controls, and an export button with
live progress and cancel. Verified on physical device against the real live stream.

## What was built

- `CutUiState` / `CutViewModel` — in/out points are tracked as **wall-clock epoch ms** internally
  (not player-timeline positions), and projected onto the player's current `0..durationMs` window
  for rendering. This is deliberate: the player's `0..duration` timeline is relative to whatever
  segments are *currently* buffered, and that window slides forward continuously as the live DVR
  evicts old segments. Storing in/out as raw timeline ms would silently drift out from under the
  user as they scrub; storing as epoch ms keeps a selection pinned to the same real moment
  regardless of how long the window has been open.
- `TimelineScrubber` — custom Compose scrubber (`Canvas` + drag handles), spanning whatever
  `durationMs` the player currently reports — never a hardcoded 5/10-minute window, per the plan.
- Frame-step (`|<` / `>|`) at ~33ms per tap (1 frame at the stream's 30fps) — an approximation via
  `player.seekTo`, not decoder-exact single-frame stepping.
- Export button wired to `ClipExporter` (Phase 3), now with live progress text and a Cancel button.

## Three real bugs found and fixed during device testing

1. **Handles unreachable near the live edge.** The default selection is the last ~15s, which sits
   at the timeline's right edge — and a real drag starting there triggered the OS's edge
   back-gesture, kicking the user out of the app before the touch ever reached Compose. Fixed with
   extra horizontal inset on the scrubber beyond the screen's own padding.

2. **In/out handles ambiguous when nearly coincident.** With a short selection against up to a 2h
   window, in/out often land within a few px of each other — genuinely ambiguous which handle a
   touch should grab. Fixed by giving each handle its own vertical lane (in above, out below)
   instead of sharing the track's center line, so hit-testing is unambiguous regardless of how
   close in/out are in time. (What first looked like a stale-closure drag bug during debugging
   turned out to be correct clamping behavior on a large test drag against a tiny selection window
   — see git history on `TimelineScrubber.kt`/`CutViewModel.kt` for the trail; the `rememberUpdatedState`
   fix for the pointerInput-closure-staleness issue was kept since it's the documented-correct
   pattern regardless.)

3. **Drag felt laggy.** `dragInPoint`/`dragOutPoint` updated the ViewModel's internal epoch state
   immediately, but the UI-facing `StateFlow` only picked it up on the next 200ms position-poll
   tick — so the handle visibly lagged behind a finger by up to 200ms. Fixed by pushing the in/out
   projection into `_uiState` synchronously on every drag event, not just the poll tick.

## Export UX gap found live: no cancel, no real progress

First on-device export test selected roughly a 10-minute range (not the few-second clips this is
built for). The two-pass transmux genuinely took several minutes for that — not hung, just slow —
but with only a boolean spinner and no cancel button, it was indistinguishable from stuck. Fixed:
`ClipExporter.export()` now takes an `onProgress: (String) -> Unit` callback, polling
`Transformer.getProgress()` during each pass ("Preparing (pass 1/2): 42%" / "Trimming (pass 2/2):
87%"), and `CutViewModel` tracks the export `Job` so `cancelExport()` can actually cancel it
(propagates through to `transformer.cancel()` via structured concurrency).

One subtlety worth documenting: `Media3ClipExporter.export()`'s top-level `runCatching` absorbs
`CancellationException` into `Result.failure` rather than letting it propagate, which would let
the coroutine run to completion and overwrite `CutViewModel`'s "Export cancelled" message with a
stale "Export failed" one. Guarded with an explicit `ensureActive()` check before the final state
update in `CutViewModel.export()`.

## Verified end-to-end on device

- Short clip (~48s): exported in ~5 seconds, valid MP4 (`ftyp isom` + `moov` header confirmed via
  pulled file), 53.5MB, `ExportResult.durationMs=47766` matching the request.
- ~4-minute clip: exported in ~26 seconds.
- ~10-minute clip: exported in several minutes -- slow but not broken; now has visible progress
  and a working cancel.

## Known gaps carried forward

- **Video-only clips** (from Phase 3) — audio is a separate LLHLS rendition, not yet fetched/muxed.
- **Frame-step is approximate** (~33ms seeks), not decoder-exact single-frame stepping.
- **Large selections are genuinely slow** (roughly real-time or slower for the two-pass transmux)
  -- acceptable now that progress/cancel exist, but worth profiling if the product ever wants to
  support longer clips than the "what just happened" use case implies.

Proceed to Phase 5 (Caption screen) next.
