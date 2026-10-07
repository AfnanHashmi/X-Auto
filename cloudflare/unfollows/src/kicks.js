// The automation: some time after someone unfollows the main account, if they are in the group chat and still do not
// follow, RW removes them. Every rule fails toward NOT removing anyone.
//
//   protected (you, RW, DK, a group admin, the whitelist)      -> never touched
//   not in the group                                            -> nothing to do (costs nothing)
//   follows main again                                          -> kept
//   cannot be verified (error, account gone)                    -> kept
//   dry run                                                     -> only recorded as "would kick"
//   live                                                        -> removed, one at a time, stopping at the first surprise

import { authorizationHeader, queryString } from "./oauth1.js";
import { utcDay } from "./logic.js";
import { bump, clearState, getState, nextVersion, setState } from "./store.js";

const API = "https://api.x.com/2";
const NUMERIC_ID = /^[0-9]{1,19}$/;
const LOCK_MS = 4 * 60 * 1000;
const MAX_PER_RUN = 10;

export const MODES = ["off", "dry_run", "live"];

export const rwCreds = (env) => ({
  consumerKey: env.X_RW_CONSUMER_KEY,
  consumerSecret: env.X_RW_CONSUMER_SECRET,
  token: env.X_RW_ACCESS_TOKEN,
  tokenSecret: env.X_RW_ACCESS_SECRET,
});

export const mainCreds = (env) => ({
  consumerKey: env.X_MAIN_CONSUMER_KEY,
  consumerSecret: env.CONSUMER_SECRET,
  token: env.X_MAIN_ACCESS_TOKEN,
  tokenSecret: env.X_MAIN_ACCESS_SECRET,
});

/** A signed call to X. Never throws: a missing reply comes back as {noReply: true}. */
export async function xcall(fetchImpl, creds, method, path, params = {}, body) {
  const base = API + path;
  const headers = { authorization: await authorizationHeader(method, base, params, creds) };
  const init = { method, headers };
  if (body !== undefined) {
    headers["content-type"] = "application/json";
    init.body = JSON.stringify(body);
  }
  const qs = queryString(params);
  let res;
  try {
    res = await fetchImpl(qs ? `${base}?${qs}` : base, init);
  } catch (e) {
    return { noReply: true, error: String(e && e.message) };
  }
  let json = null;
  try {
    json = await res.json();
  } catch {
    json = null;
  }
  return { status: res.status, json };
}

/** The group, only if X's answer is exactly what was asked for and complete. */
export function parseGroup(reply, groupId) {
  const j = reply && reply.json;
  if (!j || reply.status !== 200 || (Array.isArray(j.errors) && j.errors.length)) return null;
  const d = j.data;
  if (!d || d.id !== groupId || d.type !== "group") return null;
  const ok = (a) => Array.isArray(a) && a.every((x) => NUMERIC_ID.test(String(x)));
  if (!ok(d.member_ids) || !ok(d.admin_ids)) return null;
  return { members: new Set(d.member_ids.map(String)), admins: new Set(d.admin_ids.map(String)) };
}

/** "follows" | "not_following" | {unverified: reason}. Only a positive, complete answer about THIS account counts. */
export function parseFollowCheck(reply, userId) {
  if (reply.noReply) return { unverified: "no reply from X" };
  if (reply.status === 429) return { rateLimited: true };
  const j = reply.json;
  if (reply.status !== 200 || !j) return { unverified: `HTTP ${reply.status}` };
  if (Array.isArray(j.errors) && j.errors.length) return { unverified: "X reported an error for this account" };
  if (!j.data || j.data.id !== userId) return { unverified: "X returned no such account" };
  return Array.isArray(j.data.connection_status) && j.data.connection_status.includes("followed_by") ? "follows" : "not_following";
}

export async function setKick(db, uuid, status, detail, now) {
  const v = await nextVersion(db);
  await db
    .prepare("UPDATE kicks SET status = ?, detail = ?, updated_at = ?, version = ? WHERE event_uuid = ?")
    .bind(status, detail ?? null, now.toISOString(), v, uuid)
    .run();
}

export async function pauseAutomation(db, reason) {
  await setState(db, "auto_paused_reason", reason);
}

export async function getMode(db) {
  const m = await getState(db, "mode");
  return MODES.includes(m) ? m : "dry_run";
}

/**
 * One pass over the kicks that are due. Returns a short summary for logs and tests.
 * [deps] lets tests replace the network and the clock: {fetch, sleep}.
 */
export async function processDueKicks(env, now = new Date(), deps = {}) {
  const db = env.DB;
  const fetchImpl = deps.fetch || fetch;
  const sleep = deps.sleep || ((ms) => new Promise((r) => setTimeout(r, ms)));
  const delayMs = Number(env.KICK_SPACING_MS ?? 3000);
  const cap = Number(env.KICK_DAILY_CAP || 20);
  const summary = { ran: false, note: null, results: [] };

  const mode = await getMode(db);
  if (mode === "off") return { ...summary, note: "automation is off" };
  if (await getState(db, "auto_paused_reason")) return { ...summary, note: "automation is paused" };
  if (await getState(db, "paused_reason")) return { ...summary, note: "tracking is paused" };

  const lock = Number((await getState(db, "run_lock")) || 0);
  if (lock && now.getTime() - lock < LOCK_MS) return { ...summary, note: "another run is in progress" };

  const due = (
    await db
      .prepare(
        "SELECT event_uuid, user_id FROM kicks WHERE (status = 'pending' AND due_at <= ?) OR status = 'in_flight' ORDER BY due_at LIMIT ?",
      )
      .bind(now.toISOString(), MAX_PER_RUN)
      .all()
  ).results;
  await setState(db, "last_run_at", now.toISOString());
  if (due.length === 0) return { ...summary, ran: true, note: "nothing due" };

  await setState(db, "run_lock", now.getTime());
  try {
    summary.ran = true;
    // One read of the group serves the whole batch.
    const group = parseGroup(await xcall(fetchImpl, rwCreds(env), "GET", `/chat/conversations/${env.GC_GROUP_ID}`, {
      expansions: "member_ids,admin_ids",
      "chat_conversation.fields": "type",
    }), env.GC_GROUP_ID);
    if (!group) {
      await setState(db, "last_problem", "could not read the group; will retry");
      return { ...summary, note: "could not read the group" };
    }
    if (!group.members.has(env.RW_USER_ID) || !group.admins.has(env.RW_USER_ID)) {
      await pauseAutomation(db, "RW is no longer an admin of the group");
      return { ...summary, note: "RW is not an admin" };
    }

    const whitelist = new Set((await db.prepare("SELECT user_id FROM whitelist").all()).results.map((r) => r.user_id));
    const own = new Set([env.MAIN_USER_ID, env.RW_USER_ID, env.DK_USER_ID].filter(Boolean));
    const isProtected = (id) => own.has(id) || group.admins.has(id) || whitelist.has(id);
    const kickedKey = `kicked:${utcDay(now)}`;
    let first = true;

    for (const row of due) {
      const { event_uuid: uuid, user_id: uid } = row;
      const current = (await db.prepare("SELECT status FROM kicks WHERE event_uuid = ?").bind(uuid).first())?.status;

      // A removal that was started but never confirmed: the group says what happened.
      if (current === "in_flight") {
        if (group.members.has(uid)) {
          await setKick(db, uuid, "failed", "a removal was started but the person is still in the group", now);
        } else {
          await setKick(db, uuid, "kicked", "the group no longer lists them (settled after an interrupted run)", now);
        }
        await pauseAutomation(db, "a removal was interrupted; check the audit, then resume");
        summary.results.push({ uuid, status: "settled" });
        return { ...summary, note: "settled an interrupted removal" };
      }

      if (isProtected(uid)) {
        await setKick(db, uuid, "protected", "you, an admin or on the whitelist", now);
        summary.results.push({ uuid, status: "protected" });
        continue;
      }
      if (!group.members.has(uid)) {
        await setKick(db, uuid, "not_in_gc", null, now);
        summary.results.push({ uuid, status: "not_in_gc" });
        continue;
      }

      const check = parseFollowCheck(await xcall(fetchImpl, mainCreds(env), "GET", `/users/${uid}`, { "user.fields": "connection_status" }), uid);
      if (check.rateLimited) {
        await setState(db, "last_problem", "rate limited while re-checking; will retry");
        return { ...summary, note: "rate limited" };
      }
      if (check === "follows") {
        await setKick(db, uuid, "refollowed", "follows again, so kept", now);
        summary.results.push({ uuid, status: "refollowed" });
        continue;
      }
      if (check !== "not_following") {
        await setKick(db, uuid, "unverified", check.unverified, now);
        summary.results.push({ uuid, status: "unverified" });
        continue;
      }

      if (mode === "dry_run") {
        await setKick(db, uuid, "would_kick", "dry run: would be removed", now);
        summary.results.push({ uuid, status: "would_kick" });
        continue;
      }

      // ---- live ----
      if (Number((await getState(db, kickedKey)) || 0) >= cap) {
        await pauseAutomation(db, `the daily limit of ${cap} removals was reached`);
        return { ...summary, note: "daily cap reached" };
      }
      if (uid === env.RW_USER_ID) throw new Error("refusing to remove the remover");
      if (!first) await sleep(delayMs);
      first = false;
      await setKick(db, uuid, "in_flight", "removal request sent", now);
      const r = await xcall(fetchImpl, rwCreds(env), "POST", `/chat/conversations/${env.GC_GROUP_ID}/members/remove`, {}, { user_ids: [uid] });
      if (r.status === 200 && r.json && !(r.json.errors && r.json.errors.length) && r.json.data && r.json.data.removed === true) {
        await bump(db, kickedKey);
        await setKick(db, uuid, "kicked", "removed from the group", now);
        summary.results.push({ uuid, status: "kicked" });
        continue;
      }
      if (r.noReply) {
        const again = parseGroup(await xcall(fetchImpl, rwCreds(env), "GET", `/chat/conversations/${env.GC_GROUP_ID}`, {
          expansions: "member_ids,admin_ids",
          "chat_conversation.fields": "type",
        }), env.GC_GROUP_ID);
        if (again && !again.members.has(uid)) {
          await bump(db, kickedKey);
          await setKick(db, uuid, "kicked", "no reply from X, but the group no longer lists them", now);
        } else {
          await setKick(db, uuid, "failed", "no reply from X and the removal could not be confirmed", now);
        }
      } else {
        const why = (r.json && r.json.errors && r.json.errors[0] && (r.json.errors[0].detail || r.json.errors[0].title)) || "X did not confirm the removal";
        await setKick(db, uuid, "failed", `HTTP ${r.status}: ${why}`, now);
      }
      await pauseAutomation(db, "a removal failed or could not be confirmed; stopped at the first problem");
      summary.results.push({ uuid, status: "failed" });
      return { ...summary, note: "stopped at the first failure" };
    }
    return summary;
  } finally {
    await clearState(db, "run_lock");
  }
}
