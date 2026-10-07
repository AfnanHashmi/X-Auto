import test from "node:test";
import assert from "node:assert/strict";
import { processDueKicks } from "../src/kicks.js";
import { DK, GROUP, NOW, RW, kickRow, setup, state } from "./helpers.js";
import { MAIN } from "./fixture.js";

const LATER = new Date(NOW.getTime() + 31 * 60 * 1000); // past the 30 minute delay
const ADMIN = "7777";

/** A fake X: the group, who follows main, and what each call should do. Records every call. */
function fakeX(over = {}) {
  const x = {
    members: new Set([RW, MAIN, DK, ADMIN, "10", "11", "12", "13", "14"]),
    admins: new Set([RW, ADMIN]),
    follows: new Set(["13"]),
    unverifiable: new Set(),
    groupStatus: 200,
    groupId: GROUP,
    groupType: "group",
    checkStatus: new Map(),
    removeStatus: 200,
    removeBody: (id) => ({ data: { removed: true } }),
    removeThrows: false,
    removeThrowsFirstOnly: false,
    calls: [],
    ...over,
  };
  x.fetch = async (url, init = {}) => {
    const u = new URL(url);
    const auth = init.headers?.authorization || "";
    x.calls.push({ method: init.method, path: u.pathname, key: auth.match(/oauth_consumer_key="([^"]+)"/)?.[1], body: init.body });
    const reply = (status, body) => ({ status, ok: status < 300, json: async () => body });
    if (u.pathname.startsWith("/2/chat/conversations/") && u.pathname.endsWith("/members/remove")) {
      if (x.removeThrows) throw new Error("network down");
      const id = JSON.parse(init.body).user_ids[0];
      const body = x.removeBody(id);
      if (x.removeStatus === 200 && body?.data?.removed) {
        x.members.delete(id);
      }
      return reply(x.removeStatus, body);
    }
    if (u.pathname.startsWith("/2/chat/conversations/")) {
      if (x.groupStatus !== 200) return reply(x.groupStatus, { errors: [{ title: "x" }] });
      return reply(200, { data: { id: x.groupId, type: x.groupType, member_ids: [...x.members], admin_ids: [...x.admins] } });
    }
    const m = u.pathname.match(/^\/2\/users\/(\d+)$/);
    if (m) {
      const id = m[1];
      if (x.checkStatus.has(id)) return reply(x.checkStatus.get(id), { title: "limit" });
      if (x.unverifiable.has(id)) return reply(200, { errors: [{ title: "Not Found Error" }] });
      return reply(200, { data: { id, ...(x.follows.has(id) ? { connection_status: ["followed_by"] } : {}) } });
    }
    return reply(404, {});
  };
  x.count = (pred) => x.calls.filter(pred).length;
  x.removes = () => x.calls.filter((c) => c.path.endsWith("/members/remove"));
  return x;
}

function seed(DB, id, { status = "pending", dueAt = NOW.toISOString() } = {}) {
  DB.raw.prepare("INSERT INTO events (event_uuid, user_id, username, name, followers_count, following_count, received_at) VALUES (?, ?, ?, ?, 1, 1, ?)").run(`u${id}`, id, `user${id}`, `User ${id}`, NOW.toISOString());
  DB.raw.prepare("INSERT INTO kicks (event_uuid, user_id, status, detail, due_at, updated_at, version) VALUES (?, ?, ?, NULL, ?, ?, ?)").run(`u${id}`, id, status, dueAt, NOW.toISOString(), Number(id) % 1000);
}

const run = (env, x, now = LATER, sleeps = []) => processDueKicks(env, now, { fetch: x.fetch, sleep: async (ms) => void sleeps.push(ms) });
const setMode = (DB, mode) => DB.raw.prepare("INSERT INTO state(key, value) VALUES('mode', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").run(mode);
const status = (DB, id) => kickRow(DB, `u${id}`).status;

test("dry run records what it would do and removes nobody", async () => {
  const { DB, env } = setup(); const x = fakeX();
  seed(DB, "10");
  await run(env, x);
  assert.equal(status(DB, "10"), "would_kick");
  assert.equal(x.removes().length, 0);
});

test("live: a member who unfollowed and still does not follow is removed, using RW's keys for the group and main's to re-check", async () => {
  const { DB, env } = setup(); const x = fakeX();
  setMode(DB, "live"); seed(DB, "10");
  await run(env, x);
  assert.equal(status(DB, "10"), "kicked");
  assert.deepEqual(x.removes().map((c) => JSON.parse(c.body)), [{ user_ids: ["10"] }]);
  assert.equal(x.removes()[0].key, "rwck");
  assert.equal(x.calls.find((c) => c.path === "/2/users/10").key, "mnck");
  assert.equal(x.calls.find((c) => c.path.startsWith("/2/chat/conversations/") && c.method === "GET").key, "rwck");
  assert.equal(state(DB, `kicked:2026-10-07`), "1");
  assert.equal(kickRow(DB, "u10").detail, "removed from the group");
});

test("someone who is not due yet is left alone", async () => {
  const { DB, env } = setup(); const x = fakeX();
  setMode(DB, "live"); seed(DB, "10", { dueAt: new Date(LATER.getTime() + 60000).toISOString() });
  await run(env, x);
  assert.equal(status(DB, "10"), "pending");
  assert.equal(x.calls.length, 0);
});

test("protected people are never touched and never even re-checked: you, DK, RW, an admin, the whitelist", async () => {
  const { DB, env } = setup(); const x = fakeX();
  setMode(DB, "live");
  DB.raw.prepare("INSERT INTO whitelist (user_id) VALUES ('11')").run();
  for (const id of [MAIN, DK, RW, ADMIN, "11"]) seed(DB, id);
  await run(env, x);
  for (const id of [MAIN, DK, RW, ADMIN, "11"]) assert.equal(status(DB, id), "protected", id);
  assert.equal(x.removes().length, 0);
  assert.equal(x.count((c) => c.path.startsWith("/2/users/")), 0);
});

test("someone who is not in the group costs nothing: no re-check, no removal", async () => {
  const { DB, env } = setup(); const x = fakeX();
  setMode(DB, "live"); seed(DB, "99");
  await run(env, x);
  assert.equal(status(DB, "99"), "not_in_gc");
  assert.equal(x.count((c) => c.path.startsWith("/2/users/")), 0);
  assert.equal(x.removes().length, 0);
});

test("someone who follows again is kept", async () => {
  const { DB, env } = setup(); const x = fakeX();
  setMode(DB, "live"); seed(DB, "13");
  await run(env, x);
  assert.equal(status(DB, "13"), "refollowed");
  assert.equal(x.removes().length, 0);
});

test("an account X cannot answer for is kept, never removed", async () => {
  const { DB, env } = setup(); const x = fakeX({ unverifiable: new Set(["10"]), checkStatus: new Map([["11", 500]]) });
  setMode(DB, "live"); seed(DB, "10"); seed(DB, "11");
  await run(env, x);
  assert.equal(status(DB, "10"), "unverified");
  assert.equal(status(DB, "11"), "unverified");
  assert.equal(x.removes().length, 0);
});

test("a rate limit on the re-check leaves that person and the rest pending for the next run", async () => {
  const { DB, env } = setup(); const x = fakeX({ checkStatus: new Map([["10", 429]]) });
  setMode(DB, "live"); seed(DB, "10"); seed(DB, "12");
  await run(env, x);
  assert.equal(status(DB, "10"), "pending");
  assert.equal(status(DB, "12"), "pending");
  assert.equal(x.removes().length, 0);
});

test("with the automation off or paused, nothing is read or removed", async () => {
  for (const arrange of [
    (DB) => setMode(DB, "off"),
    (DB) => DB.raw.prepare("INSERT INTO state(key, value) VALUES('auto_paused_reason', 'x')").run(),
    (DB) => DB.raw.prepare("INSERT INTO state(key, value) VALUES('paused_reason', 'tracking paused')").run(),
  ]) {
    const { DB, env } = setup(); const x = fakeX();
    arrange(DB); if (!state(DB, "mode")) setMode(DB, "live"); seed(DB, "10");
    await run(env, x);
    assert.equal(status(DB, "10"), "pending");
    assert.equal(x.calls.length, 0);
  }
});

test("if the group cannot be read, everyone stays pending", async () => {
  for (const over of [{ groupStatus: 500 }, { groupId: "g1" }, { groupType: "one_to_one" }]) {
    const { DB, env } = setup(); const x = fakeX(over);
    setMode(DB, "live"); seed(DB, "10");
    await run(env, x);
    assert.equal(status(DB, "10"), "pending", JSON.stringify(over));
    assert.equal(x.removes().length, 0);
  }
});

test("if RW stopped being an admin, automation pauses and nobody is removed", async () => {
  const { DB, env } = setup(); const x = fakeX({ admins: new Set([ADMIN]) });
  setMode(DB, "live"); seed(DB, "10");
  await run(env, x);
  assert.match(state(DB, "auto_paused_reason"), /no longer an admin/);
  assert.equal(status(DB, "10"), "pending");
  assert.equal(x.removes().length, 0);
});

test("the group is read once per batch, however many people are due", async () => {
  const { DB, env } = setup(); const x = fakeX();
  setMode(DB, "dry_run"); for (const id of ["10", "11", "12"]) seed(DB, id);
  await run(env, x);
  assert.equal(x.count((c) => c.path.startsWith("/2/chat/conversations/") && c.method === "GET"), 1);
});

test("the first failed removal stops everything and pauses the automation", async () => {
  const { DB, env } = setup(); const x = fakeX({ removeStatus: 403, removeBody: () => ({ errors: [{ detail: "nope" }] }) });
  setMode(DB, "live"); seed(DB, "10"); seed(DB, "11"); seed(DB, "12");
  await run(env, x);
  assert.equal(status(DB, "10"), "failed");
  assert.match(kickRow(DB, "u10").detail, /HTTP 403: nope/);
  assert.equal(status(DB, "11"), "pending");
  assert.equal(status(DB, "12"), "pending");
  assert.equal(x.removes().length, 1);
  assert.match(state(DB, "auto_paused_reason"), /stopped at the first problem/);
});

test("removed: false is a failure, not a success", async () => {
  const { DB, env } = setup(); const x = fakeX({ removeBody: () => ({ data: { removed: false } }) });
  setMode(DB, "live"); seed(DB, "10");
  await run(env, x);
  assert.equal(status(DB, "10"), "failed");
  assert.ok(state(DB, "auto_paused_reason"));
});

test("a removal with no reply is settled by re-reading the group", async () => {
  const { DB, env } = setup(); const x = fakeX({ removeThrows: true });
  setMode(DB, "live"); seed(DB, "10");
  await run(env, x);
  assert.equal(status(DB, "10"), "failed"); // still listed
  assert.ok(state(DB, "auto_paused_reason"));

  const t = setup(); const y = fakeX({ removeThrows: true });
  const origFetch = y.fetch;
  y.fetch = async (url, init) => {
    if (init?.method === "POST") y.members.delete("10"); // it went through, but the reply was lost
    return origFetch(url, init);
  };
  setMode(t.DB, "live"); seed(t.DB, "10");
  await run(t.env, y);
  assert.equal(status(t.DB, "10"), "kicked");
  assert.match(kickRow(t.DB, "u10").detail, /no reply from X, but the group no longer lists them/);
});

test("the daily limit pauses the automation instead of removing more", async () => {
  const { DB, env } = setup({ KICK_DAILY_CAP: "2" }); const x = fakeX();
  setMode(DB, "live"); for (const id of ["10", "11", "12"]) seed(DB, id);
  await run(env, x);
  assert.equal(status(DB, "10"), "kicked");
  assert.equal(status(DB, "11"), "kicked");
  assert.equal(status(DB, "12"), "pending");
  assert.match(state(DB, "auto_paused_reason"), /daily limit of 2/);
  assert.equal(x.removes().length, 2);
});

test("removals are spaced out, and only between removals", async () => {
  const { DB, env } = setup({ KICK_SPACING_MS: "3000" }); const x = fakeX();
  setMode(DB, "live"); for (const id of ["10", "11", "12"]) seed(DB, id);
  const sleeps = [];
  await run(env, x, LATER, sleeps);
  assert.deepEqual(sleeps, [3000, 3000]);
});

test("at most ten people are handled per run", async () => {
  const { DB, env } = setup(); const x = fakeX();
  x.members = new Set([RW, ...Array.from({ length: 15 }, (_, i) => String(100 + i))]);
  for (let i = 0; i < 15; i++) seed(DB, String(100 + i));
  await run(env, x);
  assert.equal(DB.raw.prepare("SELECT COUNT(*) AS n FROM kicks WHERE status = 'would_kick'").get().n, 10);
  assert.equal(DB.raw.prepare("SELECT COUNT(*) AS n FROM kicks WHERE status = 'pending'").get().n, 5);
});

test("a dry-run decision is final: switching to live later does not remove those people", async () => {
  const { DB, env } = setup(); const x = fakeX();
  seed(DB, "10");
  await run(env, x);
  setMode(DB, "live");
  await run(env, x, new Date(LATER.getTime() + 3600000));
  assert.equal(status(DB, "10"), "would_kick");
  assert.equal(x.removes().length, 0);
});

test("an interrupted removal is settled from the group and pauses the automation", async () => {
  const { DB, env } = setup(); const x = fakeX();
  setMode(DB, "live"); seed(DB, "10", { status: "in_flight" }); seed(DB, "11", { status: "in_flight" });
  x.members.delete("11"); // 11 is gone, 10 is still there
  await run(env, x);
  assert.equal(status(DB, "10"), "failed");
  assert.ok(state(DB, "auto_paused_reason"));
  assert.equal(x.removes().length, 0);
});

test("a run that is already in progress blocks a second one, but a stale lock does not", async () => {
  const { DB, env } = setup(); const x = fakeX();
  setMode(DB, "dry_run"); seed(DB, "10");
  DB.raw.prepare("INSERT INTO state(key, value) VALUES('run_lock', ?)").run(String(LATER.getTime() - 60 * 1000));
  await run(env, x);
  assert.equal(status(DB, "10"), "pending");
  DB.raw.prepare("UPDATE state SET value = ? WHERE key = 'run_lock'").run(String(LATER.getTime() - 10 * 60 * 1000));
  await run(env, x);
  assert.equal(status(DB, "10"), "would_kick");
  assert.equal(state(DB, "run_lock"), null);
});

test("who is being removed is on record BEFORE the request is sent", async () => {
  const { DB, env } = setup(); const x = fakeX();
  setMode(DB, "live"); seed(DB, "10");
  let seenAtRequest = null;
  const orig = x.fetch;
  x.fetch = async (url, init) => {
    if (init?.method === "POST") seenAtRequest = status(DB, "10");
    return orig(url, init);
  };
  await run(env, x);
  assert.equal(seenAtRequest, "in_flight");
  assert.equal(status(DB, "10"), "kicked");
});

test("the remover can never be sent for removal, even if a row for it exists", async () => {
  const { DB, env } = setup(); const x = fakeX();
  setMode(DB, "live"); seed(DB, RW);
  await run(env, x);
  assert.equal(status(DB, RW), "protected");
  assert.equal(x.removes().length, 0);
});
