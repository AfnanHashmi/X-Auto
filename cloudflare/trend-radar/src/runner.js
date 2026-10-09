// Doing the work: one run (a pulse or a deep scan) and the once-a-minute tick that decides whether to start one.
// Nothing here posts, replies, likes or follows: the only outbound call is to xAI (see xai.js).

import { BASELINE_RUNS, analyse, isSpike } from "./analysis.js";
import { check } from "./budget.js";
import { matchKey, mergeByKey } from "./match.js";
import { ParseError, answerText, parseJson, parseReport, usageOf } from "./parse.js";
import { costOf, estimate, worstCase } from "./pricing.js";
import { deepInstructions, pulseInstructions } from "./prompts.js";
import { DEEP_SCHEMA, PULSE_SCHEMA } from "./schemas.js";
import { DEEP_SAMPLE, PULSE_SAMPLE } from "./samples.js";
import { addDays, decide, istDate } from "./schedule.js";
import { endSession, loadSession } from "./session.js";
import { XaiError, callXai } from "./xai.js";
import { clearState, dayStart, getConfig, getJson, getState, knownKeys, pastRuns, recentCosts, setJson, setState, spentSince } from "./store.js";

const LOCK_TTL_MS = 15 * 60 * 1000;
const INTERRUPTED_AFTER_MS = 15 * 60 * 1000;

const modelFor = (cfg, type) => (type === "PULSE" ? cfg.pulse_model : cfg.deep_model);

/** What a run would cost and whether the guard lets it start. [session] = {budget, spent} for scheduled and spike runs. */
export async function preview(env, type, posts, now, session = null) {
  const db = env.DB;
  const cfg = await getConfig(db);
  const model = modelFor(cfg, type);
  const n = posts ?? (type === "PULSE" ? cfg.pulse.posts : cfg.deep.posts);
  const likely = estimate(type, model, n, await recentCosts(db, type, model, 5), cfg.pulse.posts);
  const worst = worstCase(likely, cfg.worst_case_factor);
  const spentToday = await spentSince(db, dayStart(now));
  const spentSinceCredits = cfg.credits_added_usd == null ? 0 : await spentSince(db, cfg.credits_added_at);
  const guard = check({
    worst, spentToday, dailyCap: cfg.daily_cap_usd, creditsAdded: cfg.credits_added_usd, spentSinceCredits,
    sessionBudget: session ? session.budget : null, sessionSpent: session ? session.spent : 0,
  });
  return { type, model, posts: n, likely, worst, guard, spent_today: spentToday, credits_left: guard.ok ? guard.creditsLeft : null };
}

async function addRunCost(db, runId, usage, model, prices) {
  const c = costOf(usage, model, prices);
  const tool = usage.server_side_tool_usage_details || {};
  await db
    .prepare(
      "UPDATE runs SET posts_fetched = ?, users_fetched = ?, search_calls = ?, input_tokens = ?, cached_tokens = ?, output_tokens = ?, reasoning_tokens = ?, cost_usd = ?, cost_from_xai = ? WHERE id = ?",
    )
    .bind(tool.x_posts_fetched ?? 0, tool.x_users_fetched ?? 0, tool.x_search_calls ?? 0, usage.input_tokens ?? 0, usage.input_tokens_details?.cached_tokens ?? 0, usage.output_tokens ?? 0, usage.output_tokens_details?.reasoning_tokens ?? 0, c.total, c.from_xai ? 1 : 0, runId)
    .run();
  return c;
}

async function failRun(db, runId, error, now) {
  await db.prepare("UPDATE runs SET status = 'failed', error = ?, finished_at = ? WHERE id = ?").bind(error, now, runId).run();
}

/**
 * One run. [spec] = {type: "PULSE"|"DEEP", trigger, posts?, focus?, dry?, session?: {id, budget, spent}}.
 * Returns {ok: true, runId, costUsd, rows} | {ok: false, refused, kind} | {ok: false, failed, runId}.
 */
export async function executeRun(env, spec, now, deps = {}) {
  const db = env.DB;
  const cfg = await getConfig(db);
  const type = spec.type;
  const model = modelFor(cfg, type);
  const s = type === "PULSE" ? cfg.pulse : cfg.deep;
  const dry = Boolean(spec.dry);
  const pv = await preview(env, type, spec.posts ?? null, now, spec.session ?? null);
  if (!dry) {
    if (!env.XAI_API_KEY) return { ok: false, refused: "The xAI key is not set on the Worker.", kind: "key" };
    if (!pv.guard.ok) return { ok: false, refused: pv.guard.reason, kind: pv.guard.kind };
  }

  const ins = await db
    .prepare("INSERT INTO runs (type, trigger, model, started_at, status, focus, session_id, target_posts, days_back, max_turns) VALUES (?, ?, ?, ?, 'running', ?, ?, ?, ?, ?)")
    .bind(type, dry ? "dry_run" : spec.trigger, model, now, spec.focus ?? null, spec.session?.id ?? null, pv.posts, s.days_back, s.max_turns)
    .run();
  const runId = ins.meta.last_row_id;

  let body;
  if (dry) {
    body = type === "PULSE" ? PULSE_SAMPLE : DEEP_SAMPLE;
  } else {
    const today = istDate(now).date;
    const topic = cfg.topics[0];
    try {
      body = await callXai(
        env.XAI_API_KEY,
        {
          model,
          instructions: (type === "PULSE" ? pulseInstructions : deepInstructions)(topic, { posts: pv.posts, focus: spec.focus }),
          input: `Run the ${type.toLowerCase()} for "${topic.name}" now.`,
          fromDate: addDays(today, -s.days_back),
          toDate: addDays(today, 1),
          excludedHandles: cfg.excluded_x_handles,
          schemaName: `trend_${type.toLowerCase()}`,
          schema: type === "PULSE" ? PULSE_SCHEMA : DEEP_SCHEMA,
          maxTurns: s.max_turns,
          timeoutSec: cfg.timeouts_sec[type === "PULSE" ? "pulse" : "deep"],
        },
        deps,
      );
    } catch (e) {
      const message = e instanceof XaiError ? e.message : `The request failed: ${e?.message}`;
      await failRun(db, runId, message, now);
      return { ok: false, failed: message, runId };
    }
  }

  // The cost is recorded FIRST, before the answer is judged, because the run is billed whether or not it is usable.
  let root;
  let cost;
  try {
    root = parseJson(body);
    cost = await addRunCost(db, runId, usageOf(root), model, cfg.prices);
  } catch (e) {
    await failRun(db, runId, e.message, now);
    return { ok: false, failed: e.message, runId };
  }
  let result;
  try {
    if (root.error) throw new ParseError(`xAI reported an error: ${root.error.message ?? "unknown"}`);
    if (root.status !== "completed") throw new ParseError(`The run did not finish (status '${root.status}')`);
    result = parseReport(answerText(root), type);
  } catch (e) {
    await failRun(db, runId, e.message, now);
    return { ok: false, failed: e.message, runId };
  }

  // Match to earlier runs, then label by comparing with them.
  const merged = mergeByKey(result.subtopics, await knownKeys(db));
  const rows = analyse(merged, await pastRuns(db, now, BASELINE_RUNS + 2), now);
  const byKey = new Map(merged);
  for (const row of rows) {
    const t = byKey.get(row.key);
    await db
      .prepare("INSERT INTO snapshots (run_id, key, name, mentions, share, sentiment, keywords, example_urls, label, growth, baseline) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
      .bind(runId, row.key, row.name, row.mentions, row.share, t?.sentiment ?? "neutral", (t?.keywords ?? []).join("|"), (t?.example_urls ?? []).join("\n"), row.label, row.growth, row.baseline)
      .run();
  }
  const keys = merged.map(([k]) => k);
  const findings = [
    ...result.questions.map((text) => ["question", text, null, null]),
    ...result.reply_targets.map((r) => ["reply", r.why, r.url, null]),
    ...result.angles.map((a) => ["angle", a.idea, null, matchKey(a.subtopic_key, keys)]),
  ];
  for (const [kind, text, url, extra] of findings) {
    await db.prepare("INSERT INTO findings (run_id, kind, text, url, extra) VALUES (?, ?, ?, ?, ?)").bind(runId, kind, text, url, extra).run();
  }
  // A dry run keeps its (free) report to look at but costs nothing and never feeds trends.
  await db
    .prepare("UPDATE runs SET status = ?, finished_at = ?, sample_size = ?, dropped_urls = ?, cost_usd = ? WHERE id = ?")
    .bind(dry ? "dry_run" : "ok", now, result.sample_size, result.dropped_urls, dry ? 0 : cost.total, runId)
    .run();
  return { ok: true, runId, costUsd: dry ? 0 : cost.total, postsFetched: root.usage?.server_side_tool_usage_details?.x_posts_fetched ?? 0, rows };
}

/** Queues a focused deep scan for each new or rising sub-topic, within the cooldown and the daily limit. */
export async function detectSpikes(db, cfg, rows, now) {
  const day = istDate(now).date;
  const counter = await getJson(db, "spikes_today", { day, n: 0 });
  const today = counter.day === day ? counter : { day, n: 0 };
  const cooldown = await getJson(db, "spike_cooldown", {});
  const queue = await getJson(db, "spike_queue", []);
  const queued = [];
  for (const row of rows) {
    if (!isSpike(row) || row.mentions < cfg.spike.min_mentions) continue;
    if (today.n >= cfg.spike.max_per_day) break;
    if (cooldown[row.key] && now - cooldown[row.key] < cfg.spike.cooldown_hours * 3600000) continue;
    if (queue.includes(row.name)) continue;
    queue.push(row.name);
    cooldown[row.key] = now;
    today.n++;
    queued.push(row.name);
  }
  if (queued.length) {
    await setJson(db, "spike_queue", queue);
    await setJson(db, "spike_cooldown", cooldown);
  }
  await setJson(db, "spikes_today", today);
  return queued;
}

/** A run that was started but never finished (the Worker was stopped mid-run) may still have been billed. */
async function settleInterrupted(db, now) {
  await db
    .prepare("UPDATE runs SET status = 'failed', error = 'Interrupted: the run may still have been carried out and billed.', finished_at = ? WHERE status = 'running' AND started_at < ?")
    .bind(now, now - INTERRUPTED_AFTER_MS)
    .run();
}

/** Called every minute. Does at most one run. Returns a short summary for logs and tests. */
export async function tick(env, now, deps = {}) {
  const db = env.DB;
  const lock = Number((await getState(db, "run_lock")) || 0);
  if (lock && now - lock < LOCK_TTL_MS) return { note: "a run is already in progress" };
  await settleInterrupted(db, now);
  await setState(db, "run_lock", now);
  try {
    // 1. A run you asked for from the app.
    const jobs = await getJson(db, "jobs", []);
    if (jobs.length) {
      const [job, ...rest] = jobs;
      await setJson(db, "jobs", rest);
      const r = await executeRun(env, { type: job.type, trigger: "manual", posts: job.posts, focus: job.focus }, now, deps);
      await setJson(db, "last_job", { at: now, type: job.type, ok: r.ok, reason: r.refused ?? r.failed ?? null, run_id: r.runId ?? null });
      return { note: "manual run", ok: r.ok };
    }

    // 2. The day session, if one is on.
    const cfg = await getConfig(db);
    const s = await loadSession(db);
    const d = decide(now, s, cfg);
    if (d.action === "end") {
      await endSession(db, d.reason, now);
      return { note: "session ended", reason: d.reason };
    }
    if (d.action === "none") return { note: d.reason };

    const spec = {
      type: d.action === "deep" ? "DEEP" : "PULSE",
      trigger: d.trigger,
      posts: d.posts,
      focus: d.focus,
      session: { id: s.id, budget: s.budget, spent: s.spent },
    };
    const r = await executeRun(env, spec, now, deps);

    // Whatever happened, this counts as a look: a failure is never retried every minute (each try is billed).
    await setState(db, "session_last_look", now);
    if (d.action === "deep" && d.trigger === "scheduled") await setJson(db, "session_deep_done", [...s.deepDone, ...d.slots]);
    if (d.trigger === "spike") await setJson(db, "spike_queue", s.spikeQueue.slice(1));

    if (r.refused) {
      const why = { session: "the session's spending limit was reached", daily: "today's spending limit was reached", credits: "the credits you added are used up", key: "the xAI key is missing" }[r.kind] ?? r.refused;
      await endSession(db, why, now);
      return { note: "session ended", reason: why };
    }
    if (!r.ok) {
      const streak = s.failStreak + 1;
      await setState(db, "session_fail_streak", streak);
      if (streak >= 2) {
        await endSession(db, "two runs in a row failed, so it stopped to protect your credits", now);
        return { note: "session ended", reason: "runs kept failing" };
      }
      return { note: "run failed", error: r.failed };
    }
    await setState(db, "session_fail_streak", 0);
    const queued = await detectSpikes(db, cfg, r.rows, now);
    return { note: d.reason, runId: r.runId, queued };
  } finally {
    await clearState(db, "run_lock");
  }
}
