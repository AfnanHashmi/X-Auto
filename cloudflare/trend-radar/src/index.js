// The Trend Radar Worker: a once-a-minute cron that runs the day session, and a token-protected API for the app.
// It reads X through xAI only. It has no X credentials and never posts, replies, likes or follows.

import { applyPatch, cleanHandles } from "./config.js";
import { labelOrder } from "./analysis.js";
import { creditsLeft } from "./budget.js";
import { executeRun, preview, tick } from "./runner.js";
import { istDate } from "./schedule.js";
import { armSession, endSession, loadSession, phase } from "./session.js";
import { dayStart, getConfig, getJson, getState, monthStart, saveConfig, setJson, spentSince } from "./store.js";

const json = (obj, status = 200) => new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });

/** Constant-time string comparison. */
export function safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const enc = new TextEncoder();
  const x = enc.encode(a);
  const y = enc.encode(b);
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0;
}

export const authorised = (request, env) => {
  const auth = request.headers.get("authorization") || "";
  return Boolean(env.TREND_APP_TOKEN) && safeEqual(auth, `Bearer ${env.TREND_APP_TOKEN}`);
};

const readBody = async (request) => {
  try {
    const b = await request.json();
    return b && typeof b === "object" ? b : null;
  } catch {
    return null;
  }
};

const rowToRun = (r) => ({ ...r, cost_from_xai: Boolean(r.cost_from_xai) });

/** Everything the app shows, in one reply. */
export async function handleReport(env, now) {
  const db = env.DB;
  const cfg = await getConfig(db);
  const s = await loadSession(db);
  const latest = (await db.prepare("SELECT * FROM runs WHERE status IN ('ok', 'dry_run') ORDER BY started_at DESC LIMIT 1").first()) ?? null;
  const snapshots = latest ? (await db.prepare("SELECT * FROM snapshots WHERE run_id = ?").bind(latest.id).all()).results : [];
  const findings = latest ? (await db.prepare("SELECT * FROM findings WHERE run_id = ?").bind(latest.id).all()).results : [];
  const runs = (await db.prepare("SELECT * FROM runs ORDER BY started_at DESC LIMIT 60").all()).results;
  const spentToday = await spentSince(db, dayStart(now));
  const spentSinceCredits = cfg.credits_added_usd == null ? 0 : await spentSince(db, cfg.credits_added_at);
  const jobs = await getJson(db, "jobs", []);
  return {
    server_time: now,
    config: cfg,
    session: {
      phase: phase(s, now),
      on: s.on,
      window_start: s.windowStart || null,
      window_end: s.windowEnd || null,
      budget: s.budget || null,
      spent: s.spent,
      deep_slots: s.deepSlots,
      deep_done: s.deepDone,
      spike_queue: s.spikeQueue,
      ended_reason: s.endedReason,
      ended_at: s.endedAt || null,
      last_look_at: s.lastLookAt || null,
    },
    spend: { today: spentToday, month: await spentSince(db, monthStart(now)), since_credits: spentSinceCredits, credits_left: creditsLeft(cfg.credits_added_usd, spentSinceCredits) },
    job: { queued: jobs, last: await getJson(db, "last_job", null), running: (await db.prepare("SELECT id FROM runs WHERE status = 'running' LIMIT 1").first()) != null },
    latest: latest ? { run: rowToRun(latest), snapshots: [...snapshots].sort((a, b) => labelOrder(a.label) - labelOrder(b.label) || b.growth - a.growth || b.mentions - a.mentions), findings } : null,
    runs: runs.map(rowToRun),
  };
}

export async function handlePreview(request, env, now) {
  const url = new URL(request.url);
  const type = url.searchParams.get("type") === "deep" ? "DEEP" : "PULSE";
  const posts = Number(url.searchParams.get("posts")) || null;
  return json(await preview(env, type, posts, now));
}

/** Switch the day session on or off. On: arms the open (or next) window with a spending limit. */
export async function handleSession(request, env, now) {
  const body = await readBody(request);
  if (!body || typeof body.on !== "boolean") return json({ error: "send {\"on\": true|false}" }, 400);
  const db = env.DB;
  if (!body.on) {
    await endSession(db, "switched off", now);
    return json({ on: false });
  }
  const r = await armSession(db, await getConfig(db), now, body.budget_usd ?? null);
  if (!r.ok) return json({ error: r.reason }, 409);
  return json({ on: true, window_start: r.window.start, window_end: r.window.end, budget: r.budget });
}

/** A run now. Free sample runs happen at once; real runs are queued and picked up by the next minute's tick. */
export async function handleRun(request, env, now, deps = {}) {
  const body = await readBody(request);
  if (!body || !["pulse", "deep"].includes(body.type)) return json({ error: "type must be pulse or deep" }, 400);
  const type = body.type.toUpperCase();
  const focus = typeof body.focus === "string" && body.focus.trim() ? body.focus.trim().slice(0, 120) : null;
  if (body.dry === true) {
    const r = await executeRun(env, { type, trigger: "dry_run", dry: true }, now, deps);
    return json(r.ok ? { ok: true, run_id: r.runId } : { ok: false, error: r.failed ?? r.refused }, r.ok ? 200 : 500);
  }
  const posts = Number.isInteger(body.posts) ? body.posts : null;
  const pv = await preview(env, type, posts, now);
  if (!env.XAI_API_KEY) return json({ error: "The xAI key is not set on the Worker." }, 409);
  if (!pv.guard.ok) return json({ error: pv.guard.reason }, 409);
  const jobs = await getJson(env.DB, "jobs", []);
  if (jobs.length || (await env.DB.prepare("SELECT id FROM runs WHERE status = 'running' LIMIT 1").first())) {
    return json({ error: "A run is already queued or in progress. Wait for it to finish." }, 409);
  }
  await setJson(env.DB, "jobs", [{ type, posts, focus, at: now }]);
  return json({ queued: true, likely: pv.likely, worst: pv.worst });
}

export async function handleConfig(request, env, now) {
  const body = await readBody(request);
  const r = applyPatch(await getConfig(env.DB), body, now);
  if (r.error) return json({ error: r.error }, 400);
  await saveConfig(env.DB, r.config);
  return json({ config: r.config });
}

/** Add one account to the ignore list (spam, bots). */
export async function handleExclude(request, env, now) {
  const body = await readBody(request);
  const cfg = await getConfig(env.DB);
  const clean = cleanHandles([body?.handle]);
  if (!clean.length) return json({ error: "that is not a valid handle" }, 400);
  if (cfg.excluded_x_handles.some((h) => h.toLowerCase() === clean[0].toLowerCase())) return json({ excluded: cfg.excluded_x_handles });
  if (cfg.excluded_x_handles.length >= 20) return json({ error: "The ignore list is full (X allows 20). Remove one in Settings first." }, 409);
  const r = applyPatch(cfg, { excluded_x_handles: [...cfg.excluded_x_handles, clean[0]] }, now);
  await saveConfig(env.DB, r.config);
  return json({ excluded: r.config.excluded_x_handles });
}

export default {
  async fetch(request, env) {
    if (!authorised(request, env)) return new Response("unauthorized", { status: 401 });
    const url = new URL(request.url);
    const now = Date.now();
    if (request.method === "GET") {
      if (url.pathname === "/report") return json(await handleReport(env, now));
      if (url.pathname === "/preview") return handlePreview(request, env, now);
    }
    if (request.method === "POST") {
      if (url.pathname === "/session") return handleSession(request, env, now);
      if (url.pathname === "/run") return handleRun(request, env, now);
      if (url.pathname === "/config") return handleConfig(request, env, now);
      if (url.pathname === "/exclude") return handleExclude(request, env, now);
    }
    return new Response("not found", { status: 404 });
  },

  /** Cron, every minute: runs the day session (if it is on) and any run you asked for. */
  async scheduled(_event, env, ctx) {
    ctx.waitUntil(tick(env, Date.now()));
  },
};
