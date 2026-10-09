// Growth and labels. They come ONLY from comparing stored runs: X's date filters are whole days, so the API cannot say
// what changed within a day. Runs are compared with the runs before them, however long ago those were.

export const MIN_MENTIONS = 3;
export const BASELINE_RUNS = 4;
export const FADING_RUNS = 3;
export const NEW_WITHIN_MS = 48 * 3600 * 1000;
export const RISING_GROWTH = 1.0;
const FLOOR = 0.02;

const ORDER = { RISING: 0, NEW: 1, PEAKING: 2, STEADY: 3, FADING: 4 };
export const labelOrder = (label) => ORDER[label] ?? 9;

export const shareOf = (mentions, total) => (total <= 0 ? 0 : mentions / total);

const mean = (xs) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : 0);

/**
 * Worth a focused follow-up: climbing, or appearing for the first time with weight. "First time" means absent from every
 * earlier run, so the very first run (no earlier runs at all) never counts everything as a spike.
 */
export const isSpike = (row) => (row.isNew && row.hasHistory && row.mentions >= MIN_MENTIONS) || row.label === "RISING";

/**
 * [subtopics] is [[key, subtopic], ...] for the latest run; [past] the runs before it, newest first, each
 * {atMs, shares: {key: share}}; [nowMs] when the latest run happened. A key that was in the recent runs but is missing
 * now comes back too, with 0 mentions, so a topic that fell away can be shown as FADING.
 */
export function analyse(subtopics, past, nowMs) {
  const total = subtopics.reduce((a, [, s]) => a + s.mentions, 0);
  const recent = past.slice(0, BASELINE_RUNS);
  const rows = [];
  const seen = new Set();
  const firstSeen = (key) => {
    const times = past.filter((p) => (p.shares[key] ?? 0) > 0).map((p) => p.atMs);
    return times.length ? Math.min(...times) : nowMs;
  };
  for (const [key, s] of subtopics) {
    seen.add(key);
    const share = shareOf(s.mentions, total);
    const baseline = recent.length ? mean(recent.map((p) => p.shares[key] ?? 0)) : 0;
    const growth = (share - baseline) / Math.max(baseline, FLOOR);
    const peak3 = Math.max(0, ...past.slice(0, FADING_RUNS).map((p) => p.shares[key] ?? 0));
    const historyMax = Math.max(peak3, ...past.map((p) => p.shares[key] ?? 0), 0);
    const first = firstSeen(key);
    const isNew = first === nowMs; // in none of the earlier runs
    let label;
    if (isNew) label = "NEW";
    else if (growth >= RISING_GROWTH && s.mentions >= MIN_MENTIONS) label = "RISING";
    else if (peak3 > 0 && share <= peak3 * 0.6) label = "FADING";
    else if (s.mentions >= MIN_MENTIONS && share >= Math.max(historyMax, share) * 0.9 && growth > -0.25 && growth < 0.25) label = "PEAKING";
    else if (nowMs - first < NEW_WITHIN_MS) label = "NEW"; // appeared within the last two days and is nothing more specific yet
    else label = "STEADY";
    rows.push({ key, name: s.name, mentions: s.mentions, share, baseline, growth, label, isNew, hasHistory: past.length > 0 });
  }
  const gone = new Set(past.slice(0, FADING_RUNS).flatMap((p) => Object.keys(p.shares)));
  for (const key of gone) {
    if (seen.has(key)) continue;
    const peak = Math.max(...past.slice(0, FADING_RUNS).map((p) => p.shares[key] ?? 0));
    if (peak <= 0) continue;
    rows.push({ key, name: key, mentions: 0, share: 0, baseline: mean(recent.map((p) => p.shares[key] ?? 0)), growth: -1, label: "FADING", isNew: false, hasHistory: true });
  }
  return rows.sort((a, b) => labelOrder(a.label) - labelOrder(b.label) || b.growth - a.growth || b.mentions - a.mentions);
}
