# X Auto

A personal Android app for running the Bigg Boss clip-and-post workflow and the X accounts around it.
Home screen features: **Clip Cut**, **Caption Generator**, **Trigger Deletion**, **GC Prune**.

API keys are read from `local.properties` (git-ignored) and compiled into the debug build. They are never
hard-coded and never logged. Build and install with `./gradlew :app:installDebug`; run the tests with
`./gradlew :app:test`.

---

## GC Prune: remove group-chat members who don't follow you

Removes people from an X group chat (GC) who do not follow your **main account**. Because a wrong removal is
far worse than a missed one, every rule is built to fail toward *not* removing anyone.

### Who does what

| Job | Account |
|---|---|
| Whose followers count ("follows me") | **main** (@main_account) |
| Reads the group, removes members | **RW** (@rw_account), which must be an **admin** of the group |

### One-time setup

1. RW's developer app needs permission **Read and write and Direct message**. After changing it, **regenerate
   RW's Access Token and Secret** (old tokens keep the old permissions) and put them in `local.properties`:
   `X_RW_ACCESS_TOKEN`, `X_RW_ACCESS_SECRET` (alongside `X_RW_CONSUMER_KEY`, `X_RW_CONSUMER_SECRET`, `X_RW_HANDLE`).
2. The main account's four keys (`X_MAIN_*`) must be present: they supply the follower list.
3. Rebuild and install.

### Using it

1. Open **GC Prune** on the home screen and paste the group link (`https://x.com/i/chat/g…`) or the bare id
   (`g…`). Only group ids are accepted.
2. Tap **Scan**. The app first shows **what the scan will cost** (see *Cost* below) and reads nothing until you
   approve. A scan is a **dry run**: it removes nobody. It reads the group, downloads your complete follower list,
   and shows a table of candidates: @handle, name, user id and the reason each was flagged.
3. Review. Untick anyone you want to keep, or tap **Keep** to add them to the whitelist permanently.
4. To actually remove people: switch **Allow removal** on, set the limits, **type the group id exactly**, and tap
   **Remove**. Without the typed id nothing can be removed.

### Safety rules

- **Fails closed.** If the follower fetch is incomplete, rate-limited, errors, repeats a page, or looks implausible,
  the scan aborts and shows *no* candidates. A partial follower list would flag real followers.
- **Sanity checks.** Aborts if fewer than 97% of the followers X reports were fetched, or if more than half of the
  group would be flagged.
- **Never removed:** yourself (main, DK and RW), group admins, and anyone in the whitelist. This is checked again
  against a fresh read of the group right before removing.
- **Re-checked individually.** Immediately before each removal the app asks X whether that person follows you
  *right now*. Anyone who followed since the scan is skipped, and so is anyone X cannot positively answer for.
- **Proves the check works first.** Before the first removal it confirms that known followers are reported as
  following; if not, it aborts.
- **Paced.** One person at a time, 5 seconds apart by default (never under 1 second). It reads X's
  `x-rate-limit-*` headers and waits for a reset of up to 15 minutes; a longer wait or a 429 stops the run.
- **Capped.** 20 removals per run by default, 50 at most.
- **Stops at the first surprise.** Any unexpected reply from X stops the run immediately; the rest of the batch is
  not attempted. If a request gets no reply, the app re-reads the group to see whether the person was removed,
  records it, and stops.

### Audit log

Every scan and every removal run writes a JSON file, rewritten after each step, so it is always current:
`Documents/XAuto/gc-prune/gc-prune-<group>-<time>.json` (a second copy is kept in the app's private storage).
It records the group, settings, follower counts, every candidate, and every removal, skip and failure with the
user id, handle and name. Before each removal request, the person is recorded as `in_flight`, so even a crash
mid-request leaves a trace. **To re-add someone, use the user ids in the `removed` list.**

If the audit file cannot be written, the run stops rather than continuing without a record.

### Whitelist

`whitelist.json` (in the app's private storage) holds user ids that are never removed. Add or remove entries on
the GC Prune screen, by numeric id or by @handle. If the file is damaged the app refuses to run rather than
treating it as empty.

### Known limits

- **Encrypted chats and `action_signatures`.** X's remove-members endpoint accepts optional cryptographic
  signatures for encrypted chats. Producing one needs X's Chat encryption keys, which this app does not hold, and
  it never fakes one. The request is sent without. If X rejects it for lacking a signature, the run stops, nobody
  has been removed, and the screen says so. **Do the first live run with a single person** (untick everyone else,
  cap 1) and confirm the group still works afterwards.
- **No group-member endpoint beyond one read.** X returns the full member and admin lists in one response, which is
  what the app uses. The group's name comes back encrypted, so the screen identifies the group by its id.
- **Cost: read this before the first scan.** X's pay-per-use pricing charges **$0.01 per follower record read**.
  A full scan of an account with 24,028 followers is therefore about **$240**. X bills each person once per UTC
  day (the day changes at 05:30 IST), so scanning again the same day costs far less. On top of that, each candidate
  profile lookup and each pre-removal check costs $0.01 (a few dollars at most). The app shows the estimate and
  needs your approval first, but the figure is the app's arithmetic from X's published price; X's usage page is
  the source of truth. Consider setting a spending cap in the X developer console.
- **Follower counts drift.** X's reported count can lag a little; the 97% threshold allows for that.

### Where the code is

`domain/gcprune/` (logic and the X seam), `data/gcprune/` (whitelist and audit storage), `ui/gcprune/` (screen).
The safety logic is covered by tests in `app/src/test/java/com/clipcut/domain/gcprune/`, run against a fake X.
