import test from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import worker, { handleMark, handleRead, handleResume, handleSettings, handleWebhookPost, handleWhitelist } from "../src/index.js";
import { MAIN, unfollowEvent } from "./fixture.js";
import { APP_TOKEN, NOW, SECRET, authed, count, kickRow, post, setup, sig, signed, state } from "./helpers.js";

test("a signed unfollow is stored once, however many times X delivers it", async () => {
  const { DB, env, fetchImpl } = setup();
  for (let i = 0; i < 3; i++) assert.equal((await handleWebhookPost(signed(unfollowEvent()), env, NOW, fetchImpl)).status, 200);
  assert.equal(count(DB), 1);
  assert.equal(DB.raw.prepare("SELECT COUNT(*) AS n FROM kicks").get().n, 1);
  assert.equal(state(DB, "count:2026-10-07"), "1");
  assert.equal(state(DB, "last_event_at"), NOW.toISOString());
});

test("an unsigned, wrongly signed or tampered POST is rejected and stores nothing", async () => {
  const { DB, env, fetchImpl } = setup();
  const body = JSON.stringify(unfollowEvent());
  assert.equal((await handleWebhookPost(post(body), env, NOW, fetchImpl)).status, 401);
  assert.equal((await handleWebhookPost(post(body, { "x-twitter-webhooks-signature": "sha256=AAAA" }), env, NOW, fetchImpl)).status, 401);
  assert.equal((await handleWebhookPost(post(body + " ", { "x-twitter-webhooks-signature": sig(body) }), env, NOW, fetchImpl)).status, 401);
  assert.equal(count(DB), 0);
  assert.equal(DB.raw.prepare("SELECT COUNT(*) AS n FROM kicks").get().n, 0);
  assert.equal(state(DB, "rejected_signature"), "3");
});

test("a correctly signed body that is not JSON is refused", async () => {
  const { env, fetchImpl } = setup();
  const body = "not json";
  assert.equal((await handleWebhookPost(post(body, { "x-twitter-webhooks-signature": sig(body) }), env, NOW, fetchImpl)).status, 400);
});

test("an event that does not match the filter pauses tracking and deletes the subscription", async () => {
  const { DB, env, calls, fetchImpl } = setup();
  await handleWebhookPost(signed(unfollowEvent({ targetId: "999" })), env, NOW, fetchImpl);
  assert.equal(count(DB), 0);
  assert.match(state(DB, "paused_reason"), /did not match the filter/);
  assert.deepEqual(calls, [{ url: "https://api.x.com/2/activity/subscriptions/sub1", method: "DELETE" }]);
  assert.equal(state(DB, "subscription_deleted"), "yes");
});

test("once paused, further events are accepted but not stored", async () => {
  const { DB, env, fetchImpl } = setup();
  await handleWebhookPost(signed(unfollowEvent({ targetId: "999" })), env, NOW, fetchImpl);
  const r = await handleWebhookPost(signed(unfollowEvent({ uuid: "later" })), env, NOW, fetchImpl);
  assert.equal(r.status, 200);
  assert.equal(count(DB), 0);
});

test("the daily cap pauses tracking and deletes the subscription", async () => {
  const { DB, env, calls, fetchImpl } = setup({ DAILY_CAP: "2" });
  for (const u of ["a", "b", "c", "d"]) await handleWebhookPost(signed(unfollowEvent({ uuid: u, sourceId: String(100 + u.charCodeAt(0)) })), env, NOW, fetchImpl);
  assert.equal(count(DB), 3); // the third trips the cap, the fourth is ignored
  assert.match(state(DB, "paused_reason"), /more than 2 unfollow events/);
  assert.equal(calls.length, 1);
});

test("the cap counts per UTC day", async () => {
  const { DB, env, fetchImpl } = setup({ DAILY_CAP: "2" });
  await handleWebhookPost(signed(unfollowEvent({ uuid: "a" })), env, new Date("2026-10-07T23:00:00Z"), fetchImpl);
  await handleWebhookPost(signed(unfollowEvent({ uuid: "b" })), env, new Date("2026-10-07T23:30:00Z"), fetchImpl);
  await handleWebhookPost(signed(unfollowEvent({ uuid: "c" })), env, new Date("2026-10-08T00:30:00Z"), fetchImpl);
  assert.equal(count(DB), 3);
  assert.equal(state(DB, "paused_reason"), null);
});

test("a failing kill-switch call is recorded, not hidden", async () => {
  const { DB, env } = setup();
  const failing = async () => ({ ok: false, status: 401 });
  await handleWebhookPost(signed(unfollowEvent({ type: "follow.follow" })), env, NOW, failing);
  assert.equal(state(DB, "subscription_deleted"), "no (HTTP 401)");
});

test("an unrecognised body is counted but does not pause tracking", async () => {
  const { DB, env, fetchImpl } = setup();
  await handleWebhookPost(signed({ hello: "world" }), env, NOW, fetchImpl);
  assert.equal(state(DB, "paused_reason"), null);
  assert.equal(state(DB, "unrecognised_bodies"), "1");
});

// ---- the follow-up row ----------------------------------------------------------------------------------
test("each new unfollow gets a pending kick due after the configured delay", async () => {
  const { DB, env, fetchImpl } = setup();
  await handleWebhookPost(signed(unfollowEvent({ uuid: "u1" })), env, NOW, fetchImpl);
  const k = kickRow(DB, "u1");
  assert.equal(k.status, "pending");
  assert.equal(k.due_at, new Date(NOW.getTime() + 30 * 60 * 1000).toISOString());
  assert.equal(k.user_id, "1111111111111111111");
});

test("a re-delivered event does not create or reset a kick", async () => {
  const { DB, env, fetchImpl } = setup();
  await handleWebhookPost(signed(unfollowEvent({ uuid: "u1" })), env, NOW, fetchImpl);
  DB.raw.prepare("UPDATE kicks SET status = 'kicked' WHERE event_uuid = 'u1'").run();
  await handleWebhookPost(signed(unfollowEvent({ uuid: "u1" })), env, new Date(NOW.getTime() + 60000), fetchImpl);
  assert.equal(kickRow(DB, "u1").status, "kicked");
});

test("with the automation off, the follow-up is recorded as skipped, never pending", async () => {
  const { DB, env, fetchImpl } = setup();
  await handleSettings(authed("/settings", { method: "POST", body: JSON.stringify({ mode: "off" }) }), env);
  await handleWebhookPost(signed(unfollowEvent({ uuid: "u1" })), env, NOW, fetchImpl);
  assert.equal(kickRow(DB, "u1").status, "skipped");
});

// ---- reading -----------------------------------------------------------------------------------------------------
test("reading needs the app token", async () => {
  const { env } = setup();
  for (const h of [{}, { authorization: "Bearer nope" }, { authorization: APP_TOKEN }]) {
    assert.equal((await handleRead(new Request("https://w/unfollows", { headers: h }), env, NOW)).status, 401);
  }
  assert.equal((await handleRead(authed("/unfollows"), env, NOW)).status, 200);
});

test("reading with no token configured is refused, never open", async () => {
  const { env } = setup({ APP_TOKEN: "" });
  assert.equal((await handleRead(new Request("https://w/unfollows", { headers: { authorization: "Bearer " } }), env, NOW)).status, 401);
});

test("reading returns what changed after a version, in change order, with the group follow-up and a status block", async () => {
  const { DB, env, fetchImpl } = setup();
  for (const [u, id] of [["a", "1"], ["b", "2"], ["c", "3"]]) await handleWebhookPost(signed(unfollowEvent({ uuid: u, sourceId: id, username: `user${id}` })), env, NOW, fetchImpl);
  const get = (since) => handleRead(authed(`/unfollows?since_version=${since}`), env, NOW).then((r) => r.json());
  const all = await get(0);
  assert.deepEqual(all.events.map((e) => e.username), ["user1", "user2", "user3"]);
  assert.equal(all.events[0].gc_status, "pending");
  const rest = await get(all.events[0].version);
  assert.deepEqual(rest.events.map((e) => e.username), ["user2", "user3"]);

  // A later change to the first row shows up again, after the others.
  DB.raw.prepare("UPDATE kicks SET status = 'kicked', version = 99 WHERE event_uuid = 'a'").run();
  const changed = await get(3);
  assert.deepEqual(changed.events.map((e) => [e.username, e.gc_status]), [["user1", "kicked"]]);

  assert.equal(all.status.total_events, 3);
  assert.equal(all.status.today_count, 3);
  assert.equal(all.status.automation.mode, "dry_run");
  assert.equal(all.status.automation.pending, 3);
  assert.equal(all.status.automation.kick_cap, 20);
  assert.equal(all.status.paused_reason, null);
  assert.equal((await get("garbage")).events.length, 3);
});

test("tracking_since is set once and never moves", async () => {
  const { env } = setup();
  const mark = (t) => handleMark(authed("/mark", { method: "POST" }), env, t).then((r) => r.json());
  assert.equal((await mark(new Date("2026-10-07T10:00:00Z"))).tracking_since, "2026-10-07T10:00:00.000Z");
  assert.equal((await mark(new Date("2026-10-09T10:00:00Z"))).tracking_since, "2026-10-07T10:00:00.000Z");
  assert.equal((await handleMark(new Request("https://w/mark", { method: "POST" }), env, NOW)).status, 401);
});

test("the schema backfills a row for unfollows stored before the automation existed", async () => {
  const { DB } = setup();
  DB.raw.prepare("INSERT INTO events (event_uuid, user_id, username, name, followers_count, following_count, received_at) VALUES ('old', '5', 'x', 'X', 1, 1, '2026-10-07T07:56:39.877Z')").run();
  DB.raw.exec(await import("node:fs").then((fs) => fs.readFileSync(new URL("../schema.sql", import.meta.url), "utf8")));
  assert.equal(kickRow(DB, "old").status, "not_tracked");
});

// ---- settings, resume, whitelist ----------------------------------------------------------------------------------
test("the mode can only be off, dry_run or live, and only with the token", async () => {
  const { DB, env } = setup();
  const put = (mode, headers) => handleSettings(new Request("https://w/settings", { method: "POST", body: JSON.stringify({ mode }), headers }), env);
  assert.equal((await put("live", {})).status, 401);
  assert.equal((await put("yolo", { authorization: `Bearer ${APP_TOKEN}` })).status, 400);
  assert.equal((await put("live", { authorization: `Bearer ${APP_TOKEN}` })).status, 200);
  assert.equal(state(DB, "mode"), "live");
});

test("resume clears an automation pause and needs the token", async () => {
  const { DB, env } = setup();
  DB.raw.prepare("INSERT INTO state(key, value) VALUES('auto_paused_reason', 'x')").run();
  assert.equal((await handleResume(new Request("https://w/resume", { method: "POST" }), env)).status, 401);
  assert.equal(state(DB, "auto_paused_reason"), "x");
  assert.equal((await handleResume(authed("/resume", { method: "POST" }), env)).status, 200);
  assert.equal(state(DB, "auto_paused_reason"), null);
});

test("the whitelist can be added to and removed from, with a numeric id only", async () => {
  const { DB, env } = setup();
  const wl = (body) => handleWhitelist(authed("/whitelist", { method: "POST", body: JSON.stringify(body) }), env);
  assert.equal((await wl({ user_id: "abc" })).status, 400);
  assert.equal((await wl({ user_id: "42" })).status, 200);
  assert.equal(DB.raw.prepare("SELECT COUNT(*) AS n FROM whitelist").get().n, 1);
  assert.equal((await wl({ user_id: "42", keep: false })).status, 200);
  assert.equal(DB.raw.prepare("SELECT COUNT(*) AS n FROM whitelist").get().n, 0);
  assert.equal((await handleWhitelist(new Request("https://w/whitelist", { method: "POST", body: "{}" }), env)).status, 401);
});

test("the CRC endpoint answers GET, and unknown paths are 404", async () => {
  const { env } = setup();
  const crc = await worker.fetch(new Request("https://w/x/webhook?crc_token=abc"), env);
  assert.equal(crc.status, 200);
  assert.equal((await crc.json()).response_token, "sha256=" + createHmac("sha256", SECRET).update("abc").digest("base64"));
  assert.equal((await worker.fetch(new Request("https://w/x/webhook"), env)).status, 400);
  assert.equal((await worker.fetch(new Request("https://w/other"), env)).status, 404);
});
