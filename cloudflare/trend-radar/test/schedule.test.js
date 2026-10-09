import test from "node:test";
import assert from "node:assert/strict";
import { DEFAULT_CONFIG } from "../src/defaults.js";
import { decide, inPeak, istDate, minutesOf, targetWindow, windowStartingOn } from "../src/schedule.js";
import { ist } from "./helpers.js";

const cfg = DEFAULT_CONFIG;
// Mon 12 Oct 2026 ... Fri 16, Sat 17, Sun 18
const MON = "2026-10-12";
const SAT = "2026-10-17";

test("a weekday window runs 12:00 to 01:00 the next morning, with deep scans at 12, 18 and 23", () => {
  const w = windowStartingOn(MON, cfg);
  assert.equal(w.start, ist(MON, "12:00"));
  assert.equal(w.end, ist("2026-10-13", "01:00"));
  assert.deepEqual(w.deepSlots, [ist(MON, "12:00"), ist(MON, "18:00"), ist(MON, "23:00")]);
  assert.equal(w.profile, "weekday");
});

test("a weekend window runs 20:00 to 01:00 with deep scans at 20 and 23", () => {
  const w = windowStartingOn(SAT, cfg);
  assert.equal(w.start, ist(SAT, "20:00"));
  assert.equal(w.end, ist("2026-10-18", "01:00"));
  assert.deepEqual(w.deepSlots, [ist(SAT, "20:00"), ist(SAT, "23:00")]);
  assert.equal(w.profile, "weekend");
});

test("the window ending at 1 AM belongs to the day it started: Saturday's runs into Sunday", () => {
  const sat = windowStartingOn(SAT, cfg);
  const sunEarly = ist("2026-10-18", "00:30");
  assert.ok(sunEarly >= sat.start && sunEarly < sat.end);
  assert.equal(targetWindow(sunEarly, cfg).date, SAT); // Sunday 00:30 is still Saturday's window
  assert.equal(targetWindow(ist("2026-10-18", "01:30"), cfg).date, "2026-10-18"); // after 1 AM the next one is Sunday's own
});

test("the target window is the open one, or the next to open", () => {
  assert.equal(targetWindow(ist(MON, "09:00"), cfg).date, MON);
  assert.equal(targetWindow(ist(MON, "15:00"), cfg).date, MON);
  assert.equal(targetWindow(ist("2026-10-13", "00:59"), cfg).date, MON);
  assert.equal(targetWindow(ist("2026-10-13", "01:00"), cfg).date, "2026-10-13");
  assert.equal(targetWindow(ist("2026-10-16", "22:00"), cfg).date, "2026-10-16"); // Friday evening
  assert.equal(targetWindow(ist("2026-10-17", "02:00"), cfg).date, SAT); // Saturday small hours: Saturday's own evening is next
});

test("istDate reads IST, not UTC", () => {
  assert.deepEqual(istDate(Date.parse("2026-10-12T20:00:00Z")), { date: "2026-10-13", dow: "tue", minutes: 90 });
  assert.equal(minutesOf("12:00"), 720);
  assert.equal(minutesOf("24:00"), null);
  assert.equal(minutesOf("nonsense"), null);
});

test("the peak is airtime to two hours later, 21:00 to 23:00", () => {
  assert.equal(inPeak(ist(MON, "20:59"), cfg), false);
  assert.equal(inPeak(ist(MON, "21:00"), cfg), true);
  assert.equal(inPeak(ist(MON, "22:59"), cfg), true);
  assert.equal(inPeak(ist(MON, "23:00"), cfg), false);
});

// ---- deciding what to do this minute -----------------------------------------------------------------------------------
const w = windowStartingOn(MON, cfg);
const session = (over = {}) => ({
  on: true, windowStart: w.start, windowEnd: w.end, deepSlots: w.deepSlots, deepDone: [], lastLookAt: 0, budget: 4, spent: 0, spikeQueue: [], ...over,
});

test("with the session off nothing ever runs", () => {
  assert.equal(decide(ist(MON, "13:00"), session({ on: false }), cfg).action, "none");
});

test("armed in the morning, it waits for the window to open", () => {
  const d = decide(ist(MON, "09:00"), session(), cfg);
  assert.equal(d.action, "none");
  assert.match(d.reason, /waiting/);
});

test("the first run when the window opens is the scheduled deep scan", () => {
  const d = decide(ist(MON, "12:00"), session(), cfg);
  assert.equal(d.action, "deep");
  assert.equal(d.trigger, "scheduled");
  assert.deepEqual(d.slots, [ist(MON, "12:00")]);
});

test("switching on late runs ONE catch-up deep scan and counts the missed slots as done", () => {
  const d = decide(ist(MON, "19:00"), session(), cfg);
  assert.equal(d.action, "deep");
  assert.deepEqual(d.slots, [ist(MON, "12:00"), ist(MON, "18:00")]);
  const after = decide(ist(MON, "19:01"), session({ deepDone: d.slots, lastLookAt: ist(MON, "19:00") }), cfg);
  assert.equal(after.action, "none"); // no second scan for the missed slots
});

test("after a deep scan the next look is a pulse an hour later, not straight away", () => {
  const s = session({ deepDone: [ist(MON, "12:00")], lastLookAt: ist(MON, "12:00") });
  assert.equal(decide(ist(MON, "12:59"), s, cfg).action, "none");
  const d = decide(ist(MON, "13:00"), s, cfg);
  assert.equal(d.action, "pulse");
  assert.equal(d.posts, 20);
});

test("pulses are bigger during the peak", () => {
  const s = session({ deepDone: [ist(MON, "12:00"), ist(MON, "18:00")], lastLookAt: ist(MON, "20:30") });
  const d = decide(ist(MON, "21:30"), s, cfg);
  assert.equal(d.action, "pulse");
  assert.equal(d.posts, 40);
});

test("a queued spike follow-up runs as a focused deep scan, before the next pulse", () => {
  const s = session({ deepDone: [ist(MON, "12:00")], lastLookAt: ist(MON, "12:00"), spikeQueue: ["kitchen fight"] });
  const d = decide(ist(MON, "13:00"), s, cfg);
  assert.equal(d.action, "deep");
  assert.equal(d.focus, "kitchen fight");
  assert.equal(d.trigger, "spike");
});

test("at 80% of the limit only the scheduled deep scans run, no pulses and no spike scans", () => {
  const s = session({ spent: 3.3, deepDone: [ist(MON, "12:00")], lastLookAt: ist(MON, "12:00"), spikeQueue: ["x"] });
  const quiet = decide(ist(MON, "14:00"), s, cfg);
  assert.equal(quiet.action, "none");
  assert.match(quiet.reason, /80%/);
  assert.equal(decide(ist(MON, "18:00"), s, cfg).action, "deep"); // the scheduled one still runs
});

test("at 100% of the limit the session ends", () => {
  const d = decide(ist(MON, "14:00"), session({ spent: 4 }), cfg);
  assert.equal(d.action, "end");
  assert.match(d.reason, /limit/);
});

test("once the window is over the session ends, and nothing runs outside it", () => {
  assert.equal(decide(ist("2026-10-13", "00:59"), session({ deepDone: w.deepSlots, lastLookAt: ist(MON, "23:30") }), cfg).action, "pulse");
  const end = decide(ist("2026-10-13", "01:00"), session(), cfg);
  assert.equal(end.action, "end");
  assert.match(end.reason, /window ended/);
});

test("scheduled deep scans and pulses stop exactly at the end of the window", () => {
  const s = session({ deepDone: w.deepSlots, lastLookAt: ist("2026-10-13", "00:00") });
  assert.notEqual(decide(ist("2026-10-13", "00:59"), s, cfg).action, "end");
  assert.equal(decide(ist("2026-10-13", "01:00"), s, cfg).action, "end");
});
