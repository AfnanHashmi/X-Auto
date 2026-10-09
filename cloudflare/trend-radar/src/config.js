// The settings document: defaults, validation and merging. A damaged or partial stored document falls back to the
// default for whatever is missing, never to nothing; a bad edit is rejected with the reason.

import { DEFAULT_CONFIG } from "./defaults.js";
import { minutesOf } from "./schedule.js";

const clone = (x) => JSON.parse(JSON.stringify(x));
const DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"];
const HANDLE = /^[A-Za-z0-9_]{1,15}$/;
export const MAX_EXCLUDED = 20;

const num = (v, min, max, fallback) => (typeof v === "number" && Number.isFinite(v) && v >= min && v <= max ? v : fallback);
const intIn = (v, min, max, fallback) => (Number.isInteger(v) && v >= min && v <= max ? v : fallback);
const strList = (v, fallback) => (Array.isArray(v) ? v.filter((x) => typeof x === "string" && x.trim()).map((x) => x.trim()) : fallback);

function cleanTopic(t, fallback) {
  if (!t || typeof t !== "object" || typeof t.name !== "string" || !t.name.trim()) return fallback;
  return {
    name: t.name.trim(),
    keywords_en: strList(t.keywords_en, []),
    keywords_hi: strList(t.keywords_hi, []),
    keywords_hinglish: strList(t.keywords_hinglish, []),
    contestants: strList(t.contestants, []),
    hashtags: strList(t.hashtags, []),
  };
}

/** Handles X will accept: no @, letters/digits/underscore, 1-15 long, at most 20, no repeats. */
export function cleanHandles(list) {
  const seen = new Set();
  const out = [];
  for (const raw of Array.isArray(list) ? list : []) {
    const h = String(raw).trim().replace(/^@/, "");
    if (HANDLE.test(h) && !seen.has(h.toLowerCase())) {
      seen.add(h.toLowerCase());
      out.push(h);
    }
  }
  return out.slice(0, MAX_EXCLUDED);
}

/** Why a schedule is not usable, or null. */
export function scheduleProblem(s) {
  if (!s || typeof s !== "object") return "the schedule is missing";
  if (!s.profiles || typeof s.profiles !== "object" || !Object.keys(s.profiles).length) return "there is no window profile";
  for (const [name, p] of Object.entries(s.profiles)) {
    if (minutesOf(p?.start) === null || minutesOf(p?.end) === null) return `window '${name}' needs start and end like 12:00`;
    if (minutesOf(p.start) === minutesOf(p.end)) return `window '${name}' starts and ends at the same time`;
    for (const t of p.deep_times ?? []) if (minutesOf(t) === null) return `window '${name}' has a deep scan time that is not HH:MM`;
  }
  for (const d of DAYS) if (!s.profiles[s.days?.[d]]) return `${d} does not name a window profile`;
  if (!Number.isInteger(s.pulse_every_min) || s.pulse_every_min < 10 || s.pulse_every_min > 480) return "pulses must be between 10 and 480 minutes apart";
  if (s.peak && (minutesOf(s.peak.start) === null || minutesOf(s.peak.end) === null)) return "the peak window needs start and end like 21:00";
  return null;
}

/** The effective configuration from whatever is stored. */
export function mergeConfig(stored) {
  const d = clone(DEFAULT_CONFIG);
  if (!stored || typeof stored !== "object") return d;
  const topics = Array.isArray(stored.topics) ? stored.topics.map((t) => cleanTopic(t, null)).filter(Boolean) : [];
  const schedule = stored.schedule && !scheduleProblem(stored.schedule) ? stored.schedule : d.schedule;
  return {
    topics: topics.length ? topics : d.topics,
    excluded_x_handles: cleanHandles(stored.excluded_x_handles),
    pulse_model: typeof stored.pulse_model === "string" && stored.pulse_model.trim() ? stored.pulse_model.trim() : d.pulse_model,
    deep_model: typeof stored.deep_model === "string" && stored.deep_model.trim() ? stored.deep_model.trim() : d.deep_model,
    pulse_sizes: Array.isArray(stored.pulse_sizes) && stored.pulse_sizes.every((n) => Number.isInteger(n) && n >= 5 && n <= 200) && stored.pulse_sizes.length ? stored.pulse_sizes : d.pulse_sizes,
    pulse: {
      posts: intIn(stored.pulse?.posts, 5, 200, d.pulse.posts),
      peak_posts: intIn(stored.pulse?.peak_posts, 5, 200, d.pulse.peak_posts),
      max_turns: intIn(stored.pulse?.max_turns, 1, 5, d.pulse.max_turns),
      days_back: intIn(stored.pulse?.days_back, 1, 7, d.pulse.days_back),
    },
    deep: {
      posts: intIn(stored.deep?.posts, 10, 300, d.deep.posts),
      max_turns: intIn(stored.deep?.max_turns, 1, 5, d.deep.max_turns),
      days_back: intIn(stored.deep?.days_back, 1, 7, d.deep.days_back),
    },
    timeouts_sec: { pulse: intIn(stored.timeouts_sec?.pulse, 30, 600, d.timeouts_sec.pulse), deep: intIn(stored.timeouts_sec?.deep, 60, 840, d.timeouts_sec.deep) },
    daily_cap_usd: num(stored.daily_cap_usd, 0.01, 1000, d.daily_cap_usd),
    credits_added_usd: stored.credits_added_usd == null ? null : num(stored.credits_added_usd, 0, 100000, null),
    credits_added_at: Number.isFinite(stored.credits_added_at) ? stored.credits_added_at : 0,
    prices: { per_1k_posts: num(stored.prices?.per_1k_posts, 0.01, 1000, d.prices.per_1k_posts), per_1k_users: num(stored.prices?.per_1k_users, 0.01, 1000, d.prices.per_1k_users) },
    schedule,
    spike: {
      min_mentions: intIn(stored.spike?.min_mentions, 1, 100, d.spike.min_mentions),
      cooldown_hours: num(stored.spike?.cooldown_hours, 0.1, 72, d.spike.cooldown_hours),
      max_per_day: intIn(stored.spike?.max_per_day, 0, 20, d.spike.max_per_day),
    },
    worst_case_factor: num(stored.worst_case_factor, 1, 5, d.worst_case_factor),
  };
}

/**
 * Applies an edit from the app to the current configuration. Returns {config} or {error}. Only known keys are taken.
 * Changing the credits amount restarts the credit count from [nowMs].
 */
export function applyPatch(current, patch, nowMs) {
  if (!patch || typeof patch !== "object") return { error: "nothing to change" };
  const next = clone(current);
  if ("schedule" in patch) {
    const problem = scheduleProblem(patch.schedule);
    if (problem) return { error: `The schedule cannot be used: ${problem}.` };
    next.schedule = patch.schedule;
  }
  for (const k of ["pulse_model", "deep_model"]) {
    if (k in patch) {
      if (typeof patch[k] !== "string" || !patch[k].trim()) return { error: `${k} must be a model name` };
      next[k] = patch[k].trim();
    }
  }
  if ("topics" in patch) {
    const topics = Array.isArray(patch.topics) ? patch.topics.map((t) => cleanTopic(t, null)) : [];
    if (!topics.length || topics.some((t) => !t)) return { error: "every topic needs a name" };
    next.topics = topics;
  }
  if ("excluded_x_handles" in patch) {
    const cleaned = cleanHandles(patch.excluded_x_handles);
    next.excluded_x_handles = cleaned;
  }
  if ("daily_cap_usd" in patch) {
    if (!(typeof patch.daily_cap_usd === "number" && patch.daily_cap_usd > 0 && patch.daily_cap_usd <= 1000)) return { error: "the daily limit must be a positive amount" };
    next.daily_cap_usd = patch.daily_cap_usd;
  }
  if ("credits_added_usd" in patch) {
    const v = patch.credits_added_usd;
    if (v !== null && !(typeof v === "number" && v >= 0 && v <= 100000)) return { error: "credits must be an amount or empty" };
    if (v !== current.credits_added_usd) next.credits_added_at = nowMs;
    next.credits_added_usd = v;
  }
  for (const k of ["pulse_sizes"]) if (k in patch) next[k] = patch[k];
  if ("pulse" in patch) next.pulse = { ...next.pulse, ...patch.pulse };
  if ("deep" in patch) next.deep = { ...next.deep, ...patch.deep };
  if ("prices" in patch) next.prices = { ...next.prices, ...patch.prices };
  if ("spike" in patch) next.spike = { ...next.spike, ...patch.spike };
  // Everything goes back through the same clamping as a stored document.
  return { config: mergeConfig(next) };
}
