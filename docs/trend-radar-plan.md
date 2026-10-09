# Trend Radar: an X research feature (reports and suggestions only)

## Context

You want X Auto to watch what people on X are saying about Bigg Boss 20 and turn it into reports.

- **What it produces:** rising sub-topics, the questions people are asking, posts worth replying to, and angles for
  your own posts.
- **What it never does:** post, reply, like or follow. **Nothing touches X except reading.**
- **Who does what:** xAI Grok does the X research. OpenAI remains the only drafting tool, through the existing
  Caption Generator, and only when you tap.

Your decisions:
- **No notifications.** Spikes show up inside the app only: at the top of the report, and as a badge on the home card.
- **Episode airtime is 9:00 PM daily**, so the default peak window is 9–11 PM.
- **Pulses every 60 minutes** instead of 30. This is configurable.

## How you'll use it (day to day)

You don't start anything. It runs on its own in the background from 12 PM on weekdays and 8 PM on weekends, until
1 AM. You open the app whenever you want ideas.

1. **Home screen.** The Trend Radar card shows, for example, "3 rising · last scan 21:05". It turns coloured when
   something is suddenly taking off.
2. **Tap the card** to open the latest report, newest first:
   - **What's rising right now:** for example "RISING: Arishfa vs Kanika kitchen fight, 18 mentions (22%), angry".
     Each one has 2 example posts you can tap to open in X.
   - **What people are asking:** for example "Was Gullu's eviction fixed?"
   - **Posts worth replying to:** a link plus why, such as "high engagement, asks a question you can answer". You open
     it in X and reply yourself if you want; the app never replies.
   - **Ideas for your next post:** short angles, not finished posts. Tap **Draft in Caption Generator** on one, and the
     existing caption screen opens with that idea, so OpenAI writes the draft there. Then use it as you do today.
3. **Other tabs:**
   - **Cost:** today and this month, and what each scan cost.
   - **Settings:** edit keywords, contestant names and hashtags, block spam accounts, and change the times and the
     daily limit.
4. **When you want it right now:** **Run pulse now** (cheap and quick) or **Run deep scan now** (the full report)
   work any time, even outside the active hours.

## What exists today, and what I'll reuse

- **Android app** (Kotlin, Compose, Hilt, Room v12):
  - It has **no notification channel and no WorkManager**; both were removed with Daily Link.
  - Keys are read from `local.properties` into `BuildConfig`.
  - OpenAI drafting already lives in the Caption Generator (`ui/caption/CaptionScreen.kt`, which also holds your hashtag
    list `#BiggBoss20 #QaziTouqeer #ArishfaKhan #UditiSingh #YungDSA #KanikaMann #Gullu`).
- **There is already an always-on scheduler:** the Cloudflare Worker `cloudflare/unfollows/`. It has D1 storage, cron
  triggers, Cloudflare secrets, a token-protected API that the app reads, and a tested OAuth 1.0a signer
  (`src/oauth1.js`). Trend Radar has to run from 12 PM to 1 AM whether your phone or PC is on, so it belongs there.
- **Patterns to copy:**
  - app side: `domain/unfollows/*` (parser, service, `RealUnfollowsApi`) and `ui/unfollows/*` (screen and home card);
  - Worker side: `test/helpers.js` (the D1 fake) and its mutation-check approach.

## Architecture

```
Cloudflare cron (every 5 min) → Trend Radar Worker (new, cloudflare/trend-radar/)
   ├─ schedule.js  : decides whether this tick runs anything (IST windows, peak, budget)
   ├─ xai.js       : OpenAI SDK, baseURL https://api.x.ai/v1, Responses API + x_search + JSON schema
   ├─ trends.js    : matching, growth score, NEW/RISING/PEAKING/FADING, spike detection, cooldown
   ├─ budget.js    : cost of each run and the guard (80% → deep scans only, 100% → stop for the day)
   ├─ enrich.js    : optional engagement numbers via X GET /2/tweets (read-only, $0.005 per post)
   └─ D1 database x-auto-trends  ←  app reads it over HTTPS with its own token
```

- **A separate Worker and database** from the unfollow tracker, so a bug or a spend problem here can never touch the
  tracker. The OAuth signer is shared through `cloudflare/shared/oauth1.js`.
- **Secrets, in Cloudflare only:**
  - `XAI_API_KEY`;
  - `TREND_APP_TOKEN`, the phone's read token;
  - main's X keys, only if enrichment is on.
  
  The app gets just the Worker's address and the token (`TREND_URL` and `TREND_TOKEN` in `local.properties`). **No
  xAI key ever goes in the app.**
- **Read-only, enforced in code:** the X client in this Worker exposes only `GET /2/tweets`. A test fails if the
  Worker's code contains any other x.com call. The only POST it ever makes is to `api.x.ai`.

## The xAI call (checked against docs.x.ai, 2026-10-09)

- `client.responses.create({ model, tools: [{ type: "x_search", from_date, to_date, excluded_x_handles,
  enable_image_understanding: false, enable_video_understanding: false }], text: { format: json_schema, strict },
  max_turns })`. In Node, `max_turns` goes in the request body.
- **Model:** set in config. The default is `grok-4.7`, which the docs use for x_search and structured outputs ($2 in,
  $6 out per million tokens). Step 1 also tests `grok-4.3` ($1.25 in, $2.50 out); if its output is as good, it
  becomes the default.
- **Two things your spec asks for that the API can't enforce.** The docs list **no parameter for sort order (Top or
  Latest), for turning off thread fetches, or for the number of posts**; the model chooses those itself. So:
  - "Latest only, no thread fetches, about 20 posts" for pulses, and "Top + Latest, thread fetches allowed, about
    80–100 posts" for deep scans, go **in the instructions**, together with `max_turns` 1 or 2.
  - Every run records the actual `x_posts_fetched`, `x_users_fetched`, `x_search_calls` and tokens.
  - If a pulse fetches more than 2× its target, it is flagged on the cost dashboard, and the budget uses the real
    numbers, never the planned ones.
- **Dates:** `from_date` and `to_date` work by whole day only (and `to_date` is exclusive). Pulses use the last 1 day,
  deep scans the last 1–2 days. Changes within a day come only from comparing stored runs.
- **No made-up numbers.** The schema has no engagement fields for the model to fill in. Likes, replies and views come
  only from enrichment (deep scans only, the top 5 cited posts, about $0.025 per scan, on by default and switchable
  off). Otherwise they stay empty.
- **Validation:** every reply is checked against the schema after parsing. Post URLs must look like `x.com/*/status/<id>`.
  A failed check marks the run `invalid`, and its numbers never feed the trend history.

## Schedule (IST, all values in config)

- **Weekdays:** active 12:00 PM to 1:00 AM.
  - Deep scans at 12:00, about 18:00 and about 23:00.
  - A pulse every 60 minutes.
- **Weekends:** active 8:00 PM to 1:00 AM.
  - Deep scans at 20:00 and about 23:00.
  - A pulse every 60 minutes.
- **The 12:00–1:00 AM spill counts as the day it started.** "Is this tick inside a window" checks both today's window
  and the window that started yesterday.
- **Peak window:** 21:00 to 23:00 daily. Pulses there use 40 posts instead of 20.
- **Spike scans:**
  - A pulse triggers one when a sub-topic is new with at least 3 mentions, or when its share is at least twice its
    baseline and up 10 points.
  - The scan focuses on that sub-topic's keywords.
  - There is a 3-hour cooldown per sub-topic and at most 3 spike scans a day.
  - They run only inside a window and only if the budget allows.
- **Nothing runs outside a window**, spike scans included. The only exception is the manual run (below).

## Storage (D1) and trend logic

- **Tables:**
  - `config`: one JSON document, editable from the app;
  - `runs`: type, trigger, times, window day, model, settings, status, error, posts, users, search calls, tokens
    (input, cached, output, reasoning), cost;
  - `subtopics`: normalized key, display name, aliases, first and last seen;
  - `snapshots`: run, key, mentions, share, sentiment, keywords, examples, label, growth;
  - `findings`: questions, reply targets and angles from deep scans;
  - `post_metrics`: enrichment results.
- **Normalized key:** lower-case, `#` removed, punctuation and filler words dropped, Hinglish spelling variants merged
  through an alias table, words sorted. If no key matches exactly, a similar existing one (word overlap of 0.6 or more,
  or close spelling) is matched instead, and the new name is stored as an alias.
- **Growth and labels:** share means mentions divided by all mentions in that run's sample. The baseline is the
  average share over the last 4 pulses and the last 24 hours. Growth is (share − baseline) ÷ max(baseline, 0.02).
  - **NEW:** first seen within the last 48 hours.
  - **RISING:** growth of 1.0 or more and at least 3 mentions.
  - **PEAKING:** near its highest share, and growth has flattened.
  - **FADING:** share at least 40% below its peak over the last 3 runs.

## Budget guard

- **Prices in config:**
  - $5 per 1,000 posts;
  - $10 per 1,000 user profiles;
  - per-model token rates, including cached and reasoning tokens;
  - $0.005 per enriched post.
- **Daily cap:** $6 by default, counted per window day.
- **Before each run:** it estimates the worst case from recent averages of that run type.
  - At 80% of the cap, pulses and spike scans stop; scheduled deep scans continue.
  - At 100%, or if the next run would cross 100%, everything stops until the next window. It is logged, and the app
    shows a banner.
- **Expected cost:** about $3.5 per weekday and $2 per weekend day, roughly $90 a month. Step 1 replaces these
  guesses with measured numbers.

## In the app

- **Home card "Trend Radar":** shows "N rising · last scan 21:05". The card is tinted while there is a RISING or NEW
  spike.
- **Report screen:**
  - the latest report first, with RISING and NEW sub-topics at the top;
  - for each sub-topic: label chip, mention count and share, sentiment, keywords, and 2 example posts that open in X;
  - deep-scan sections: what people are asking, posts worth replying to (link and why; you reply yourself), and
    suggested angles;
  - each angle has **"Draft in Caption Generator"**, which opens the existing OpenAI caption screen with the angle as
    context. That is the only drafting, and only when you tap.
- **Cost tab:** today and this month, plus a list of runs with type, posts, tokens and cost.
- **Settings:**
  - topics, keywords (English, Hindi and Hinglish), contestants, hashtags and excluded handles (at most 20);
  - pulse sizes, the model, prices, cap and schedule.
  
  Every report row has **"Exclude this account"**, which adds it to the spam list.
- **Manual run:** "Run pulse now" and "Run deep scan now" work outside windows and still respect the 100% cap.
  There's also a free **dry run** that replays a saved sample answer, so nothing is spent. The same is available as
  `npm run radar -- --type deep [--dry-run]`.

## Errors

- 60-second timeout on pulses and 120 seconds on deep scans.
- Two retries with backoff on 429, 5xx and timeouts. It honours `retry-after`.
- Errors are stored on the run, and a failed run never changes trends.
- A missing key or bad config leaves the run `skipped` with a reason.

## Tests (mocked xAI, no network)

- **Fixtures:** sample pulse and deep-scan answers, including a bad-schema one and one with too many posts.
- **Covered:**
  - **schedule:** weekday, weekend, 1 AM spill, peak, hourly pulses, deep-scan times, "nothing outside windows";
  - **matching and labels:** fuzzy matching and Hinglish aliases, growth and the four labels, spike detection and
    cooldown;
  - **budget:** cost maths and the 80% / 100% guard;
  - **API behaviour:** retries and timeouts, schema rejection, enrichment that refuses anything but GET;
  - **app side:** parser and screen state.
- **Mutation check** on the window, budget and read-only rules, as with the unfollow Worker.

## Order of work (small commits)

1. **Measure first:** one real manual pulse and one real deep scan from a script, with `grok-4.7` and with `grok-4.3`.
   This records real posts, tokens, cost and running time, and confirms structured output works with `x_search`.
   **I report the numbers before building the rest.** Costs about $2.
2. The Worker skeleton: D1 schema, config with defaults (Bigg Boss 20 keywords, contestants and hashtags), xAI client,
   schema, validation and cost, with fixture tests.
3. Schedule and budget guard, with tests.
4. Trends: matching, labels, spikes, cooldown, with tests.
5. Enrichment (read-only) and the read, config and run endpoints, with tests and a mutation check.
6. Deploy with the schedule **paused**, check one manual run, then turn the schedule on.
7. App: Room is not needed (the app reads the report live and keeps the last one in memory and a small cache). Then
   the home card, report, cost and settings screens, and the "Draft in Caption Generator" hand-off.
8. README section, install, and a check with you.

## Risks and open points

- **The number of posts per run is not controllable** through the API (see above). The real per-run cost is known
  only after step 1, and the guard works on actual spend.
- **Cloudflare free plan:** each run gets 10 ms of CPU, and long waits for xAI don't count against that. Whether a
  60–120 s deep scan finishes inside a scheduled run is checked in step 1. If it doesn't, the fix is Workers Paid at
  $5 a month, and I'll ask first.
- **Your X spend:** enrichment adds about $0.025 per deep scan to your X bill. It can be switched off.
