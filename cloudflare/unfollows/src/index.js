import { crcResponse, parseEvents, safeEqual, utcDay, verifySignature } from "./logic.js";

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });

async function getState(db, key) {
  const row = await db.prepare("SELECT value FROM state WHERE key = ?").bind(key).first();
  return row ? row.value : null;
}

async function setState(db, key, value) {
  await db.prepare("INSERT INTO state(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").bind(key, String(value)).run();
}

async function bump(db, key) {
  await db.prepare("INSERT INTO state(key, value) VALUES(?, '1') ON CONFLICT(key) DO UPDATE SET value = CAST(value AS INTEGER) + 1").bind(key).run();
  return Number(await getState(db, key));
}

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

export async function handleRead(request, env, now = new Date()) {
  const auth = request.headers.get("authorization") || "";
  if (!env.APP_TOKEN || !safeEqual(auth, `Bearer ${env.APP_TOKEN}`)) return new Response("unauthorized", { status: 401 });
  const db = env.DB;
  const url = new URL(request.url);
  const after = Math.max(0, parseInt(url.searchParams.get("after") || "0", 10) || 0);
  const rows = await db
    .prepare("SELECT seq, event_uuid, user_id, username, name, followers_count, following_count, received_at FROM events WHERE seq > ? ORDER BY seq LIMIT 500")
    .bind(after)
    .all();
  const total = await db.prepare("SELECT COUNT(*) AS n FROM events").first();
  return json({
    events: rows.results,
    status: {
      total_events: total.n,
      tracking_since: await getState(db, "tracking_since"),
      last_event_at: await getState(db, "last_event_at"),
      paused_reason: await getState(db, "paused_reason"),
      subscription_deleted: await getState(db, "subscription_deleted"),
      today_count: Number((await getState(db, `count:${utcDay(now)}`)) || 0),
      daily_cap: Number(env.DAILY_CAP || 300),
      rejected_signature: Number((await getState(db, "rejected_signature")) || 0),
      last_problem: await getState(db, "last_problem"),
      server_time: now.toISOString(),
    },
  });
}

/** Records when tracking began. Called once by the setup script, after the subscription exists. */
export async function handleMark(request, env, now = new Date()) {
  const auth = request.headers.get("authorization") || "";
  if (!env.APP_TOKEN || !safeEqual(auth, `Bearer ${env.APP_TOKEN}`)) return new Response("unauthorized", { status: 401 });
  if (!(await getState(env.DB, "tracking_since"))) await setState(env.DB, "tracking_since", now.toISOString());
  return json({ tracking_since: await getState(env.DB, "tracking_since") });
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
    if (url.pathname === "/unfollows" && request.method === "GET") return handleRead(request, env);
    if (url.pathname === "/mark" && request.method === "POST") return handleMark(request, env);
    return new Response("not found", { status: 404 });
  },
};
