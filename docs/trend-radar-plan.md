# Trend Radar: an X research feature (reports and suggestions only), SESSION version

Revised 2026-10-09 (third version). History: first a 24/7 schedule; then manual only (because credits are added now and
then); now a **day session**: nothing runs until you turn it on, and once you do (say in the morning, after adding
credits) it works through that day's window and shuts itself off. It needs something always on while your phone is in
your pocket, so the engine is a Cloudflare Worker again, with a hard switch and a spending limit.

## What it does and never does

- **Does:** reads X through xAI Grok (`x_search`) and produces reports: rising sub-topics, what people are asking,
  posts worth replying to, and ideas for your own posts.
- **Never:** posts, replies, likes or follows. The Worker's only outbound call is to `api.x.ai`.
  OpenAI drafts only when you tap "Draft in Caption Generator" in the app.

## Measured on 2026-10-09 (one real run each)

| Run | Model | Time | Cost | Posts read (target) |
|---|---|---|---|---|
| Pulse | grok-4.7 | 113 s | $0.198 | 24 (20) |
| Pulse | grok-4.3 | 22 s | $0.106 | 18 (20) |
| Deep scan | grok-4.3 | 21 s | $0.307 | 56 (90) |
| Deep scan | grok-4.7 | 143 s | $0.840 | 129 (90) |

- X's per-post charge ($5 per 1,000 posts) is most of every cost. Each response reports its exact cost
  (`usage.cost_in_usd_ticks`), which matched my arithmetic to four decimals.
- The API cannot cap posts or searches, so targets are approximate and the guard uses real numbers.
- Defaults: pulses on `grok-4.3`, deep scans on `grok-4.7` (about $4 a weekday, inside the $6 limit).

## How you use it

1. **Morning, after adding credits:** open Trend Radar, set "Spend at most $X today" (default: your daily limit, or the
   credits you said you added if that is lower), and switch **Day session** on. The screen says "Armed: starts 12:00".
2. **It runs the day by itself**, only inside that day's window:
   - weekdays 12:00 PM to 1:00 AM, weekends 8:00 PM to 1:00 AM (IST; the 1 AM end belongs to the day it started);
   - the first run is a deep scan (if you switch on late it runs one straight away to catch up);
   - a pulse every 60 minutes (bigger during the 9-11 PM peak), more deep scans at about 6 PM and 11 PM on weekdays
     and about 11 PM on weekends;
   - a **spike** (a new or rising sub-topic) queues a focused deep scan, at most 3 a day, 3 hours between scans on the
     same topic.
3. **It stops by itself** at the end of the window, when the session's spending limit is reached, or when you switch
   it off. At 80% of the limit it only does the scheduled deep scans. It never starts the next day on its own.
4. **Anytime:** open the report (rising first), the cost tab, or settings. **Run pulse now** and **Run deep scan now**
   work outside a session too (you confirm the estimated cost first). **See a sample report** is free.

## Architecture

```
Cloudflare cron (every minute) -> Worker "x-auto-trend-radar" -> D1 database
   - decides if this minute needs a run (session on, inside the window, due, budget ok)
   - one run at a time (lock), calls api.x.ai/v1/responses with the x_search tool and a JSON schema
   - stores the run, matches and labels sub-topics, queues spike scans
app <-- HTTPS + token: GET /report, POST /session, /run, /config, /exclude
```

- **Separate Worker and database from the unfollow tracker**, so a bug or a spend problem can never touch it.
- **Cron every minute** so manual runs and scheduled ones start within about a minute, and a run that takes minutes
  (a deep scan on the bigger model took 143 s) is never tied to a phone connection.
- **Secrets in Cloudflare only:** `XAI_API_KEY`, `TREND_APP_TOKEN`. The app gets the Worker's address and the token.
- **Plain `fetch` to xAI's Responses API** (the same OpenAI-compatible API) instead of the OpenAI SDK, to keep the
  Worker's CPU use small: a run that is billed but killed before it is stored would be lost money.
  The raw result and cost are stored FIRST, then analysed.
- **Read-only by construction:** the Worker has no X credentials at all. Its only outbound request is to `api.x.ai`,
  and a test fails if any other host appears in the code.

## The xAI call (checked against docs.x.ai, 2026-10-09)

- Responses API, tool `x_search` only (images and video off), `from_date`/`to_date` whole days (`to_date` exclusive),
  `excluded_x_handles` (max 20), `max_turns` in the body, strict JSON schema output. No engagement fields exist in the
  schema, so likes, replies and views can never be invented.
- No parameter exists for sort order, thread fetching or post count, so those limits are in the instructions and the
  real numbers (`x_posts_fetched`, tokens, cost) are read from every response.
- Timeouts per run type; retry only on 429 and 5xx (never on a timeout: the request may be running and billed).

## Trend logic

- Sub-topics matched across runs by a normalized key (lower-case, no `#` or punctuation, filler words and lone letters
  dropped, Hinglish spellings merged, words sorted; similar keys with 60% word overlap or a one-fifth edit distance
  are the same).
- Share = mentions / all mentions in that run. Baseline = average share over the last 4 runs. Growth =
  (share - baseline) / max(baseline, 0.02).
- **NEW** (first seen within 48 h), **RISING** (growth at least 1.0 and 3+ mentions), **PEAKING** (near its peak and
  flat), **FADING** (40% below its recent peak, or gone from the latest run), else STEADY.

## Cost guard

- Every run is checked at its worst case (1.5x the likely cost) before it starts.
- **Session limit:** you set it when you switch on. 80% -> scheduled deep scans only. A run that would not fit in what
  is left ends the session ("budget used").
- **Daily limit** (default $6) applies to everything, manual runs included.
- **Credits I added** (optional) is subtracted by what the radar itself spent since you entered it.
- It cannot see your real xAI balance. The numbers are the radar's own record of what its runs cost.

## Build order (small commits)

1. Worker: schema, config, schedule (IST windows, peak, slots), parse, analysis, budget, runner, endpoints, tests,
   mutation check.
2. Deploy with the session OFF; one manual run and the free sample as a check.
3. App: report, cost, settings, session switch, manual runs, the Caption Generator hand-off, home card.
4. README, install, a check with you.
