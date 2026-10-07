import { crcResponse, parseEvents, safeEqual, utcDay, verifySignature } from "./logic.js";
import { MODES, getMode, processDueKicks } from "./kicks.js";
import { bump, clearState, getState, nextVersion, setState } from "./store.js";

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });

/** Stops X delivering (and billing) anything more: deletes the subscription and records why. */
async function pause(env, db, reason, fetchImpl = fetch) {
  await setState(db, "paused_reason", reason);
  await setState(db, "paused_at", new Date().toISOString());
  if (env.SUBSCRIPTION_ID && env.BEARER) {
    try {
      const r = await fetchImpl(`https://api.x.com/2/activity/subscriptions/${env.SUBSCRIPTION_ID}`, {
        method: "DELETE",
        headers: { authorization: `Bearer ${env.BEARER}` },
      });
      await setState(db, "subscription_deleted", r.ok ? "yes" : `no (HTTP ${r.status})`);
    } catch (e) {
      await setState(db, "subscription_deleted", `no (${e.message})`);
    }
  } else {
    await setState(db, "subscription_deleted", "no (SUBSCRIPTION_ID or BEARER not set)");
  }
}

/** Records the follow-up for a stored unfollow: a kick row that the scheduled run will look at later. */
async function enqueueKick(env, db, event, now) {
  const mode = await getMode(db);
  const delayMin = Number(env.KICK_DELAY_MIN ?? 30);
  const dueAt = new Date(now.getTime() + delayMin * 60 * 1000).toISOString();
  const off = mode === "off";
  const v = await nextVersion(db);
  await db
    .prepare("INSERT OR IGNORE INTO kicks (event_uuid, user_id, status, detail, due_at, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, ?)")
    .bind(event.event_uuid, event.user_id, off ? "skipped" : "pending", off ? "automation was off" : null, dueAt, now.toISOString(), v)
    .run();
}

export async function handleWebhookPost(request, env, now = new Date(), fetchImpl = fetch) {
  const db = env.DB;
  const raw = new Uint8Array(await request.arrayBuffer());
  const ok = await verifySignature(env.CONSUMER_SECRET, raw, request.headers.get("x-twitter-webhooks-signature"));
  if (!ok) {
    await bump(db, "rejected_signature");
    return new Response("invalid signature", { status: 401 });
  }
  let body;
  try {
    body = JSON.parse(new TextDecoder().decode(raw));
  } catch {
    await bump(db, "rejected_unparseable");
    return new Response("bad json", { status: 400 });
  }

  // Already stopped: take the request, store nothing.
  if (await getState(db, "paused_reason")) return new Response("paused", { status: 200 });

  const { events, problems } = parseEvents(body, env.MAIN_USER_ID);
  const cap = Number(env.DAILY_CAP || 300);
  const dayKey = `count:${utcDay(now)}`;
  let stored = 0;
  for (const e of events) {
    const r = await db
      .prepare("INSERT OR IGNORE INTO events (event_uuid, user_id, username, name, followers_count, following_count, received_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
      .bind(e.event_uuid, e.user_id, e.username, e.name, e.followers_count, e.following_count, now.toISOString())
      .run();
    if (r.meta.changes > 0) {
      stored++;
      await enqueueKick(env, db, e, now);
      await setState(db, "last_event_at", now.toISOString());
      if ((await bump(db, dayKey)) > cap) {
        await pause(env, db, `more than ${cap} unfollow events in one UTC day`, fetchImpl);
        break;
      }
    }
  }
  const wrong = problems.find((p) => p.kind === "wrong_target" || p.kind === "wrong_type");
  if (wrong) {
    await setState(db, "last_problem", `${wrong.kind}: ${wrong.detail}`);
    await pause(env, db, `an event did not match the filter (${wrong.kind}: ${wrong.detail})`, fetchImpl);
  } else if (problems.length) {
    await bump(db, "unrecognised_bodies");
    await setState(db, "last_problem", `${problems[0].kind}: ${problems[0].detail}`);
  }
  return new Response(JSON.stringify({ stored }), { status: 200, headers: { "content-type": "application/json" } });
}

const authorised = (request, env) => {
  const auth = request.headers.get("authorization") || "";
  return Boolean(env.APP_TOKEN) && safeEqual(auth, `Bearer ${env.APP_TOKEN}`);
};

async function statusBlock(env, now) {
  const db = env.DB;
  const total = await db.prepare("SELECT COUNT(*) AS n FROM events").first();
  const pending = await db.prepare("SELECT COUNT(*) AS n FROM kicks WHERE status = 'pending'").first();
  const wl = await db.prepare("SELECT user_id FROM whitelist ORDER BY user_id").all();
  const version = Number((await getState(db, "version")) || 0);
  return {
    total_events: total.n,
    version,
    tracking_since: await getState(db, "tracking_since"),
    last_event_at: await getState(db, "last_event_at"),
    paused_reason: await getState(db, "paused_reason"),
    subscription_deleted: await getState(db, "subscription_deleted"),
    today_count: Number((await getState(db, `count:${utcDay(now)}`)) || 0),
    daily_cap: Number(env.DAILY_CAP || 300),
    rejected_signature: Number((await getState(db, "rejected_signature")) || 0),
    last_problem: await getState(db, "last_problem"),
    server_time: now.toISOString(),
    automation: {
      mode: await getMode(db),
      paused_reason: await getState(db, "auto_paused_reason"),
      group_id: env.GC_GROUP_ID || null,
      delay_min: Number(env.KICK_DELAY_MIN ?? 30),
      kicked_today: Number((await getState(db, `kicked:${utcDay(now)}`)) || 0),
      kick_cap: Number(env.KICK_DAILY_CAP || 20),
      pending: pending.n,
      last_run_at: await getState(db, "last_run_at"),
      whitelist: wl.results.map((r) => r.user_id),
    },
  };
}

/** Everything that is new or changed after `since_version`, oldest change first, with what happened about the group. */
export async function handleRead(request, env, now = new Date()) {
  if (!authorised(request, env)) return new Response("unauthorized", { status: 401 });
  const url = new URL(request.url);
  const since = Math.max(0, parseInt(url.searchParams.get("since_version") || "0", 10) || 0);
  const rows = await env.DB
    .prepare(
      "SELECT e.seq, e.event_uuid, e.user_id, e.username, e.name, e.followers_count, e.following_count, e.received_at, " +
        "k.status AS gc_status, k.detail AS gc_detail, k.due_at, k.updated_at AS gc_updated_at, k.version " +
        "FROM kicks k JOIN events e ON e.event_uuid = k.event_uuid WHERE k.version > ? ORDER BY k.version LIMIT 500",
    )
    .bind(since)
    .all();
  return json({ events: rows.results, status: await statusBlock(env, now) });
}

/** Records when tracking began. Called once by the setup script, after the subscription exists. */
export async function handleMark(request, env, now = new Date()) {
  if (!authorised(request, env)) return new Response("unauthorized", { status: 401 });
  if (!(await getState(env.DB, "tracking_since"))) await setState(env.DB, "tracking_since", now.toISOString());
  return json({ tracking_since: await getState(env.DB, "tracking_since") });
}

/** Switch the automation: off, dry_run or live. Switching never touches rows that were already decided. */
export async function handleSettings(request, env) {
  if (!authorised(request, env)) return new Response("unauthorized", { status: 401 });
  let body;
  try {
    body = await request.json();
  } catch {
    return new Response("bad json", { status: 400 });
  }
  if (!MODES.includes(body && body.mode)) return new Response("mode must be off, dry_run or live", { status: 400 });
  await setState(env.DB, "mode", body.mode);
  return json({ mode: body.mode });
}

/** Clears an automation pause, after a person has looked at why it stopped. */
export async function handleResume(request, env) {
  if (!authorised(request, env)) return new Response("unauthorized", { status: 401 });
  await clearState(env.DB, "auto_paused_reason");
  return json({ resumed: true });
}

/** Never kick this person: add (keep: true) or remove (keep: false) from the whitelist. */
export async function handleWhitelist(request, env) {
  if (!authorised(request, env)) return new Response("unauthorized", { status: 401 });
  let body;
  try {
    body = await request.json();
  } catch {
    return new Response("bad json", { status: 400 });
  }
  const id = String(body && body.user_id);
  if (!/^[0-9]{1,19}$/.test(id)) return new Response("user_id must be a number", { status: 400 });
  if (body.keep === false) await env.DB.prepare("DELETE FROM whitelist WHERE user_id = ?").bind(id).run();
  else await env.DB.prepare("INSERT OR IGNORE INTO whitelist (user_id) VALUES (?)").bind(id).run();
  return json({ user_id: id, keep: body.keep !== false });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/x/webhook") {
      if (request.method === "GET") {
        const t = url.searchParams.get("crc_token");
        if (!t) return new Response("missing crc_token", { status: 400 });
        await setState(env.DB, "last_crc_ok_at", new Date().toISOString());
        return json(await crcResponse(env.CONSUMER_SECRET, t));
      }
      if (request.method === "POST") return handleWebhookPost(request, env);
    }
    if (request.method === "GET" && url.pathname === "/unfollows") return handleRead(request, env);
    if (request.method === "POST") {
      if (url.pathname === "/mark") return handleMark(request, env);
      if (url.pathname === "/settings") return handleSettings(request, env);
      if (url.pathname === "/resume") return handleResume(request, env);
      if (url.pathname === "/whitelist") return handleWhitelist(request, env);
    }
    return new Response("not found", { status: 404 });
  },

  /** Cron: every few minutes, look at the kicks that have come due. */
  async scheduled(_event, env, ctx) {
    ctx.waitUntil(processDueKicks(env));
  },
};
