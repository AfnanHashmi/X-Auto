// Storage helpers over D1: the key/value state table, the configuration, and the queries the radar needs.

import { mergeConfig } from "./config.js";
import { istDate, istInstant } from "./schedule.js";

export async function getState(db, key) {
  const row = await db.prepare("SELECT value FROM state WHERE key = ?").bind(key).first();
  return row ? row.value : null;
}

export async function setState(db, key, value) {
  await db
    .prepare("INSERT INTO state(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
    .bind(key, String(value))
    .run();
}

export async function clearState(db, key) {
  await db.prepare("DELETE FROM state WHERE key = ?").bind(key).run();
}

export async function getJson(db, key, fallback) {
  const v = await getState(db, key);
  if (v == null) return fallback;
  try {
    return JSON.parse(v);
  } catch {
    return fallback;
  }
}

export const setJson = (db, key, value) => setState(db, key, JSON.stringify(value));

export async function getConfig(db) {
  return mergeConfig(await getJson(db, "config", null));
}

export const saveConfig = (db, cfg) => setJson(db, "config", cfg);

/** Instants (ms) at which today / this month begin on the IST calendar. */
export function dayStart(now) {
  return istInstant(istDate(now).date, 0);
}
export function monthStart(now) {
  return istInstant(istDate(now).date.slice(0, 8) + "01", 0);
}

/** Money actually spent since [sinceMs]: every real run counts, including ones whose answer was unusable. */
export async function spentSince(db, sinceMs) {
  const r = await db.prepare("SELECT COALESCE(SUM(cost_usd), 0) AS s FROM runs WHERE status != 'dry_run' AND started_at >= ?").bind(sinceMs).first();
  return Number(r.s);
}

export async function sessionSpent(db, sessionId) {
  if (!sessionId) return 0;
  const r = await db.prepare("SELECT COALESCE(SUM(cost_usd), 0) AS s FROM runs WHERE session_id = ?").bind(sessionId).first();
  return Number(r.s);
}

export async function recentCosts(db, type, model, n = 5) {
  const rows = await db.prepare("SELECT cost_usd FROM runs WHERE status = 'ok' AND type = ? AND model = ? ORDER BY started_at DESC LIMIT ?").bind(type, model, n).all();
  return rows.results.map((r) => Number(r.cost_usd));
}

export async function knownKeys(db) {
  const rows = await db.prepare("SELECT DISTINCT s.key AS key FROM snapshots s JOIN runs r ON r.id = s.run_id WHERE r.status = 'ok'").all();
  return rows.results.map((r) => r.key);
}

/** The runs that count as history for trends: real and successful, before [beforeMs], newest first, with their shares. */
export async function pastRuns(db, beforeMs, limit) {
  const runs = (await db.prepare("SELECT id, started_at FROM runs WHERE status = 'ok' AND started_at < ? ORDER BY started_at DESC LIMIT ?").bind(beforeMs, limit).all()).results;
  const out = [];
  for (const r of runs) {
    const snaps = (await db.prepare("SELECT key, share FROM snapshots WHERE run_id = ?").bind(r.id).all()).results;
    out.push({ atMs: Number(r.started_at), shares: Object.fromEntries(snaps.map((s) => [s.key, Number(s.share)])) });
  }
  return out;
}
