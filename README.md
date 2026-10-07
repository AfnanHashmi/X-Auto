# X Auto

A personal Android app for running the Bigg Boss clip-and-post workflow and the X accounts around it.
Home screen features: **Clip Cut**, **Caption Generator**, **Trigger Deletion**, **GC Prune**, **Unfollowers**.

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
  signatures for encrypted chats. This app does not hold X's Chat encryption keys and never fakes a signature, so
  it sends the request without one. In a live run on 2026-10-05 X accepted three removals this way. If X ever
  starts requiring a signature, the run stops with nobody further removed and the screen says so.
- **Names are taken as written.** Pasting `Ana Smith` (a display name) is read as two usernames, `Ana` and
  `Smith`, and each is looked up. They are only removed if they are in the group, not protected, and you leave
  them ticked, so read the review table: it shows the name, handle and id of everyone.
- **The group's name is encrypted**, so the screen identifies the group by its id.

### Where the code is

`domain/gcprune/` (logic and the X seam), `data/gcprune/` (whitelist and audit storage), `ui/gcprune/` (screen).
The safety logic is covered by tests in `app/src/test/java/com/clipcut/domain/gcprune/`, run against a fake X.

---

## Unfollowers: who unfollowed your main account

Shows who unfollowed @main_account and when, as X reports it. It only knows about unfollows from the moment
tracking started; X offers no way to look back.

### How it works

X's Activity API sends a `follow.unfollow` event each time someone unfollows the account. X can only *push* these
events (to a public HTTPS address, or to a connection that is already open), and it refuses to replay history on a
pay-per-use plan (`start_time` and `backfill_minutes` are rejected), so the app cannot ask for "the last 24 hours" on
tap. A small always-on receiver is therefore needed. It is a **Cloudflare Worker** (free tier) in
`cloudflare/unfollows/`:

```
X --webhook (HTTPS)--> Cloudflare Worker --stores--> D1 database <--reads (HTTPS, token)-- the app
```

- The Worker answers X's challenge check, verifies the signature on every delivery, stores each unfollow once
  (de-duplicated on X's `event_uuid`), and serves the list to the app.
- The X secrets live only in Cloudflare. The app holds just the Worker's address and a read token.
- **Safety:** if an event ever arrives that is not "someone unfollowed the tracked account", or more than 300 events
  arrive in one UTC day, the Worker deletes the X subscription itself, records why, and the app shows
  "Tracking is paused". That stops X billing further events.

### Cost

X charges **$0.01 per unfollow event delivered**. Setting up, reading the list, and the Worker itself cost nothing
(Cloudflare free tier). Reading the list from the app does not cost anything on X. Set a monthly spending limit in the
X developer console as a hard ceiling.

### Setup (already done for the main account)

1. `cd cloudflare/unfollows && npm install && npx wrangler login`
2. `npx wrangler d1 create x-auto-unfollows`, put the id in `wrangler.toml`, then
   `npx wrangler d1 execute x-auto-unfollows --remote --file=schema.sql` and `npx wrangler deploy`.
3. Set three secrets with `npx wrangler secret put`: `CONSUMER_SECRET` (the X app's API secret), `BEARER` (its app-only
   token) and `APP_TOKEN` (any long random string).
4. Register the webhook and create the subscription with the app's keys: `POST /2/webhooks` with
   `https://<worker>/x/webhook`, then `POST /2/activity/subscriptions` with
   `{"event_type":"follow.unfollow","filter":{"user_id":"<main id>","qualifiers":{"direction":"inbound"}},"webhook_id":"<id>"}`.
   The `inbound` qualifier matters: without it you are also billed for people *you* unfollow.
5. Put `UNFOLLOW_URL` and `UNFOLLOW_TOKEN` (the same string as `APP_TOKEN`) in `local.properties` and rebuild.

### Known limits

- **Gaps.** While the Worker or X's delivery is down, unfollows may be missed. X's webhook replay can re-send up to the
  last 24 hours; it is not wired in yet.
- **No follow date.** X does not say when the person started following, so that is not shown.
- **Re-follows are not tracked** (that would bill every new follower). A person who unfollows twice shows as
  "unfollowed 2 times".
- **Suspended or deleted accounts.** X's docs do not say whether these produce an unfollow event.

### Where the code is

`cloudflare/unfollows/` (the Worker, with `npm test`), `domain/unfollows/` and `data/db/Unfollow.kt` (fetch and storage),
`ui/unfollows/` (screen). Tests: `cloudflare/unfollows/test/` and `app/src/test/java/com/clipcut/domain/unfollows/`.
