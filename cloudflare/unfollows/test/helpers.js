import { createHmac } from "node:crypto";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { MAIN } from "./fixture.js";

export const SECRET = "consumer-secret-for-tests";
export const APP_TOKEN = "app-token-for-tests";
export const NOW = new Date("2026-10-07T10:00:00Z");
export const GROUP = "YOUR_GROUP_CHAT_ID";
export const RW = "YOUR_RW_USER_ID";
export const DK = "YOUR_DK_USER_ID";

/** Cloudflare D1's API over an in-memory SQLite, running the real schema.sql. */
export function fakeD1() {
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

export function setup(over = {}) {
  const DB = fakeD1();
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, method: init?.method });
    return { ok: true, status: 200 };
  };
  const env = {
    DB, CONSUMER_SECRET: SECRET, BEARER: "bearer", APP_TOKEN, MAIN_USER_ID: MAIN, DAILY_CAP: "300", SUBSCRIPTION_ID: "sub1",
    GC_GROUP_ID: GROUP, RW_USER_ID: RW, DK_USER_ID: DK, KICK_DELAY_MIN: "30", KICK_DAILY_CAP: "20", KICK_SPACING_MS: "0",
    X_RW_CONSUMER_KEY: "rwck", X_RW_CONSUMER_SECRET: "rwcs", X_RW_ACCESS_TOKEN: "rwat", X_RW_ACCESS_SECRET: "rwats",
    X_MAIN_CONSUMER_KEY: "mnck", X_MAIN_ACCESS_TOKEN: "mnat", X_MAIN_ACCESS_SECRET: "mnats",
    ...over,
  };
  return { DB, env, calls, fetchImpl };
}

export const sig = (body) => "sha256=" + createHmac("sha256", SECRET).update(body).digest("base64");
export const post = (body, headers = {}) => new Request("https://w/x/webhook", { method: "POST", body, headers });
export const signed = (obj) => {
  const body = JSON.stringify(obj);
  return post(body, { "x-twitter-webhooks-signature": sig(body) });
};
export const count = (DB) => DB.raw.prepare("SELECT COUNT(*) AS n FROM events").get().n;
export const state = (DB, k) => DB.raw.prepare("SELECT value FROM state WHERE key = ?").get(k)?.value ?? null;
export const kickRow = (DB, uuid) => DB.raw.prepare("SELECT * FROM kicks WHERE event_uuid = ?").get(uuid);
export const authed = (path, init = {}) =>
  new Request(`https://w${path}`, { ...init, headers: { authorization: `Bearer ${APP_TOKEN}`, ...(init.headers || {}) } });
