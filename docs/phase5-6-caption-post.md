# Phase 5 & 6 — Caption Screen, Post Flow

Built together since Phase 1 had already scaffolded everything both phases needed: the four stub
API interfaces + fakes, and the `CaptionTemplate`/`UndoEntry`/`ClipRecord` Room entities/DAOs.
Verified end-to-end on physical device against the real live stream.

## What was built

**Navigation.** `ClipCutDestination` routes now `Uri.encode`/`decode` their path args -- clip file
paths and caption text both contain characters (`/`, spaces) that would otherwise break Navigation
Compose's single-segment `{arg}` matching. `Post`'s route carries `clipPath`, `caption`, and the
`MediaRef` (`id`/`url`) as four encoded path segments -- simple and consistent with the route-based
approach already sketched in Phase 1, avoiding a shared-ViewModel nav-graph-scoping design for what
is a small, fixed data shape.

**Cut -> Caption handoff.** `CutViewModel` gained a one-shot `SharedFlow<String>` emitted on
successful export; `CutScreen` collects it in a `LaunchedEffect` and calls `onExported(path)`,
which `MainActivity` wires to `navController.navigate(...)`. Export success also now inserts a
`ClipRecord` row (`filePath`, `startEpochMs`, `endEpochMs`) -- the first real use of that Phase-1
DAO.

**`CaptionViewModel`.** On load: uploads the clip via `MediaUploader` (updating the `ClipRecord`
with the resulting `mediaRefId`), and separately loads `MediaAccountApi.getLatestPost()` to
auto-embed. Caption edits are coalesced into one undo step per "typing run" rather than per
keystroke -- the pre-edit text is captured on the first keystroke and only pushed to the
`UndoEntry` stack when the run is committed (text field loses focus, or another mutating action --
template apply, enhance, undo itself -- interrupts it). Template apply and AI enhance each push
their own undo step. `saveAsTemplate` writes a new `CaptionTemplate` row, observed live via
`captionTemplateDao.observeAll()` so the template chip list updates immediately.

**`PostViewModel`.** Auto-posts on screen entry (the user already reviewed the caption on the
previous screen) via `MainAccountApi.post(caption, mediaRef)`, with loading/success/error
`PostUiState`, and marks the `ClipRecord` as `posted = true` on success. Error state offers Retry.

## Verified on-device: the full chain, all fakes

scrub -> cut -> export -> upload -> embed -> caption edit -> **Enhance with AI** -> **Undo**
(reverted cleanly, button correctly re-disabled once the stack emptied) -> **Continue to post** ->
auto-post -> **Posted!** -> **Done** -> back to Cut. No crashes at any step.

One real finding from testing, not a bug: dismissing the on-screen keyboard with the system
**back** button is only safe to do once, while the keyboard is confirmed visible -- Android's back
button closes an open IME first and only navigates on a second press, but pressing back when the
keyboard is *already* closed pops the nav stack immediately (sent Caption back to Cut once during
testing). Not an app issue, just a note for anyone else driving this via `adb input keyevent
KEYCODE_BACK`: check keyboard state first, or dismiss via tapping elsewhere / the IME's own
collapse control instead.

## Verification against the plan's checklist

- [x] Four stub interfaces (`MediaUploader`/`MediaAccountApi`/`MainAccountApi`/`CaptionEnhancer`)
      with `Fake*` implementations, already bound in Phase 1's `ApiModule` -- no changes needed to
      go from stub to a working screen.
- [x] Auto-embedded media from `getLatestPost()`.
- [x] Caption text field, "Enhance with AI" button, template picker (Room-backed).
- [x] Undo, covering manual edits, template selection, and AI enhancement.
- [x] `MainAccountApi.post(caption, mediaRef)` wired with loading/success/error states.
- [x] Full chain run end-to-end against the fakes on a physical device.

## Known gaps / follow-ups

- No real credentials/endpoints yet for any of the four interfaces -- deferred by design per the
  plan's open items; swapping in real implementations only requires new `Real*` classes bound in
  `ApiModule`, no UI/ViewModel changes.
- `CaptionScreen`'s "embedded media" is text (the fake's URL string), not an actual video/thumbnail
  preview -- fine for exercising the flow, but a real media preview is worth adding once there's
  real media to preview.
- Template list has no delete UI yet (`CaptionTemplateDao.delete` exists but is unused).
