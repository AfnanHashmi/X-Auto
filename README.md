# X Auto

A personal Android app for running the Bigg Boss clip-and-post workflow and the X accounts around it.
Home screen features: **Clip Cut**, **Caption Generator**, **Trigger Deletion**, **GC Prune**.

API keys are read from `local.properties` (git-ignored) and compiled into the debug build. They are never
hard-coded and never logged. Build and install with `./gradlew :app:installDebug`; run the tests with
`./gradlew :app:test`.

---

## GC Prune: remove the people you list from a group chat

You paste the usernames of the people to remove from an X group chat (GC). The app looks them up, shows you
exactly who each one is, and removes the ones you tick. Because a wrong removal is far worse than a missed one,
every rule is built to fail toward *not* removing anyone.

### Who does what

| Job | Account |
|---|---|
| Looks the usernames up and says who follows you | **main** (@main_account) |
| Reads the group, removes members | **RW** (@rw_account), which must be an **admin** of the group |

### One-time setup

1. RW's developer app needs permission **Read and write and Direct message**. After changing it, **regenerate
   RW's Access Token and Secret** (old tokens keep the old permissions) and put them in `local.properties`:
   `X_RW_ACCESS_TOKEN`, `X_RW_ACCESS_SECRET` (alongside `X_RW_CONSUMER_KEY`, `X_RW_CONSUMER_SECRET`, `X_RW_HANDLE`).
2. The main account's four keys (`X_MAIN_*`) must be present.
3. Rebuild and install.

### Using it

1. Open **GC Prune** on the home screen. Paste the group link (`https://x.com/i/chat/g...`) or the bare id
   (`g...`), then paste the usernames, separated by new lines, spaces or commas. `name`, `@name` and
   `x.com/name` links all work. Anything that is not clearly a username is listed as "not understood" and left
   out; the app never guesses.
2. Tap **Look up**. The button shows the cost first (see *Cost*). This removes nobody. Each name comes back as
   one of:
   - **In the group, does not follow you**: ticked, ready to remove.
   - **Follows you**: shown with a warning and **not** ticked, because it is probably a typo or a wrong paste.
   - **Not in this group**, **Protected** (you, your other accounts, a group admin, or the whitelist), or
     **Not found**: greyed out and impossible to tick.
3. Review. Untick anyone you want to keep, or tap **Keep** to add them to the whitelist permanently.
4. To remove: switch **Allow removal** on, set the limits, **type the group id exactly**, tap **Remove**, and
   confirm the final dialog. Without the typed id nothing can be removed.

### Safety rules

- **Only people you pasted.** The app never decides on its own who should go; there is no scan of the group or of
  your followers.
- **Nothing is removed without all of:** the switch on, the group id typed, the person ticked, a final confirmation.
- **Never removed:** yourself (main, DK and RW), group admins, and anyone on the whitelist. These are checked
  when the list is built, and again against a fresh read of the group right before each removal.
- **Followers are flagged.** Anyone who follows your main account starts unticked with a warning, and the final
  dialog says how many ticked people follow you.
- **The follow answer is proven first.** Every lookup also asks about DK, who is known to follow main. If X does
  not report that, the follow warnings cannot be trusted, so **nobody is pre-ticked** and the screen says why.
- **Fails closed.** A lookup that errors, is rate-limited, or comes back with an answer that does not match the
  names asked about shows no list at all.
- **Paced.** One person at a time, 5 seconds apart by default (never under 1 second). It reads X's
  `x-rate-limit-*` headers and waits for a reset of up to 15 minutes; a longer wait or a 429 stops the run.
- **Capped.** 20 removals per run by default, 50 at most.
- **Stops at the first surprise.** Any unexpected reply from X stops the run immediately; the rest of the batch is
  not attempted. If a request gets no reply, the app re-reads the group to see whether the person was removed,
  records it, and stops.
- **Someone who has already left**, or became an admin or whitelisted since the lookup, is skipped.

### Audit log

Every lookup and every removal run writes a JSON file, rewritten after each step, so it is always current:
`Documents/XAuto/gc-prune/gc-prune-<group>-<time>.json` (a second copy is kept in the app's private storage).
It records the group, settings, every pasted name with its status, and every removal, skip and failure with the
user id, handle and name. Before each removal request, the person is recorded as `in_flight`, so even a crash
mid-request leaves a trace. **To re-add someone, use the user ids in the `removed` list.**

If the audit file cannot be written, the run stops rather than continuing without a record.

### Whitelist

`whitelist.json` (in the app's private storage) holds user ids that are never removed. Add or remove entries on
the GC Prune screen, by numeric id or by @handle. If the file is damaged the app refuses to run rather than
treating it as empty.

### Cost

X's pay-per-use pricing charges **$0.01 per account read**, and each account is billed once per UTC day (the day
changes at 05:30 IST). The lookup reads each pasted username plus one extra account used for the follow check, so
**50 names cost about $0.51**. The button shows the figure before anything is read. Reading the group and removing
a member have no price on X's published pricing page; check X's usage page after your first run. The app's
figure is its own arithmetic from the published price, and X's usage page is the source of truth.

### Known limits

- **Encrypted chats and `action_signatures`.** X's remove-members endpoint accepts optional cryptographic
  signatures for encrypted chats. Producing one needs X's Chat encryption keys, which this app does not hold, and
  it never fakes one. The request is sent without. If X rejects it for lacking a signature, the run stops, nobody
  has been removed, and the screen says so. **Do the first live run with a single person** (cap 1) and confirm the
  group still works afterwards.
- **Names are taken as written.** Pasting `Ana Smith` (a display name) is read as two usernames, `Ana` and
  `Smith`, and each is looked up. They are only removed if they are in the group, not protected, and you leave
  them ticked, so read the review table: it shows the name, handle and id of everyone.
- **The group's name is encrypted**, so the screen identifies the group by its id.

### Where the code is

`domain/gcprune/` (logic and the X seam), `data/gcprune/` (whitelist and audit storage), `ui/gcprune/` (screen).
The safety logic is covered by tests in `app/src/test/java/com/clipcut/domain/gcprune/`, run against a fake X.
