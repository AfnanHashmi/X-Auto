# X Auto

A personal Android app for running the Bigg Boss clip-and-post workflow and the X accounts around it.
Home screen features: **Clip Cut**, **Caption Generator**, **Trigger Deletion**, **Unfollowers**, **Trend Radar**.

API keys are read from `local.properties` (git-ignored) and compiled into the debug build. They are never
hard-coded and never logged. Build and install with `./gradlew :app:installDebug`; run the tests with
`./gradlew :app:test`.

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

### Group-chat automation: kick an unfollower out of the group

The same Worker can remove an unfollower from one group chat (`GC_GROUP_ID` in `wrangler.toml`):

1. When an unfollow is stored, a follow-up is queued for **30 minutes later**.
2. A cron trigger (every 5 minutes) handles the follow-ups that are due. It reads the group once as RW (@rw_account).
3. For each person: you, RW, DK, a group admin and the whitelist are **protected** and never touched. Someone not in
   the group is skipped (no cost). Someone in the group is **re-checked** as main: if they follow again, or X cannot
   answer, they are kept. Only someone positively not following is removed.
4. **Modes** (switched in the app, stored in the database): **Off**, **Dry run** (records "would be kicked" and removes
   nobody; the starting mode) and **Live**. A dry-run decision is final: switching to Live does not retroactively remove
   those people.
5. **Safety:** one removal at a time, 3 seconds apart; at most 20 a day; it **pauses itself at the first failed or
   unconfirmed removal** and stays paused until you press Resume in the app; if RW stops being an admin it pauses; the
   remover's own id is never sent (that would make it leave the group). `connection_status` is the only signal used to
   decide someone follows, and it is the one proven in earlier runs.
6. The app shows, per person, what happened (*Kicked out of the group at 14:32*, *Would be kicked (dry run)*, *Waiting,
   re-check at 15:02*, *Not in the group*, *Followed again, kept*, *Protected*, *Could not verify, kept*, *Removal
   failed*) and a **Never kick** button. The home card shows today's "unfollowed" and "kicked from group" counts.

Extra Worker secrets for this: `X_RW_CONSUMER_KEY`, `X_RW_CONSUMER_SECRET`, `X_RW_ACCESS_TOKEN`, `X_RW_ACCESS_SECRET`
(RW, to read the group and remove) and `X_MAIN_CONSUMER_KEY`, `X_MAIN_ACCESS_TOKEN`, `X_MAIN_ACCESS_SECRET` (main, to
re-check). X's remove-members call accepts these OAuth 1.0a keys and does not need `action_signatures` (checked live).

### Cost

X charges **$0.01 per unfollow event delivered**, and **$0.01 per re-check** (only for someone who is in the group; each
person is billed once per UTC day). Setting up, reading the list, and the Worker itself cost nothing (Cloudflare free
tier). Reading the list from the app does not cost anything on X. Removals showed no charge in earlier runs. Set a
monthly spending limit in the X developer console as a hard ceiling.

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

---

## Trend Radar: what X is saying about Bigg Boss 20

Research reports and ideas only. **It never posts, replies, likes or follows.** It reads X through xAI's Grok (the
`x_search` tool) and shows rising sub-topics, what people are asking, posts worth a reply (you reply yourself, in X)
and ideas for your own posts. OpenAI is used only when you tap "Draft in Caption Generator".

### Using it (a day with credits)

1. **Add credits on xAI.** In the morning, open **Trend Radar** and switch **Day session** on. Optionally set "Spend at
   most" (blank = what is left of today's limit, or of the credits you said you added, if lower).
2. **It works through that day's window by itself**, while your phone is in your pocket (it runs on Cloudflare):
   - weekdays 12:00 PM to 1:00 AM, weekends 8:00 PM to 1:00 AM (IST; a window that ends at 1 AM belongs to the day it started);
   - the first run is a deep scan (switch on late and it runs one straight away to catch up);
   - a pulse every 60 minutes (bigger during the 9-11 PM episode peak), and more deep scans at about 6 PM and 11 PM
     on weekdays and 11 PM on weekends;
   - a new or rising sub-topic queues one focused deep scan (at most 3 a day, 3 hours between scans on the same topic).
3. **It stops by itself** at the end of the window, when the session limit is reached, after two failed runs in a row,
   or when you switch it off. At 80% of the limit it only does the scheduled deep scans. **It never starts the next day
   on its own and never runs while the switch is off.**
4. **Anytime**, even with the session off: **Run pulse** / **Run deep scan** (you see the estimated cost and confirm),
   **Deep scan on this** under a sub-topic, or **See a sample report (free)**.

Tabs: **Report** (rising first), **Cost** (today, this month, every run with its posts, tokens and cost) and
**Settings** (keywords in English, Hindi and Hinglish, contestants, hashtags, accounts to ignore, models, limits, credits).

### How it works

```
Cloudflare cron (every minute) -> Worker "x-auto-trend-radar" -> api.x.ai (Grok + x_search)  and a D1 database
app <-- HTTPS + token: GET /report, /preview; POST /session, /run, /config, /exclude
```

- The Worker (`cloudflare/trend-radar/`) has **no X credentials at all** and its only outbound request is to `api.x.ai`;
  a test fails if that ever changes. The xAI key lives only in Cloudflare; the app holds just the Worker's address and a
  token (`TREND_URL`, `TREND_TOKEN` in `local.properties`).
- Pulses use `grok-4.3` (quick, about $0.11), deep scans `grok-4.7` (sharper, about $0.85). Both are settings.
- xAI has no setting for sort order, thread fetching or how many posts to read, so those limits are in the instructions
  and the **real** posts, tokens and cost of every run are read from xAI's reply (it reports its own cost). Likes,
  replies and views are never taken from the model: the answer format has no such fields.
- Trends come only from comparing stored runs (X's date filter is whole days): a topic is **NEW** (not in earlier runs),
  **RISING** (share at least double its recent average, 3+ mentions), **PEAKING**, **FADING** or steady.

### Cost guard

Every run is checked at its worst case (1.5x the likely cost) before it starts: the **daily limit** (default $6) for
everything, the **session limit** for scheduled and follow-up runs, and the **credits you said you added** (the radar
subtracts what its own runs spent since). It cannot see your real xAI balance. A timeout is never retried, because the
request may already be running and billed; the run is recorded as "may have been billed". Expect about $4 on a weekday
with the default models.

### Setup (already done)

`cd cloudflare/trend-radar && npm install`, `npx wrangler d1 create x-auto-trend-radar` (id into `wrangler.toml`),
`npx wrangler d1 execute x-auto-trend-radar --remote --file=schema.sql`, `npx wrangler deploy`, then
`npx wrangler secret put XAI_API_KEY` and `TREND_APP_TOKEN`; put `TREND_URL` and `TREND_TOKEN` in `local.properties`.
Tests: `npm test` there (85 tests) and `python mutation-check.py` (breaks 34 safety rules one at a time and checks a test fails).

### Known limits

- Runs start within about a minute of the schedule (the cron ticks every minute).
- Cloudflare's free plan limits CPU per run; the first live runs fit comfortably. If that ever changes the fix is the
  $5 a month plan.
- The post numbers (likes, views) of cited posts are not fetched yet; the report shows none rather than guessing.
- A deep scan on the bigger model can take 2-3 minutes.
