import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { istInstant, minutesOf } from "../src/schedule.js";

export const TOKEN = "app-token-for-tests";

/** An IST wall-clock time as an instant: ist("2026-10-12", "12:00"). Mon 12 Oct 2026, Sat 10, Sun 11. */
export const ist = (date, hhmm) => istInstant(date, minutesOf(hhmm));

export const fixture = (name) => readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url), "utf8");

/** Cloudflare D1's API over an in-memory SQLite, running the real schema.sql. */
export function fakeD1() {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(new URL("../schema.sql", import.meta.url), "utf8"));
  return {
    prepare(sql) {
      const exec = (args) => ({
        run: async () => {
          const r = db.prepare(sql).run(...args);
          return { meta: { changes: Number(r.changes), last_row_id: Number(r.lastInsertRowid) } };
        },
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
  const env = { DB, XAI_API_KEY: "xai-test-key", TREND_APP_TOKEN: TOKEN, ...over };
  return { DB, env };
}

/** A xAI reply body for the given sub-topics: [[name, key, mentions]]. */
export function reply(subs, { posts = 20, ticks = 1_000_000_000, deep = false, status = "completed" } = {}) {
  const answer = {
    sample_size: posts,
    subtopics: subs.map(([name, key, mentions]) => ({ name, key, keywords: ["k"], mentions, sentiment: "mixed", example_urls: ["https://x.com/a/status/1"] })),
  };
  if (deep) {
    answer.questions = ["Who goes home?"];
    answer.reply_targets = [{ url: "https://x.com/b/status/2", why: "asks a question" }];
    answer.angles = [{ idea: "Poll fans", subtopic_key: subs[0][1] }];
  }
  return JSON.stringify({
    status,
    model: "m",
    output_text: JSON.stringify(answer),
    usage: { input_tokens: 1000, output_tokens: 500, cost_in_usd_ticks: ticks, server_side_tool_usage_details: { x_posts_fetched: posts, x_search_calls: 2, x_users_fetched: 0 } },
  });
}

/** A fake xAI: queue replies, record every request. Anything other than api.x.ai is a test failure. */
export function fakeXai() {
  const x = {
    requests: [],
    next: () => {
      throw new Error("no reply queued");
    },
    fetch: async (url, init) => {
      if (url !== "https://api.x.ai/v1/responses") throw new Error(`unexpected request to ${url}`);
      const body = JSON.parse(init.body);
      x.requests.push({ url, body, headers: init.headers });
      const out = x.next(body);
      if (out instanceof Error) throw out;
      if (typeof out === "object") return { ok: out.status < 300, status: out.status, headers: new Headers(out.headers ?? {}), text: async () => out.body ?? "" };
      return { ok: true, status: 200, headers: new Headers(), text: async () => out };
    },
    sleep: async () => {},
  };
  return x;
}

export const runs = (DB) => DB.raw.prepare("SELECT * FROM runs ORDER BY id").all();
export const state = (DB, k) => DB.raw.prepare("SELECT value FROM state WHERE key = ?").get(k)?.value ?? null;
export const putState = (DB, k, v) => DB.raw.prepare("INSERT INTO state(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").run(k, String(v));
export const authed = (path, init = {}) => new Request(`https://w${path}`, { ...init, headers: { authorization: `Bearer ${TOKEN}`, ...(init.headers || {}) } });
