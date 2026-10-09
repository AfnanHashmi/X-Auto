import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import worker, { handleConfig, handleExclude, handlePreview, handleReport, handleRun, handleSession } from "../src/index.js";
import { detectSpikes, executeRun, preview, tick } from "../src/runner.js";
import { armSession } from "../src/session.js";
import { getConfig, saveConfig } from "../src/store.js";
import { DEFAULT_CONFIG } from "../src/defaults.js";
import { authed, fakeXai, fixture, ist, putState, reply, runs, setup, state } from "./helpers.js";

const MON = "2026-10-12";
const NOW = ist(MON, "12:00");
const fight = [["Kitchen fight", "kitchen fight", 6], ["Voting", "voting trends", 4]];

const deps = (x) => ({ fetch: x.fetch, sleep: x.sleep });
const spec = (over = {}) => ({ type: "PULSE", trigger: "manual", ...over });
const post = (path, body) => authed(path, { method: "POST", body: JSON.stringify(body) });
const count = (DB, sql, ...args) => DB.raw.prepare(sql).get(...args).n;

async function arm(env, now = ist(MON, "09:00"), budget = 4) {
  const r = await armSession(env.DB, await getConfig(env.DB), now, budget);
  assert.equal(r.ok, true);
  return r;
}

// ---- one run -----------------------------------------------------------------------------------------------------------
test("a pulse uses the pulse model, IST dates and turns, and is stored with xAI's own cost", async () => {
  const { DB, env } = setup(); const x = fakeXai();
  x.next = () => reply(fight);
  const r = await executeRun(env, spec(), NOW, deps(x));
  assert.equal(r.ok, true);
  const q = x.requests[0];
  assert.equal(q.url, "https://api.x.ai/v1/responses");
  assert.equal(q.headers.authorization, "Bearer xai-test-key");
  assert.equal(q.body.model, "grok-4.3");
  assert.equal(q.body.tools[0].from_date, "2026-10-11");
  assert.equal(q.body.tools[0].to_date, "2026-10-13"); // exclusive, so today is included
  assert.equal(q.body.max_turns, 1);
  assert.ok(q.body.instructions.includes("LATEST only"));
  const run = runs(DB)[0];
  assert.equal(run.status, "ok");
  assert.ok(Math.abs(run.cost_usd - 0.1) < 1e-9);
  assert.equal(run.cost_from_xai, 1);
  assert.equal(run.posts_fetched, 20);
  assert.equal(count(DB, "SELECT COUNT(*) AS n FROM snapshots"), 2);
});

test("a deep scan uses the deep model, two turns and two days back, and stores its findings", async () => {
  const { DB, env } = setup(); const x = fakeXai();
  x.next = () => reply(fight, { posts: 90, deep: true });
  await executeRun(env, spec({ type: "DEEP" }), NOW, deps(x));
  const q = x.requests[0];
  assert.equal(q.body.model, "grok-4.7");
  assert.equal(q.body.tools[0].from_date, "2026-10-10");
  assert.equal(q.body.max_turns, 2);
  assert.ok(q.body.instructions.includes("TOP and LATEST") && q.body.instructions.includes("about 90"));
  assert.deepEqual(DB.raw.prepare("SELECT kind FROM findings ORDER BY id").all().map((r) => r.kind), ["question", "reply", "angle"]);
  assert.equal(DB.raw.prepare("SELECT extra FROM findings WHERE kind = 'angle'").get().extra, "fight kitchen"); // the normalized key
});

test("the ignore list and the focus go into the request", async () => {
  const { DB, env } = setup(); const x = fakeXai();
  const cfg = await getConfig(DB);
  await saveConfig(DB, { ...cfg, excluded_x_handles: ["spam1", "spam2"] });
  x.next = () => reply(fight);
  await executeRun(env, spec({ focus: "kitchen fight" }), NOW, deps(x));
  assert.deepEqual(x.requests[0].body.tools[0].excluded_x_handles, ["spam1", "spam2"]);
  assert.ok(x.requests[0].body.instructions.includes("kitchen fight"));
  assert.equal(runs(DB)[0].focus, "kitchen fight");
});

test("a run that could pass the daily limit, or use up the credits, is refused before anything is sent or stored", async () => {
  const { DB, env } = setup(); const x = fakeXai();
  await saveConfig(DB, { ...(await getConfig(DB)), daily_cap_usd: 0.1 });
  const r = await executeRun(env, spec(), NOW, deps(x));
  assert.equal(r.ok, false);
  assert.equal(r.kind, "daily");
  assert.equal(x.requests.length, 0);
  assert.equal(runs(DB).length, 0);
  await saveConfig(DB, { ...(await getConfig(DB)), daily_cap_usd: 6, credits_added_usd: 0.05, credits_added_at: NOW - 1000 });
  assert.equal((await executeRun(env, spec(), NOW, deps(x))).kind, "credits");
});

test("yesterday's spending does not count against today's limit", async () => {
  const { DB, env } = setup(); const x = fakeXai();
  DB.raw.prepare("INSERT INTO runs (type, trigger, model, started_at, status, target_posts, days_back, max_turns, cost_usd) VALUES ('DEEP','manual','m',?, 'ok', 90, 2, 2, 5.5)").run(ist("2026-10-11", "23:00"));
  await saveConfig(DB, { ...(await getConfig(DB)), daily_cap_usd: 1 });
  x.next = () => reply(fight);
  assert.equal((await executeRun(env, spec(), NOW, deps(x))).ok, true);
});

test("without an xAI key nothing is sent", async () => {
  const { DB, env } = setup({ XAI_API_KEY: "" }); const x = fakeXai();
  const r = await executeRun(env, spec(), NOW, deps(x));
  assert.equal(r.kind, "key");
  assert.equal(x.requests.length, 0);
  assert.equal(runs(DB).length, 0);
});

test("a timeout is recorded as failed with no cost, and never retried because it may have been billed", async () => {
  const { DB, env } = setup(); const x = fakeXai();
  x.next = () => Object.assign(new Error("aborted"), { name: "AbortError" });
  const r = await executeRun(env, spec(), NOW, deps(x));
  assert.equal(r.ok, false);
  assert.match(r.failed, /billed/);
  assert.equal(x.requests.length, 1);
  assert.equal(runs(DB)[0].status, "failed");
  assert.equal(runs(DB)[0].cost_usd, 0);
});

test("a rate limit or server error is retried twice, then succeeds or fails", async () => {
  const { env } = setup(); const x = fakeXai();
  let n = 0;
  x.next = () => (++n < 3 ? { status: 429, body: '{"error":"slow down"}' } : reply(fight));
  assert.equal((await executeRun(env, spec(), NOW, deps(x))).ok, true);
  assert.equal(x.requests.length, 3);

  const { env: env2, DB: DB2 } = setup(); const y = fakeXai();
  y.next = () => ({ status: 503, body: '{"error":{"message":"down"}}' });
  const r = await executeRun(env2, spec(), NOW, deps(y));
  assert.equal(r.ok, false);
  assert.equal(y.requests.length, 3);
  assert.match(r.failed, /503.*down/);
  assert.equal(runs(DB2)[0].status, "failed");
});

test("a client error such as a bad key is not retried", async () => {
  const { env } = setup(); const x = fakeXai();
  x.next = () => ({ status: 401, body: '{"error":{"message":"bad key"}}' });
  const r = await executeRun(env, spec(), NOW, deps(x));
  assert.equal(x.requests.length, 1);
  assert.match(r.failed, /401.*bad key/);
});

test("an answer that fails the schema is failed, but its cost is still recorded because it was billed", async () => {
  const { DB, env } = setup(); const x = fakeXai();
  x.next = () => JSON.stringify({ ...JSON.parse(reply(fight)), output_text: '{"sample_size":1}' });
  const r = await executeRun(env, spec(), NOW, deps(x));
  assert.equal(r.ok, false);
  const run = runs(DB)[0];
  assert.equal(run.status, "failed");
  assert.ok(Math.abs(run.cost_usd - 0.1) < 1e-9);
  assert.equal(run.posts_fetched, 20);
  assert.equal(count(DB, "SELECT COUNT(*) AS n FROM snapshots"), 0);
  // It counts as spend but never as history for trends.
  assert.equal(count(DB, "SELECT COUNT(*) AS n FROM runs WHERE status = 'ok'"), 0);
});

test("a reply that did not finish is failed, with its cost recorded", async () => {
  const { DB, env } = setup(); const x = fakeXai();
  x.next = () => reply(fight, { status: "incomplete" });
  const r = await executeRun(env, spec(), NOW, deps(x));
  assert.equal(r.ok, false);
  assert.match(r.failed, /did not finish/);
  assert.equal(runs(DB)[0].status, "failed");
  assert.ok(runs(DB)[0].cost_usd > 0);
  assert.equal(count(DB, "SELECT COUNT(*) AS n FROM snapshots"), 0);
});

test("a free sample run sends nothing, costs nothing, and never feeds trends or spend", async () => {
  const { DB, env } = setup({ XAI_API_KEY: "" }); const x = fakeXai();
  await saveConfig(DB, { ...(await getConfig(DB)), daily_cap_usd: 0.01 });
  const r = await executeRun(env, spec({ type: "DEEP", dry: true }), NOW, deps(x));
  assert.equal(r.ok, true);
  assert.equal(x.requests.length, 0);
  const run = runs(DB)[0];
  assert.equal(run.status, "dry_run");
  assert.equal(run.cost_usd, 0);
  assert.ok(count(DB, "SELECT COUNT(*) AS n FROM snapshots") > 0); // there is a report to look at
  const pv = await preview(env, "PULSE", null, NOW);
  assert.equal(pv.spent_today, 0);
});

test("the same topic in two runs is matched and labelled by comparing them", async () => {
  const { DB, env } = setup(); const x = fakeXai();
  x.next = () => reply([["Kitchen fight", "kitchen fight", 1], ["Other", "other thing", 9]]);
  await executeRun(env, spec(), NOW, deps(x));
  x.next = () => reply([["Fight in the kitchen", "fight kitchen", 8], ["Other", "other thing", 2]]);
  await executeRun(env, spec(), NOW + 3 * 86400000, deps(x));
  const second = DB.raw.prepare("SELECT * FROM snapshots WHERE run_id = 2").all();
  const fightRow = second.find((s) => s.name === "Fight in the kitchen");
  assert.equal(fightRow.key, "fight kitchen");
  assert.equal(DB.raw.prepare("SELECT key FROM snapshots WHERE run_id = 1 AND name = 'Kitchen fight'").get().key, "fight kitchen");
  assert.equal(fightRow.label, "RISING");
});

// ---- spikes -----------------------------------------------------------------------------------------------------------------------
const row = (key, mentions, label, isNew = label === "NEW") => ({ key, name: key, mentions, share: 0.5, baseline: 0, growth: 2, label, isNew, hasHistory: true });

test("a new or rising topic with enough mentions queues a focused scan, once per cooldown and at most three a day", async () => {
  const { DB } = setup();
  const cfg = await getConfig(DB);
  assert.deepEqual(await detectSpikes(DB, cfg, [row("a", 5, "NEW"), row("b", 2, "NEW"), row("c", 4, "STEADY")], NOW), ["a"]);
  DB.raw.prepare("DELETE FROM state WHERE key = 'spike_queue'").run(); // the follow-up has been done, so only the cooldown holds it back
  assert.deepEqual(await detectSpikes(DB, cfg, [row("a", 6, "RISING")], NOW + 3600000), []); // inside the 3 hour cooldown
  DB.raw.prepare("DELETE FROM state WHERE key = 'spike_queue'").run(); // the earlier follow-up has been done
  assert.deepEqual(await detectSpikes(DB, cfg, [row("a", 6, "RISING")], NOW + 4 * 3600000), ["a"]); // after the cooldown
  const more = await detectSpikes(DB, cfg, [row("d", 5, "RISING"), row("e", 5, "RISING"), row("f", 5, "RISING")], NOW + 5 * 3600000);
  assert.deepEqual(more, ["d"]); // 3 a day in total: a, a, d
  assert.deepEqual(await detectSpikes(DB, cfg, [row("x", 5, "RISING")], NOW + 6 * 3600000), []); // the daily limit
  // The first run ever has nothing to be new against.
  assert.deepEqual(await detectSpikes(setup().DB, cfg, [{ ...row("z", 9, "NEW"), hasHistory: false }], NOW), []);
  assert.deepEqual(JSON.parse(state(DB, "spike_queue")), ["a", "d"]);
});

// ---- the minute-by-minute tick -----------------------------------------------------------------------------------------------------
test("with the session off and nothing asked for, the tick does nothing at all", async () => {
  const { DB, env } = setup(); const x = fakeXai();
  const r = await tick(env, NOW, deps(x));
  assert.match(r.note, /off/);
  assert.equal(x.requests.length, 0);
  assert.equal(runs(DB).length, 0);
});

test("a run you asked for goes first, once, and its result is recorded", async () => {
  const { DB, env } = setup(); const x = fakeXai();
  x.next = () => reply(fight, { posts: 40 });
  const res = await handleRun(post("/run", { type: "pulse", posts: 40, focus: "kitchen fight" }), env, NOW);
  assert.equal(res.status, 200);
  assert.equal((await res.json()).queued, true);
  assert.equal(x.requests.length, 0); // queued, not run yet
  await tick(env, NOW + 1000, deps(x));
  assert.equal(x.requests.length, 1);
  assert.equal(runs(DB)[0].trigger, "manual");
  assert.equal(JSON.parse(state(DB, "last_job")).ok, true);
  await tick(env, NOW + 61000, deps(x));
  assert.equal(x.requests.length, 1); // the job was taken, not repeated
});

test("a second run is refused while one is queued or in progress", async () => {
  const { env } = setup();
  assert.equal((await handleRun(post("/run", { type: "pulse" }), env, NOW)).status, 200);
  const again = await handleRun(post("/run", { type: "deep" }), env, NOW);
  assert.equal(again.status, 409);
  assert.match((await again.json()).error, /already/);
});

test("a manual run that the guard refuses at the app is never queued", async () => {
  const { DB, env } = setup();
  await saveConfig(DB, { ...(await getConfig(DB)), daily_cap_usd: 0.1 });
  const res = await handleRun(post("/run", { type: "deep" }), env, NOW);
  assert.equal(res.status, 409);
  assert.equal(state(DB, "jobs"), null);
});

test("a run that was started but never finished is marked failed, and a fresh lock blocks a second tick", async () => {
  const { DB, env } = setup(); const x = fakeXai();
  DB.raw.prepare("INSERT INTO runs (type, trigger, model, started_at, status, target_posts, days_back, max_turns) VALUES ('PULSE','scheduled','m',?, 'running', 20, 1, 1)").run(NOW - 20 * 60000);
  await tick(env, NOW, deps(x));
  assert.equal(runs(DB)[0].status, "failed");
  assert.match(runs(DB)[0].error, /may still have been/);
  putState(DB, "run_lock", NOW - 60000);
  assert.match((await tick(env, NOW, deps(x))).note, /already in progress/);
});

// ---- a whole session ----------------------------------------------------------------------------------------------------------------------
test("a session armed in the morning waits, runs the first deep scan at 12:00, then pulses hourly and a deep scan at 18:00, and ends at 1 AM", async () => {
  const { DB, env } = setup(); const x = fakeXai();
  x.next = (b) => reply(fight, { posts: b.model === "grok-4.7" ? 90 : 20, deep: b.model === "grok-4.7", ticks: 100_000_000 }); // 1 cent a run
  await arm(env, ist(MON, "09:00"), 4);

  assert.match((await tick(env, ist(MON, "09:30"), deps(x))).note, /waiting/);
  assert.equal(x.requests.length, 0);

  await tick(env, ist(MON, "12:00"), deps(x));
  assert.deepEqual(x.requests.map((r) => r.body.model), ["grok-4.7"]); // the first run is the scheduled deep scan
  assert.match((await tick(env, ist(MON, "12:01"), deps(x))).note, /nothing due/);
  assert.equal(x.requests.length, 1);

  await tick(env, ist(MON, "13:00"), deps(x));
  assert.equal(x.requests.at(-1).body.model, "grok-4.3"); // an hourly pulse
  assert.match(x.requests.at(-1).body.instructions, /about 20 recent/);

  await tick(env, ist(MON, "18:00"), deps(x));
  assert.equal(x.requests.at(-1).body.model, "grok-4.7"); // the 18:00 deep scan
  await tick(env, ist(MON, "21:30"), deps(x));
  assert.match(x.requests.at(-1).body.instructions, /about 40 recent/); // peak size

  const before = x.requests.length;
  const end = await tick(env, ist("2026-10-13", "01:00"), deps(x));
  assert.equal(end.note, "session ended");
  assert.equal(x.requests.length, before);
  assert.equal(state(DB, "session_on"), "0");
  assert.match(state(DB, "session_ended_reason"), /window ended/);
  assert.match((await tick(env, ist("2026-10-13", "13:00"), deps(x))).note, /off/); // never starts the next day by itself
  assert.equal(x.requests.length, before);
});

test("switching on late runs one catch-up deep scan, not one per missed slot", async () => {
  const { env } = setup(); const x = fakeXai();
  x.next = () => reply(fight, { posts: 90, deep: true });
  await arm(env, ist(MON, "19:00"), 4);
  await tick(env, ist(MON, "19:00"), deps(x));
  await tick(env, ist(MON, "19:01"), deps(x));
  await tick(env, ist(MON, "19:02"), deps(x));
  assert.equal(x.requests.length, 1);
});

test("at 80% of the session limit pulses stop but the scheduled deep scans carry on, and at 100% the session ends", async () => {
  const { DB, env } = setup(); const x = fakeXai();
  x.next = () => reply(fight, { posts: 90, deep: true, ticks: 100_000_000 });
  await saveConfig(DB, { ...(await getConfig(DB)), daily_cap_usd: 20 }); // the daily limit is not what this test is about
  const a = await arm(env, ist(MON, "09:00"), 10);
  await tick(env, ist(MON, "12:00"), deps(x)); // deep scan done
  DB.raw.prepare("INSERT INTO runs (type, trigger, model, started_at, status, session_id, target_posts, days_back, max_turns, cost_usd) VALUES ('PULSE','scheduled','m',?, 'ok', ?, 20, 1, 1, 0.75)").run(ist(MON, "12:05"), Number(state(DB, "session_id")));
  DB.raw.prepare("UPDATE runs SET cost_usd = 8.2 WHERE trigger = 'scheduled' AND type = 'PULSE'").run(); // 82% of the $10 limit used
  const before = x.requests.length;
  assert.match((await tick(env, ist(MON, "14:00"), deps(x))).note, /80%/);
  assert.equal(x.requests.length, before);
  await tick(env, ist(MON, "18:00"), deps(x)); // the scheduled deep scan still runs
  assert.equal(x.requests.length, before + 1);
  DB.raw.prepare("UPDATE runs SET cost_usd = 9.999 WHERE trigger = 'scheduled' AND type = 'PULSE'").run();
  const end = await tick(env, ist(MON, "19:00"), deps(x));
  assert.equal(end.note, "session ended");
  assert.match(state(DB, "session_ended_reason"), /limit/);
  assert.ok(a.ok);
});

test("a run the guard refuses ends the session instead of being retried", async () => {
  const { DB, env } = setup(); const x = fakeXai();
  await arm(env, ist(MON, "09:00"), 0.2);
  const r = await tick(env, ist(MON, "12:00"), deps(x));
  assert.equal(r.note, "session ended");
  assert.equal(x.requests.length, 0);
  assert.match(state(DB, "session_ended_reason"), /limit/);
});

test("a failed run is not retried every minute, and two failures in a row end the session", async () => {
  const { DB, env } = setup(); const x = fakeXai();
  x.next = () => ({ status: 400, body: '{"error":"nope"}' });
  await arm(env, ist(MON, "09:00"), 4);
  const first = await tick(env, ist(MON, "12:00"), deps(x));
  assert.equal(first.note, "run failed");
  assert.match((await tick(env, ist(MON, "12:01"), deps(x))).note, /nothing due|waiting|off/);
  assert.equal(x.requests.length, 1); // not hammered
  const second = await tick(env, ist(MON, "13:00"), deps(x));
  assert.equal(second.note, "session ended");
  assert.match(state(DB, "session_ended_reason"), /failed/);
});

test("a new topic in a session queues a focused deep scan which runs next", async () => {
  const { env } = setup(); const x = fakeXai();
  x.next = (b) => reply([["Brand new fight", "brand new fight", 8], ["Other", "other", 2]], { posts: b.model === "grok-4.7" ? 90 : 20, deep: b.model === "grok-4.7", ticks: 100_000_000 });
  // An earlier run (say yesterday's) gives something to be new against.
  await executeRun(env, spec(), ist("2026-10-11", "15:00"), { fetch: async () => ({ ok: true, status: 200, headers: new Headers(), text: async () => reply([["Old news", "old news", 9], ["Other", "other", 1]]) }), sleep: async () => {} });
  await arm(env, ist(MON, "09:00"), 4);
  await tick(env, ist(MON, "12:00"), deps(x)); // the deep scan finds a new topic
  const next = await tick(env, ist(MON, "12:01"), deps(x));
  assert.match(next.note, /spike/);
  assert.ok(x.requests.at(-1).body.instructions.includes("Brand new fight"));
  assert.equal(x.requests.at(-1).body.model, "grok-4.7");
});

// ---- the API for the app -----------------------------------------------------------------------------------------------------------------------
test("everything needs the token", async () => {
  const { env } = setup();
  for (const [method, path] of [["GET", "/report"], ["GET", "/preview"], ["POST", "/session"], ["POST", "/run"], ["POST", "/config"], ["POST", "/exclude"]]) {
    assert.equal((await worker.fetch(new Request(`https://w${path}`, { method }), env)).status, 401, path);
    assert.equal((await worker.fetch(new Request(`https://w${path}`, { method, headers: { authorization: "Bearer nope" } }), env)).status, 401, path);
  }
  assert.equal((await worker.fetch(authed("/report"), env)).status, 200);
  assert.equal((await worker.fetch(authed("/nothing"), env)).status, 404);
  assert.equal((await worker.fetch(new Request("https://w/report", { headers: { authorization: "Bearer " } }), setup({ TREND_APP_TOKEN: "" }).env)).status, 401);
});

test("the report shows the session, the spend and the latest report, rising first", async () => {
  const { DB, env } = setup(); const x = fakeXai();
  x.next = () => reply(fight);
  await executeRun(env, spec(), NOW, deps(x));
  const rep = await handleReport(env, NOW);
  assert.equal(rep.session.phase, "off");
  assert.ok(Math.abs(rep.spend.today - 0.1) < 1e-9);
  assert.equal(rep.latest.snapshots.length, 2);
  assert.equal(rep.latest.run.status, "ok");
  assert.equal(rep.runs.length, 1);
  assert.deepEqual(rep.config.pulse_sizes, [20, 40, 50]);
  await arm(env, ist(MON, "09:00"), 3);
  const armed = await handleReport(env, ist(MON, "09:30"));
  assert.equal(armed.session.phase, "armed");
  assert.equal(armed.session.budget, 3);
  assert.equal((await handleReport(env, ist(MON, "12:30"))).session.phase, "running");
});

test("switching the session on arms the right window with a spending limit, and off ends it", async () => {
  const { env } = setup();
  const on = await handleSession(post("/session", { on: true, budget_usd: 3 }), env, ist(MON, "09:00"));
  const body = await on.json();
  assert.equal(on.status, 200);
  assert.equal(body.window_start, ist(MON, "12:00"));
  assert.equal(body.window_end, ist("2026-10-13", "01:00"));
  assert.equal(body.budget, 3);
  assert.equal((await handleSession(post("/session", { on: true, budget_usd: 0.05 }), env, ist(MON, "09:00"))).status, 409);
  assert.equal((await handleSession(post("/session", { on: "yes" }), env, NOW)).status, 400);
  assert.equal((await (await handleSession(post("/session", { on: false }), env, NOW)).json()).on, false);
});

test("the session limit defaults to what is left of the day and of the credits", async () => {
  const { DB, env } = setup();
  await saveConfig(DB, { ...(await getConfig(DB)), daily_cap_usd: 6, credits_added_usd: 2.5, credits_added_at: 1 });
  const r = await (await handleSession(post("/session", { on: true }), env, ist(MON, "09:00"))).json();
  assert.equal(r.budget, 2.5);
  await saveConfig(DB, { ...(await getConfig(DB)), daily_cap_usd: 0.05, credits_added_usd: null });
  assert.equal((await handleSession(post("/session", { on: true }), env, ist(MON, "09:00"))).status, 409); // no room today
});

test("the preview gives the likely and worst cost and whether the guard allows it", async () => {
  const { env } = setup();
  const pv = await (await handlePreview(authed("/preview?type=deep"), env, NOW)).json();
  assert.equal(pv.model, "grok-4.7");
  assert.equal(pv.likely, 0.84);
  assert.equal(pv.worst, 1.26);
  assert.equal(pv.guard.ok, true);
});

test("settings edits are validated, and a bad schedule is refused", async () => {
  const { DB, env } = setup();
  const ok = await handleConfig(post("/config", { daily_cap_usd: 8, deep_model: "grok-4.3" }), env, NOW);
  assert.equal(ok.status, 200);
  assert.equal((await getConfig(DB)).daily_cap_usd, 8);
  assert.equal((await handleConfig(post("/config", { daily_cap_usd: -1 }), env, NOW)).status, 400);
  const bad = JSON.parse(JSON.stringify(DEFAULT_CONFIG.schedule));
  bad.profiles.weekday.start = "nope";
  assert.equal((await handleConfig(post("/config", { schedule: bad }), env, NOW)).status, 400);
});

test("an account can be added to the ignore list from a report, up to twenty", async () => {
  const { DB, env } = setup();
  assert.equal((await handleExclude(post("/exclude", { handle: "@spammer" }), env, NOW)).status, 200);
  assert.deepEqual((await getConfig(DB)).excluded_x_handles, ["spammer"]);
  await handleExclude(post("/exclude", { handle: "spammer" }), env, NOW);
  assert.equal((await getConfig(DB)).excluded_x_handles.length, 1); // no repeats
  assert.equal((await handleExclude(post("/exclude", { handle: "bad name!" }), env, NOW)).status, 400);
  await saveConfig(DB, { ...(await getConfig(DB)), excluded_x_handles: Array.from({ length: 20 }, (_, i) => `h${i}`) });
  assert.equal((await handleExclude(post("/exclude", { handle: "one_more" }), env, NOW)).status, 409);
});

test("free sample runs happen at once through the API and cost nothing", async () => {
  const { DB, env } = setup({ XAI_API_KEY: "" });
  const res = await handleRun(post("/run", { type: "deep", dry: true }), env, NOW);
  assert.equal(res.status, 200);
  assert.equal(runs(DB)[0].status, "dry_run");
});

// ---- the Worker can only read ------------------------------------------------------------------------------------------------------------------
test("the Worker's only outbound host is api.x.ai: it has no X credentials and no way to post, reply, like or follow", () => {
  const dir = new URL("../src/", import.meta.url);
  const hosts = new Set();
  for (const f of readdirSync(dir)) {
    if (f === "samples.js" || !f.endsWith(".js")) continue;
    const code = readFileSync(new URL(f, dir), "utf8").split("\n").filter((l) => !l.trim().startsWith("//") && !l.trim().startsWith("*") && !l.trim().startsWith("/*")).join("\n");
    // prompts.js only TELLS the model what a post link looks like; it makes no request.
    if (f !== "prompts.js") for (const m of code.matchAll(/https?:\/\/([a-z0-9.-]+)/gi)) hosts.add(m[1]);
    assert.equal(/oauth|consumer_?key|access_?token|\/2\/(tweets|users|chat|dm_)/i.test(code), false, `${f} mentions X credentials or write endpoints`);
  }
  assert.deepEqual([...hosts], ["api.x.ai"]);
});
