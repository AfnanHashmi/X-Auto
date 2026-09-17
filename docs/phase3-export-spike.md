# Phase 3 — Export Spike (Gate B)

Verified 2026-09-17 ~23:29–23:33 local device time, physical device (Galaxy S25-family), against
a real ~40-min-old live segment of the actual stream.

## What was built

- `data/stream/HlsPlaylistParser` / `SegmentSelector` — pure, unit-tested (see
  `app/src/test/java/com/clipcut/data/stream/`): parse a media playlist into full segments with
  wall-clock `#EXT-X-PROGRAM-DATE-TIME`, and map an arbitrary `[startEpochMs, endEpochMs)` range
  to the contiguous run of segments that covers it (`null` if the range has aged out or isn't
  buffered yet — the plan's required expiry handling).
- `data/stream/SegmentFetcher` — follows master → variant redirection (handles both root-relative
  and file-relative variant URIs, seen as different shapes from `auto` vs `q1080` master
  playlists), then downloads the init segment + selected segments, concatenated byte-for-byte into
  one local file.
- `domain/Media3ClipExporter` — implements the `ClipExporter` interface stubbed in Phase 1, using
  Media3 Transformer. See "The bug and the fix" below for why this is a **two-pass** transmux.
- A temporary "Test export (10s)" button on the Cut screen (Phase 2's spike UI) triggers the whole
  pipeline against a known-safe recent range and reports the result. Removed once Phase 4 wires a
  real in/out selection.

## The bug and the fix

First attempt: concatenate init + N full segments into one file, feed it to Transformer with
`ClippingConfiguration(startMs, endMs)` directly. **Result: `onCompleted` fired, but the output
was 0 bytes / `durationMs=0` / `videoFrameCount=0`,** every time, regardless of what `startMs`/
`endMs` were (including the trivially-valid `0..10000` against a 12s source).

Isolated by first testing **transmux with no clipping at all** on the same raw file: that produced
a perfectly valid ~12s / 360-frame / ~6MB MP4. So the container itself is fine for Transformer —
**Gate B's actual question is answered: yes, this fMP4/CMAF source transmuxes cleanly.** The bug is
specific to `ClippingConfiguration` against a raw concatenation of live segments, which has no seek
index (no `sidx`, just sequential `moof`/`mdat` pairs) — Transformer's clip path appears to need
that to find where to start/stop cutting, and fails silently (empty output, no error) without it.

**Fix: two Transformer passes.**
1. Transmux the raw concatenated segments to a normal, properly-indexed MP4 (no clipping).
2. Run `ClippingConfiguration` against *that* file instead of the raw one.

Result: pass 1 `durationMs=11966` (~12s, matches 2×6s segments), pass 2 `durationMs=9966` against
a requested 10,000ms clip — accurate to within one frame, which is expected since transmux-only
clipping cuts at the nearest available frame rather than re-encoding to a precise boundary.

Output file confirmed as a standard, valid MP4 (`ftyp isom` + `moov` at the front, not fragmented)
via a hex check of the pulled file, `mvhd` duration/timescale consistent with the ~10s result.

## Result

**Gate B: confirmed, with a caveat.** Media3 Transformer trims and transmuxes these
OvenMediaEngine-produced fMP4 segments without re-encoding and without touching FFmpeg — matching
the plan's core bet. The caveat is the two-pass requirement discovered above; `ClipExporter`
already implements it, so this is closed as an implementation detail, not an open risk.

## Known gap: audio

The exported clip is **video-only**. OvenMediaEngine serves audio as a separate LLHLS rendition
(`#EXT-X-MEDIA` pointing at its own `chunklist_N_audio_..._llhls.m3u8`), which `SegmentFetcher`
doesn't fetch yet -- `ClipExporter` only pulls the video chunklist. Muxing the two together needs a
Transformer `Composition` with two parallel `EditedMediaItemSequence`s (one video-only, one
audio-only, same clip range) rather than the current single-file transmux. Not attempted in this
spike since it's outside Gate B's question; worth doing before Phase 4 ships a real export button,
since a silent, unannounced audio-less clip would be a bad surprise for the user.

## Verification against the plan's checklist

- [x] Map in/out points to wall-clock via `#EXT-X-PROGRAM-DATE-TIME`, then to segment URLs in the
      highest rendition (q1080).
- [x] Fetch only those segments to app storage.
- [x] `ClipExporter` runs Transformer with `ClippingConfiguration` → MP4, transmux only (no
      re-encode) -- confirmed via the ~6MB-for-12s-source / ~5.4MB-for-10s-clip size ratio, which
      is copy-cost, not encode-cost.
- [x] Handle expiry: `SegmentSelector.selectForRange` returns `null` (surfaced as a clear
      `Result.failure` message) when the range isn't fully covered by the playlist.
- [x] Export a known 10-second span and confirm the MP4 plays and matches the range -- confirmed
      via `ExportResult.durationMs` and a raw header/box check; full on-device playback check of
      the pulled file is a good idea before Phase 4 but wasn't run this session.

Proceed to Phase 4 (Cut screen UI) next -- optionally after closing the audio gap above.
