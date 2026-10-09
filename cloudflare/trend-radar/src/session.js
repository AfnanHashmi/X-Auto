// The day session: nothing runs until it is switched on, it works through ONE window, and it ends by itself.

import { creditsLeft } from "./budget.js";
import { targetWindow } from "./schedule.js";
import { clearState, dayStart, getJson, getState, sessionSpent, setJson, setState, spentSince } from "./store.js";

/** Everything the scheduler needs to know about the session. */
export async function loadSession(db) {
  const on = (await getState(db, "session_on")) === "1";
  const id = Number((await getState(db, "session_id")) || 0);
  return {
    on,
    id,
    windowStart: Number((await getState(db, "session_window_start")) || 0),
    windowEnd: Number((await getState(db, "session_window_end")) || 0),
    deepSlots: await getJson(db, "session_deep_slots", []),
    deepDone: await getJson(db, "session_deep_done", []),
    lastLookAt: Number((await getState(db, "session_last_look")) || 0),
    budget: Number((await getState(db, "session_budget")) || 0),
    spent: await sessionSpent(db, id),
    spikeQueue: await getJson(db, "spike_queue", []),
    failStreak: Number((await getState(db, "session_fail_streak")) || 0),
    endedReason: await getState(db, "session_ended_reason"),
    endedAt: Number((await getState(db, "session_ended_at")) || 0),
  };
}

/**
 * Switches the session on for the open window, or the next one to open. Returns {ok, window, budget} or {ok: false, reason}.
 * The budget defaults to what is left of today's limit (and of the credits you said you added, if lower).
 */
export async function armSession(db, cfg, now, requestedBudget) {
  const window = targetWindow(now, cfg);
  if (!window) return { ok: false, reason: "There is no window in the schedule to run in." };
  const spentToday = await spentSince(db, dayStart(now));
  const room = Math.max(0, cfg.daily_cap_usd - spentToday);
  const credits = cfg.credits_added_usd == null ? null : creditsLeft(cfg.credits_added_usd, await spentSince(db, cfg.credits_added_at));
  const fallback = credits == null ? room : Math.min(room, credits);
  const budget = requestedBudget == null ? fallback : requestedBudget;
  if (!(typeof budget === "number" && Number.isFinite(budget) && budget >= 0.2)) {
    return { ok: false, reason: requestedBudget == null ? "There is no spending room left today, so a session cannot start. Raise the daily limit or add credits." : "The session limit must be at least $0.20." };
  }
  await setState(db, "session_on", "1");
  await setState(db, "session_id", now);
  await setState(db, "session_window_start", window.start);
  await setState(db, "session_window_end", window.end);
  await setJson(db, "session_deep_slots", window.deepSlots);
  await setJson(db, "session_deep_done", []);
  await setState(db, "session_last_look", 0);
  await setState(db, "session_budget", budget);
  await setState(db, "session_fail_streak", 0);
  await setJson(db, "spike_queue", []);
  await clearState(db, "session_ended_reason");
  await clearState(db, "session_ended_at");
  return { ok: true, window, budget };
}

export async function endSession(db, reason, now) {
  await setState(db, "session_on", "0");
  await setState(db, "session_ended_reason", reason);
  await setState(db, "session_ended_at", now);
}

/** "off" | "armed" (waiting for the window) | "running" | "ended". */
export function phase(s, now) {
  if (s.on) return now < s.windowStart ? "armed" : "running";
  return s.endedAt ? "ended" : "off";
}
