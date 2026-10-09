// When the radar may run. Pure: no network, no storage. All wall-clock logic is IST (UTC+5:30).

import { IST_OFFSET_MIN } from "./defaults.js";

const DAY_MS = 86400000;
const DOW = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"];

/** "HH:MM" -> minutes after midnight, or null when it is not a time. */
export function minutesOf(text) {
  const m = /^([01]?\d|2[0-3]):([0-5]\d)$/.exec(String(text));
  return m ? Number(m[1]) * 60 + Number(m[2]) : null;
}

/** The IST calendar date (YYYY-MM-DD) and weekday of an instant. */
export function istDate(ms, offsetMin = IST_OFFSET_MIN) {
  const d = new Date(ms + offsetMin * 60000);
  return { date: d.toISOString().slice(0, 10), dow: DOW[d.getUTCDay()], minutes: d.getUTCHours() * 60 + d.getUTCMinutes() };
}

/** The instant at a given IST date and time of day. */
export function istInstant(date, minutes, offsetMin = IST_OFFSET_MIN) {
  return Date.parse(`${date}T00:00:00Z`) + minutes * 60000 - offsetMin * 60000;
}

export function addDays(date, n) {
  return new Date(Date.parse(`${date}T00:00:00Z`) + n * DAY_MS).toISOString().slice(0, 10);
}

/**
 * The window that STARTS on the given IST date: its start, its end (after midnight when the end time is earlier than
 * the start time) and the instants of its scheduled deep scans, in order. Returns null if that day has no usable profile.
 */
export function windowStartingOn(date, config) {
  const s = config.schedule;
  const dow = istDate(istInstant(date, 12 * 60)).dow;
  const profile = s.profiles[s.days[dow]];
  if (!profile) return null;
  const a = minutesOf(profile.start);
  const b = minutesOf(profile.end);
  if (a === null || b === null) return null;
  const start = istInstant(date, a);
  const end = istInstant(b > a ? date : addDays(date, 1), b);
  // A deep scan time earlier than the start belongs after midnight (e.g. 00:30 in a window that began at 20:00).
  const deepSlots = (profile.deep_times || [])
    .map((t) => minutesOf(t))
    .filter((m) => m !== null)
    .map((m) => istInstant(m >= a ? date : addDays(date, 1), m))
    .filter((t) => t >= start && t < end)
    .sort((x, y) => x - y);
  return { date, profile: s.days[dow], start, end, deepSlots };
}

/** The window that is open now, or the next one to open: the first whose end is still ahead. */
export function targetWindow(now, config) {
  const today = istDate(now).date;
  for (const offset of [-1, 0, 1, 2]) {
    const w = windowStartingOn(addDays(today, offset), config);
    if (w && w.end > now) return w;
  }
  return null;
}

/** True when `now` is inside the peak part (episode time) of IST's clock, e.g. 21:00-23:00. */
export function inPeak(now, config) {
  const p = config.schedule.peak;
  const a = minutesOf(p?.start);
  const b = minutesOf(p?.end);
  if (a === null || b === null) return false;
  const m = istDate(now).minutes;
  return b > a ? m >= a && m < b : m >= a || m < b;
}

/**
 * What to do this minute. [st] is the session state:
 *   {on, windowStart, windowEnd, deepSlots[], deepDone[] (instants), lastLookAt (any run: a deep scan is a fresh look too), budget, spent, spikeQueue[]}
 * Returns {action: "none"|"deep"|"pulse"|"end", reason, ...}. Nothing outside the window, ever.
 */
export function decide(now, st, config) {
  if (!st.on) return { action: "none", reason: "session is off" };
  if (now >= st.windowEnd) return { action: "end", reason: "the window ended" };
  if (now < st.windowStart) return { action: "none", reason: "armed, waiting for the window to open" };

  const used = st.budget > 0 ? st.spent / st.budget : 1;
  if (used >= 1) return { action: "end", reason: "the session's spending limit was reached" };
  const deepOnly = used >= 0.8;

  // The scheduled deep scans: the earliest one that is due. Arriving late runs ONE scan and counts the missed ones as done.
  const due = st.deepSlots.filter((t) => t <= now && !st.deepDone.includes(t));
  if (due.length) return { action: "deep", reason: "scheduled deep scan", slots: due, trigger: "scheduled" };

  if (!deepOnly && st.spikeQueue.length) return { action: "deep", reason: "spike follow-up", focus: st.spikeQueue[0], trigger: "spike" };

  if (!deepOnly) {
    const every = config.schedule.pulse_every_min * 60000;
    if (!st.lastLookAt || now - st.lastLookAt >= every) {
      const posts = inPeak(now, config) ? config.pulse.peak_posts : config.pulse.posts;
      return { action: "pulse", reason: inPeak(now, config) ? "hourly pulse (peak)" : "hourly pulse", posts, trigger: "scheduled" };
    }
  }
  return { action: "none", reason: deepOnly ? "80% of the limit used: scheduled deep scans only" : "nothing due" };
}
