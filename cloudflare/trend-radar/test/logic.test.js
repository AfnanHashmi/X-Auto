import test from "node:test";
import assert from "node:assert/strict";
import { analyse, isSpike, shareOf } from "../src/analysis.js";
import { check, creditsLeft } from "../src/budget.js";
import { applyPatch, cleanHandles, mergeConfig, scheduleProblem } from "../src/config.js";
import { DEFAULT_CONFIG } from "../src/defaults.js";
import { matchKey, mergeByKey, normalize, similar } from "../src/match.js";
import { ParseError, parseReport, parseXaiResponse } from "../src/parse.js";
import { costOf, estimate, worstCase } from "../src/pricing.js";
import { deepInstructions, pulseInstructions } from "../src/prompts.js";
import { DEEP_SCHEMA, PULSE_SCHEMA, isPostUrl } from "../src/schemas.js";
import { XaiError, callXai, requestBody } from "../src/xai.js";
import { fixture } from "./helpers.js";

// ---- parsing the REAL saved answers ------------------------------------------------------------------------------------
test("reads a real grok-4.3 pulse with its exact usage", () => {
  const r = parseXaiResponse(fixture("grok-4.3-pulse"), "PULSE");
  assert.equal(r.model, "grok-4.3");
  assert.equal(r.report.subtopics.length, 5);
  assert.equal(r.usage.server_side_tool_usage_details.x_posts_fetched, 18);
  assert.equal(r.usage.server_side_tool_usage_details.x_search_calls, 2);
  assert.equal(r.usage.cost_in_usd_ticks, 1055161500);
  assert.equal(r.report.dropped_urls, 0);
  assert.ok(r.report.subtopics.every((s) => s.example_urls.every(isPostUrl)));
  assert.deepEqual(r.report.questions, []);
});

test("reads real deep scans with questions, reply targets and angles", () => {
  const d = parseXaiResponse(fixture("grok-4.3-deep"), "DEEP");
  assert.equal(d.report.subtopics.length, 6);
  assert.equal(d.report.questions.length, 5);
  assert.equal(d.report.reply_targets.length, 6);
  assert.equal(d.report.angles.length, 6);
  const big = parseXaiResponse(fixture("grok-4.7-deep"), "DEEP");
  assert.equal(big.report.subtopics.length, 8);
  assert.equal(big.usage.server_side_tool_usage_details.x_posts_fetched, 129);
  assert.equal(big.usage.server_side_tool_usage_details.x_search_calls, 17);
});

test("xAI's own reported cost is used, and matches the arithmetic to a few tenths of a cent", () => {
  for (const [name, type] of [["grok-4.3-pulse", "PULSE"], ["grok-4.3-deep", "DEEP"], ["grok-4.7-pulse", "PULSE"], ["grok-4.7-deep", "DEEP"]]) {
    const r = parseXaiResponse(fixture(name), type);
    const reported = costOf(r.usage, r.model);
    const noTicks = { ...r.usage };
    delete noTicks.cost_in_usd_ticks;
    const computed = costOf(noTicks, r.model);
    assert.equal(reported.from_xai, true, name);
    assert.equal(computed.from_xai, false, name);
    assert.ok(Math.abs(reported.total - computed.total) < 0.003, `${name}: ${reported.total} vs ${computed.total}`);
  }
  assert.ok(Math.abs(costOf(parseXaiResponse(fixture("grok-4.3-pulse"), "PULSE").usage, "grok-4.3").total - 0.1055) < 0.0001);
  assert.ok(Math.abs(costOf(parseXaiResponse(fixture("grok-4.7-deep"), "DEEP").usage, "grok-4.7").total - 0.84) < 0.001);
});

const answer = (extra = "", sentiment = "mixed", url = "https://x.com/a/status/9") =>
  `{"sample_size":10,"subtopics":[{"name":"N","key":"n","keywords":["k"],"mentions":4,"sentiment":"${sentiment}","example_urls":["${url}"]}]${extra}}`;

test("an answer missing a field or with an unknown sentiment invalidates the whole run", () => {
  for (const bad of ['{"subtopics":[]}', '{"sample_size":1}', answer("", "angry"), "not json",
    '{"sample_size":1,"subtopics":[{"name":"N","key":"n","keywords":[],"mentions":-1,"sentiment":"mixed","example_urls":[]}]}',
    '{"sample_size":1,"subtopics":[{"name":"","key":"n","keywords":[],"mentions":1,"sentiment":"mixed","example_urls":[]}]}']) {
    assert.throws(() => parseReport(bad, "PULSE"), ParseError, bad);
  }
});

test("a deep answer must have its extra sections", () => {
  assert.throws(() => parseReport(answer(), "DEEP"), ParseError);
  const ok = parseReport(answer(',"questions":["q"],"reply_targets":[{"url":"https://x.com/a/status/1","why":"w"}],"angles":[{"idea":"i","subtopic_key":"n"}]'), "DEEP");
  assert.equal(ok.reply_targets.length, 1);
});

test("links that are not real post links are dropped and counted, never repaired", () => {
  const r = parseReport(answer("", "mixed", "https://evil.example/a/status/9"), "PULSE");
  assert.deepEqual(r.subtopics[0].example_urls, []);
  assert.equal(r.dropped_urls, 1);
  const deep = parseReport(answer(',"questions":[],"reply_targets":[{"url":"x.com/a","why":"w"}],"angles":[]'), "DEEP");
  assert.deepEqual(deep.reply_targets, []);
  assert.equal(deep.dropped_urls, 1);
});

test("post link checks", () => {
  assert.ok(isPostUrl("https://x.com/abc_1/status/123"));
  assert.ok(isPostUrl("https://x.com/i/status/123"));
  assert.ok(isPostUrl("https://twitter.com/abc/status/123"));
  for (const bad of ["http://x.com/abc/status/123", "https://x.com/abc/status/12a", "https://x.com/abc/status/123/photo/1", "https://x.com.evil.com/abc/status/123", "x.com/a"]) assert.equal(isPostUrl(bad), false, bad);
});

test("a reply that is unfinished, has no usage, or is an error is rejected", () => {
  const wrap = (o) => JSON.stringify({ status: "completed", model: "m", output_text: answer(), usage: { input_tokens: 1, output_tokens: 1 }, ...o });
  assert.throws(() => parseXaiResponse(wrap({ status: "incomplete" }), "PULSE"), ParseError);
  assert.throws(() => parseXaiResponse(wrap({ usage: undefined }), "PULSE"), ParseError);
  assert.throws(() => parseXaiResponse(wrap({ error: { message: "bad key" } }), "PULSE"), ParseError);
  assert.throws(() => parseXaiResponse("garbage", "PULSE"), ParseError);
  assert.equal(parseXaiResponse(wrap({}), "PULSE").report.subtopics.length, 1);
});

test("the answer is found in the message item when there is no output_text field", () => {
  const body = { status: "completed", model: "m", usage: { input_tokens: 1, output_tokens: 1 }, output: [{ type: "reasoning" }, { type: "message", content: [{ type: "output_text", text: answer() }] }] };
  assert.equal(parseXaiResponse(body, "PULSE").report.subtopics.length, 1);
});

// ---- the request that is sent -----------------------------------------------------------------------------------------------
const req = (excluded = []) => ({ model: "grok-4.3", instructions: "i", input: "n", fromDate: "2026-10-08", toDate: "2026-10-10", excludedHandles: excluded, schemaName: "trend_pulse", schema: PULSE_SCHEMA, maxTurns: 1 });

test("the only tool offered is x_search, so the model can only read", () => {
  const b = requestBody(req(["a", "b"]));
  assert.equal(b.tools.length, 1);
  const t = b.tools[0];
  assert.equal(t.type, "x_search");
  assert.equal(t.from_date, "2026-10-08");
  assert.equal(t.to_date, "2026-10-10");
  assert.equal(t.enable_image_understanding, false);
  assert.equal(t.enable_video_understanding, false);
  assert.deepEqual(t.excluded_x_handles, ["a", "b"]);
  assert.equal("allowed_x_handles" in t, false); // the two lists cannot be used together
  assert.equal(b.max_turns, 1);
  assert.equal(b.text.format.type, "json_schema");
  assert.equal(b.text.format.strict, true);
});

test("the exclusion list is capped at twenty and left out when empty", () => {
  assert.equal(requestBody(req(Array.from({ length: 30 }, (_, i) => `h${i}`))).tools[0].excluded_x_handles.length, 20);
  assert.equal("excluded_x_handles" in requestBody(req()).tools[0], false);
});

test("the schema has no engagement fields for the model to invent", () => {
  for (const schema of [PULSE_SCHEMA, DEEP_SCHEMA]) {
    const text = JSON.stringify(schema);
    for (const word of ["likes", "replies", "reposts", "views", "retweets", "impressions"]) assert.equal(text.includes(`"${word}"`), false, word);
  }
  assert.deepEqual(Object.keys(DEEP_SCHEMA.properties).sort(), ["angles", "questions", "reply_targets", "sample_size", "subtopics"]);
  assert.equal(DEEP_SCHEMA.additionalProperties, false);
});

test("pulse and deep instructions state their own limits and carry the keywords in all three languages", () => {
  const topic = DEFAULT_CONFIG.topics[0];
  const pulse = pulseInstructions(topic, { posts: 20 });
  assert.ok(pulse.includes("LATEST only") && pulse.includes("Do NOT fetch threads") && pulse.includes("about 20"));
  const deep = deepInstructions(topic, { posts: 90, focus: "qazi drama" });
  assert.ok(deep.includes("TOP and LATEST") && deep.includes("fetch threads") && deep.includes("about 90") && deep.includes("qazi drama"));
  assert.ok(pulse.includes("बिग बॉस 20") && pulse.includes("bigg boss 20 kal ka episode") && pulse.includes("#QaziTouqeer"));
  for (const t of [pulse, deep]) assert.ok(t.includes("only READS X"));
});

test("a timeout or a lost connection is marked as possibly billed; a refusal is not", async () => {
  const attempt = async (fetch) => {
    try {
      await callXai("k", { ...req(), timeoutSec: 5 }, { fetch, sleep: async () => {} });
    } catch (e) {
      return e;
    }
  };
  const timeout = await attempt(async () => { throw Object.assign(new Error("aborted"), { name: "AbortError" }); });
  assert.ok(timeout instanceof XaiError);
  assert.equal(timeout.maybeBilled, true);
  const lost = await attempt(async () => { throw new Error("reset"); });
  assert.equal(lost.maybeBilled, true);
  const refused = await attempt(async () => ({ ok: false, status: 401, headers: new Headers(), text: async () => '{"error":"bad key"}' }));
  assert.equal(refused.maybeBilled, false);
  assert.equal(refused.status, 401);
});

// ---- cost -------------------------------------------------------------------------------------------------------------------------
test("the estimate uses recent real runs, else the first measurements, and a pulse scales with its size", () => {
  assert.equal(estimate("DEEP", "grok-4.7", 90, []), 0.84);
  assert.equal(estimate("PULSE", "grok-4.3", 20, []), 0.106);
  assert.ok(Math.abs(estimate("PULSE", "grok-4.3", 40, []) - 0.212) < 1e-9);
  assert.ok(Math.abs(estimate("DEEP", "grok-4.3", 90, [0.4, 0.6]) - 0.5) < 1e-9);
  assert.equal(worstCase(1), 1.5);
});

// ---- matching sub-topics across runs ----------------------------------------------------------------------------------------------
test("normalizing merges case, hashtags, punctuation, word order, spelling variants and possessives", () => {
  assert.equal(normalize("Qazi Touqeer drama"), normalize("#Tauqeer-Qazi: Drama!"));
  assert.equal(normalize("kanika vs arishfa"), normalize("Arishfa Kanika"));
  assert.equal(normalize("Mary's evictions"), "evict mary");
  assert.equal(normalize("the of and"), "");
});

test("similar keys are the same thing but different topics are not", () => {
  assert.ok(similar("voting trends", "trends update voting"));
  assert.ok(similar("ration fights", "ration fight"));
  assert.equal(similar("qazi praise", "qazi drama"), false);
  assert.equal(similar("wildcard entries", "voting trends"), false);
  assert.equal(similar("", "x"), false);
});

test("a new key joins an existing similar one, otherwise it stands alone", () => {
  const known = ["trends voting", "fight qazi"];
  assert.equal(matchKey("Voting trends update", known), "trends voting");
  assert.equal(matchKey("Qazi drama", known), "fight qazi");
  assert.equal(matchKey("Weekend roadies", known), "roadies weekend");
});

test("two names for the same thing in one answer become one sub-topic", () => {
  const s = (name, key, mentions) => ({ name, key, keywords: [name], mentions, sentiment: "mixed", example_urls: [] });
  const merged = mergeByKey([s("Kitchen fight", "kitchen fight", 3), s("Fight in kitchen", "fight kitchen", 2), s("Voting", "voting", 5)], []);
  assert.equal(merged.length, 2);
  assert.equal(merged.find(([k]) => k === "fight kitchen")[1].mentions, 5);
});

// ---- growth and labels ---------------------------------------------------------------------------------------------------------------
const day = 86400000;
const now = 100 * day;
const sub = (name, mentions) => ({ name, mentions, keywords: [], sentiment: "mixed", example_urls: [] });
const rowOf = (key, mentions, total, past) => {
  const list = [[key, sub(key, mentions)]];
  if (total > mentions) list.push(["other", sub("other", total - mentions)]);
  return analyse(list, past, now).find((r) => r.key === key);
};

test("a topic seen for the first time is NEW; it is a spike only with weight AND earlier runs to be new against", () => {
  const history = [{ atMs: now - 5 * day, shares: { other: 1 } }];
  const r = rowOf("fight", 6, 10, history);
  assert.equal(r.label, "NEW");
  assert.equal(isSpike(r), true);
  assert.equal(r.share, 0.6);
  assert.equal(isSpike(rowOf("fight", 2, 10, history)), false); // too few mentions
  const first = rowOf("fight", 6, 10, []);
  assert.equal(first.label, "NEW");
  assert.equal(isSpike(first), false); // the very first run has nothing to compare with
});

test("a topic first seen an hour ago is still NEW to look at, but it is not a spike again", () => {
  const history = [{ atMs: now - 3600000, shares: { fight: 0.3 } }];
  const r = rowOf("fight", 2, 10, history);
  assert.equal(r.label, "NEW");
  assert.equal(r.isNew, false);
  assert.equal(isSpike(r), false);
});

test("a topic whose share climbs far above its baseline is RISING", () => {
  const past = [5, 6, 7, 8].map((d) => ({ atMs: now - d * day, shares: { fight: 0.1 } }));
  const r = rowOf("fight", 5, 10, past);
  assert.equal(r.label, "RISING");
  assert.ok(Math.abs(r.growth - 4) < 1e-9);
  assert.equal(isSpike(r), true);
});

test("the baseline counts runs where the topic was absent as zero", () => {
  const past = [{ atMs: now - 5 * day, shares: { fight: 0.4 } }, ...[6, 7, 8].map((d) => ({ atMs: now - d * day, shares: {} }))];
  assert.ok(Math.abs(rowOf("fight", 4, 10, past).baseline - 0.1) < 1e-9);
});

test("a steady topic near its peak is PEAKING and one well below its recent peak is FADING", () => {
  const past = [5, 6, 7].map((d) => ({ atMs: now - d * day, shares: { fight: 0.4 } }));
  assert.equal(rowOf("fight", 4, 10, past).label, "PEAKING");
  assert.equal(rowOf("fight", 2, 10, past).label, "FADING");
});

test("a topic that disappeared is reported as FADING with zero mentions", () => {
  const past = [{ atMs: now - 5 * day, shares: { "old topic": 0.5, fight: 0.2 } }];
  const gone = analyse([["fight", sub("fight", 5)]], past, now).find((r) => r.key === "old topic");
  assert.equal(gone.label, "FADING");
  assert.equal(gone.mentions, 0);
});

test("a topic seen long ago is no longer NEW, and spikes sort first", () => {
  assert.notEqual(rowOf("fight", 3, 10, [{ atMs: now - 10 * day, shares: { fight: 0.3 } }]).label, "NEW");
  const past = [{ atMs: now - 5 * day, shares: { steady: 0.3, rise: 0.05, fall: 0.4 } }];
  const rows = analyse([["steady", sub("s", 3)], ["rise", sub("r", 5)], ["fall", sub("f", 1)], ["fresh", sub("n", 4)]], past, now);
  assert.equal(rows[0].key, "rise");
  assert.equal(rows.at(-1).label, "FADING");
  assert.equal(shareOf(0, 0), 0);
});

// ---- the cost guard ---------------------------------------------------------------------------------------------------------------------
test("a run that could pass today's limit is refused with the reason", () => {
  assert.equal(check({ worst: 0.9, spentToday: 1, dailyCap: 6, creditsAdded: null, spentSinceCredits: 0 }).ok, true);
  const no = check({ worst: 1, spentToday: 5.5, dailyCap: 6, creditsAdded: null, spentSinceCredits: 0 });
  assert.equal(no.ok, false);
  assert.ok(no.reason.includes("$6.00") && no.reason.includes("$5.50"));
  assert.equal(no.kind, "daily");
});

test("a scheduled run is also held to the session's limit", () => {
  const no = check({ worst: 1, spentToday: 0, dailyCap: 6, creditsAdded: null, spentSinceCredits: 0, sessionBudget: 2, sessionSpent: 1.5 });
  assert.equal(no.ok, false);
  assert.equal(no.kind, "session");
  assert.equal(check({ worst: 1, spentToday: 0, dailyCap: 6, creditsAdded: null, spentSinceCredits: 0, sessionBudget: 2, sessionSpent: 0.5 }).ok, true);
});

test("credits you said you added are counted down and refuse a run that could exceed them", () => {
  assert.equal(creditsLeft(5, 3), 2);
  assert.equal(creditsLeft(5, 9), 0);
  assert.equal(creditsLeft(null, 3), null);
  assert.equal(check({ worst: 1.2, spentToday: 0, dailyCap: 6, creditsAdded: 5, spentSinceCredits: 4 }).kind, "credits");
  assert.equal(check({ worst: 0.9, spentToday: 0, dailyCap: 6, creditsAdded: 5, spentSinceCredits: 4 }).ok, true);
  assert.equal(check({ worst: 5, spentToday: 0, dailyCap: 6, creditsAdded: null, spentSinceCredits: 100 }).ok, true);
});

// ---- settings -----------------------------------------------------------------------------------------------------------------------------
test("a missing or damaged config falls back to the defaults and bad values are clamped", () => {
  assert.deepEqual(mergeConfig(null), DEFAULT_CONFIG);
  assert.deepEqual(mergeConfig("nonsense"), DEFAULT_CONFIG);
  const c = mergeConfig({ daily_cap_usd: -3, deep: { posts: 100000 }, pulse_sizes: [1, 9999], deep_model: "" });
  assert.equal(c.daily_cap_usd, 6);
  assert.equal(c.deep.posts, DEFAULT_CONFIG.deep.posts);
  assert.deepEqual(c.pulse_sizes, [20, 40, 50]);
  assert.equal(c.deep_model, "grok-4.7");
});

test("handles to ignore are cleaned and capped at twenty", () => {
  const out = cleanHandles(["@spam", " spam ", "bad-name", "ok_1", "", ...Array.from({ length: 30 }, (_, i) => `h${i}`)]);
  assert.equal(out.length, 20);
  assert.deepEqual(out.slice(0, 2), ["spam", "ok_1"]);
  assert.ok(out.every((h) => /^[A-Za-z0-9_]{1,15}$/.test(h)));
});

test("an edit from the app is checked; changing the credits restarts the count", () => {
  const cur = mergeConfig(null);
  assert.ok(applyPatch(cur, { daily_cap_usd: -1 }, 5).error);
  assert.ok(applyPatch(cur, { schedule: { profiles: {}, days: {} } }, 5).error);
  assert.ok(applyPatch(cur, { topics: [{ name: "" }] }, 5).error);
  const withCredits = applyPatch(cur, { credits_added_usd: 10 }, 12345).config;
  assert.equal(withCredits.credits_added_usd, 10);
  assert.equal(withCredits.credits_added_at, 12345);
  assert.equal(applyPatch(withCredits, { daily_cap_usd: 8 }, 99999).config.credits_added_at, 12345); // unchanged credits keep their start
  assert.equal(applyPatch(cur, { surprise: 1 }, 5).config.surprise, undefined); // unknown keys are ignored
});

test("the default schedule is valid, and bad schedules say why", () => {
  assert.equal(scheduleProblem(DEFAULT_CONFIG.schedule), null);
  const s = JSON.parse(JSON.stringify(DEFAULT_CONFIG.schedule));
  s.profiles.weekday.start = "25:00";
  assert.match(scheduleProblem(s), /start and end/);
  const t = JSON.parse(JSON.stringify(DEFAULT_CONFIG.schedule));
  t.pulse_every_min = 1;
  assert.match(scheduleProblem(t), /10 and 480/);
});

test("the default topic carries English, Hindi and Hinglish keywords, contestants and hashtags", () => {
  const t = DEFAULT_CONFIG.topics[0];
  assert.equal(t.name, "Bigg Boss 20");
  assert.ok(t.keywords_en.length && t.keywords_hi.length && t.keywords_hinglish.length);
  assert.ok(t.hashtags.includes("#BiggBoss20") && t.contestants.includes("Qazi Touqeer"));
});
