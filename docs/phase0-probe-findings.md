# Phase 0 — Stream Probe Findings

Probed 2026-09-17 ~15:30 UTC against `https://biggboss24hrs.com`.

## Auth flow (needed by the app, not just this probe)

The website's stream URLs are short-lived signed links gated behind two layers of session:

1. `POST /api/login` with `{email, password}` + header `x-app-platform: Streamflix-App`
   → sets `streamflix_session` cookie (JWT, 30-day `Max-Age`). Also returned as `token` in the
   body for `Authorization: Bearer` use if needed.
2. That alone is **not** sufficient — `GET /api/stream/urls` returns `403 Forbidden: valid access
   session required` until a second cookie, `access_session`, is set. That cookie comes from
   visiting `GET /access?token=...` (an ~8h-lived JWT of its own, `access_session` cookie good for
   the same window — observed `Max-Age=28794` ≈ 8h).
3. Only then does `GET /api/stream/urls` return the actual signed per-rendition `.m3u8` URLs:
   `auto`, `q1080`, `q720`, `q480`, `q360`. Each URL's `policy` query param carries a
   `url_expire` epoch-ms timestamp — observed validity **~12h** from mint time.

The `/access?token=...` link is currently obtained from an admin-only endpoint
(`/admin/current-link?key=...`) — that's a manual/admin path, **not** what the shipped app should
use. The real app needs its own path to a valid `access_session`; that's an open item (see below).

For the app: this means `MediaAccountApi`/auth needs to run steps 1–2 on launch (or when a signed
URL is close to expiry) before calling `/api/stream/urls`, not just re-hit a hardcoded link.

## Segment format — Gate B looks favorable

Segments are **fMP4/CMAF** (`.m4s`), each rendition's playlist starts with
`#EXT-X-MAP:URI="init_N_video_..._llhls.m4s"`. No `.ts` anywhere. This is the naming/tag pattern
of **OvenMediaEngine's LLHLS packager** (`chunklist_N_{video,audio}_<streamid>_llhls.m3u8`,
`seg_N_M`, `part_N_M_K`, `init_N`).

This is the favorable case for Gate B — Media3 Transformer's `ClippingConfiguration` should be
able to trim fMP4/CMAF without hitting the `.ts` remux problem
([androidx/media#725](https://github.com/androidx/media/issues/725)). Still needs the real Phase 3
spike to confirm, but the format risk is now low, not unknown.

## Renditions

| id | resolution | bandwidth | codecs |
|---|---|---|---|
| q1080 | 1920x1080@30 | 3,356,976 | avc1.640028, mp4a.40.2 |
| q720 | 1280x720@30 | 2,660,000 | avc1.4d401f, mp4a.40.2 |
| q480 | 856x480@30 | 1,360,000 | avc1.4d401f, mp4a.40.2 |
| q360 | 640x360@30 | 760,000 | avc1.4d401e, mp4a.40.2 |

Audio is a shared external group (`aac_audio`, stereo AAC), muxed via `#EXT-X-MEDIA`. Export
should target **q1080** (highest rendition) per the plan.

## `#EXT-X-PROGRAM-DATE-TIME` — present, exact

Every segment carries a millisecond-precision `#EXT-X-PROGRAM-DATE-TIME`
(e.g. `2026-09-17T15:17:56.622+00:00`), one per `EXTINF`. Wall-clock ↔ segment mapping can be
exact, not inferred from accumulated `EXTINF` durations as the plan's fallback assumed.

## This is real Low-Latency HLS, not plain HLS

Despite `EXT-X-VERSION:6`, the media playlists carry full LL-HLS: `EXT-X-SERVER-CONTROL`,
`EXT-X-PART-INF` (0.5s parts), per-segment `EXT-X-PART`, `EXT-X-PRELOAD-HINT`,
`EXT-X-RENDITION-REPORT`. `TARGETDURATION` is 6s (full segments), parts are 0.5s.

Media3's HLS extractor generally tolerates LL-HLS tags fine for normal (non-low-latency-mode)
playback, but this should be explicitly verified in Phase 2 rather than assumed — worth watching
for parser warnings/crashes on the `EXT-X-PART`/`EXT-X-PRELOAD-HINT` tags specifically.

## DVR depth — CONFIRMED via server config (Gate A closed)

At probe time, `chunklist_0` (q1080) had **126 segments**, `#EXT-X-MEDIA-SEQUENCE:0`, spanning
2026-09-17T15:17:56.622Z → 15:30:32.622Z (~12.6 minutes) — no eviction had happened yet, so the
playlist itself couldn't reveal the configured cap. Settled instead by reading the origin's
OvenMediaEngine `Server.xml`:

```xml
<DVR>
    <Enable>true</Enable>
    <EventPlaylistType>true</EventPlaylistType>
    <TempStoragePath>/data/ome_dvr/</TempStoragePath>
    <MaxDuration>7200</MaxDuration>  <!-- seconds -->
</DVR>
```

`MaxDuration` is **7200 seconds = exactly 2 hours**, and per an in-repo-adjacent comment on that
config, the seconds-unit behavior was empirically validated on this exact OME install (v0.20.5) by
setting `MaxDuration=120` and confirming on-disk segment age spread stayed ~114–120s. High
confidence this produces a true 2h rolling window in production.

**Gate A is closed. The plan's core assumption — server-side 2h DVR window, no local rolling
buffer needed — holds.** `PlaylistRepository`/the cut screen should treat scrubback depth as
config-driven (per the plan) but can default-assume up to 2h.

Bonus finding: this `MaxDuration` was **reduced from 16h to 2h on 2026-09-11** after a real
incident — at 16h the chunklist grew to ~1.2MB and was polled ~0.6×/sec/viewer, projecting to
~2.3TB/day at only 40 concurrent viewers. This is direct, already-lived evidence for why the
plan's "same segment URLs, no cache-busting, no second OBS output" requirements exist — the box
has already nearly been data-capped by playlist churn once. It's also why the manifest-polling
line item in Phase 0's brief matters in practice, not just in theory.

## CDN caching — confirmed, data cap is safe

Fetched `init_0...m4s` and `seg_0_10...m4s` twice each, same signed query string both times:

| resource | 1st fetch | 2nd fetch |
|---|---|---|
| init segment (681 B) | `cf-cache-status: MISS` | `cf-cache-status: HIT` |
| data segment (3,052,175 B, 6s @ q1080) | `MISS` | `HIT` |

Both serve `Cache-Control: public, max-age=14400` (4h) and hit CDN on the second request despite
carrying the same `policy`/`signature` query params as the first — confirms the plan's assumption
that repeated segment requests **do not** re-hit origin. Good: the data cap is only at risk from
first-time-view origin fetches (normal for any live viewer) and from repeated *manifest* fetches,
which are cheap (~18 KB now, ~150–170 KB estimated at a full 2h/126-segments-per-13-min growth
rate) and explicitly `no-store` (always live, as expected — a live edge manifest can't be cached).

## Delta updates — not observed

No `EXT-X-SKIP` or `CAN-SKIP-UNTIL` in the current (126-segment, ~18 KB) playlist. Can't rule out
that it appears only once the playlist grows larger; worth re-checking once the playlist is near
its full DVR size. Not fatal either way — manifest traffic is small per the size estimate above.

## Open items carried into later phases

- **App-side auth path** to get a fresh `access_session` — the `/admin/current-link` route used
  for this probe is not what the shipped app should call. Needs a real login → access-session flow
  wired into `MediaAccountApi`/auth (Phase 5/6 territory, but the *shape* of it — login once,
  re-mint the signed URL before ~12h expiry — should inform `PlaylistRepository`'s design in
  Phase 1's `data/stream/` package now).

## Summary — both gates status at end of Phase 0

| Gate | Question | Status |
|---|---|---|
| A | ~2h seekable DVR window? | **Confirmed** — `MaxDuration=7200s` in origin config, empirically validated unit semantics. |
| B | Can Transformer trim the segments? | **De-risked, not proven** — fMP4/CMAF confirmed (not `.ts`), but still needs the Phase 3 spike to actually run `ClippingConfiguration` against real segments. |

Phase 0 is done. Proceed to Phase 2 (live playback + DVR seek spike) next.
