import test from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import worker, { handleMark, handleRead, handleWebhookPost } from "../src/index.js";
import { MAIN, unfollowEvent } from "./fixture.js";

const SECRET = "consumer-secret-for-tests";
const APP_TOKEN = "app-token-for-tests";
const NOW = new Date("2026-10-07T10:00:00Z");

/** Cloudflare D1's API over an in-memory SQLite, running the real schema.sql. */
function fakeD1() {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(new URL("../schema.sql", import.meta.url), "utf8"));
  return {
    prepare(sql) {
      const exec = (args) => ({
        run: async () => ({ meta: { changes: Number(db.prepare(sql).run(...args).changes) } }),
        first: async () => db.prepare(sql).get(...args) ?? null,
        all: async () => ({ results: db.prepare(sql).all(...args) }),
      });
      return { ...exec([]), bind: (...args) => exec(args) };
    },
    raw: db,
  };
}

function setup(over = {}) {
  const DB = fakeD1();
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, method: init?.method });
    return { ok: true, status: 200 };
  };
  const env = { DB, CONSUMER_SECRET: SECRET, BEARER: "bearer", APP_TOKEN, MAIN_USER_ID: MAIN, DAILY_CAP: "300", SUBSCRIPTION_ID: "sub1", ...over };
  return { DB, env, calls, fetchImpl };
}

const sig = (body) => "sha256=" + createHmac("sha256", SECRET).update(body).digest("base64");
const post = (body, headers = {}) => new Request("https://w/x/webhook", { method: "POST", body, headers });
const signed = (obj) => {
  const body = JSON.stringify(obj);
  return post(body, { "x-twitter-webhooks-signature": sig(body) });
};
const count = (DB) => DB.raw.prepare("SELECT COUNT(*) AS n FROM events").get().n;
const state = (DB, k) => DB.raw.prepare("SELECT value FROM state WHERE key = ?").get(k)?.value ?? null;

test("a signed unfollow is stored once, however many times X delivers it", async () => {
  const { DB, env, fetchImpl } = setup();
  for (let i = 0; i < 3; i++) assert.equal((await handleWebhookPost(signed(unfollowEvent()), env, NOW, fetchImpl)).status, 200);
  assert.equal(count(DB), 1);
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

test("reading needs the app token", async () => {
  const { env } = setup();
  for (const h of [{}, { authorization: "Bearer nope" }, { authorization: APP_TOKEN }]) {
    assert.equal((await handleRead(new Request("https://w/unfollows", { headers: h }), env, NOW)).status, 401);
  }
  assert.equal((await handleRead(new Request("https://w/unfollows", { headers: { authorization: `Bearer ${APP_TOKEN}` } }), env, NOW)).status, 200);
});

test("reading with no token configured is refused, never open", async () => {
  const { env } = setup({ APP_TOKEN: "" });
  assert.equal((await handleRead(new Request("https://w/unfollows", { headers: { authorization: "Bearer " } }), env, NOW)).status, 401);
});

test("reading returns events after a sequence number, in order, with a status block", async () => {
  const { env, fetchImpl } = setup();
  for (const [u, id] of [["a", "1"], ["b", "2"], ["c", "3"]]) await handleWebhookPost(signed(unfollowEvent({ uuid: u, sourceId: id, username: `user${id}` })), env, NOW, fetchImpl);
  const get = (after) => handleRead(new Request(`https://w/unfollows?after=${after}`, { headers: { authorization: `Bearer ${APP_TOKEN}` } }), env, NOW).then((r) => r.json());
  const all = await get(0);
  assert.deepEqual(all.events.map((e) => e.username), ["user1", "user2", "user3"]);
  const rest = await get(all.events[0].seq);
  assert.deepEqual(rest.events.map((e) => e.username), ["user2", "user3"]);
  assert.equal(all.status.total_events, 3);
  assert.equal(all.status.today_count, 3);
  assert.equal(all.status.paused_reason, null);
  assert.equal((await get("garbage")).events.length, 3);
});

test("tracking_since is set once and never moves", async () => {
  const { env } = setup();
  const mark = (t) => handleMark(new Request("https://w/mark", { method: "POST", headers: { authorization: `Bearer ${APP_TOKEN}` } }), env, t).then((r) => r.json());
  assert.equal((await mark(new Date("2026-10-07T10:00:00Z"))).tracking_since, "2026-10-07T10:00:00.000Z");
  assert.equal((await mark(new Date("2026-10-09T10:00:00Z"))).tracking_since, "2026-10-07T10:00:00.000Z");
  assert.equal((await handleMark(new Request("https://w/mark", { method: "POST" }), env, NOW)).status, 401);
});

test("the CRC endpoint answers GET, and unknown paths are 404", async () => {
  const { env } = setup();
  const crc = await worker.fetch(new Request("https://w/x/webhook?crc_token=abc"), env);
  assert.equal(crc.status, 200);
  assert.equal((await crc.json()).response_token, "sha256=" + createHmac("sha256", SECRET).update("abc").digest("base64"));
  assert.equal((await worker.fetch(new Request("https://w/x/webhook"), env)).status, 400);
  assert.equal((await worker.fetch(new Request("https://w/other"), env)).status, 404);
});
