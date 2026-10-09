// What a run costs. Prices are configuration (docs.x.ai/developers/models and /tools/x-search, checked 2026-10-09), but
// the figure that is stored is xAI's own `cost_in_usd_ticks` whenever the reply carries it: it matched this arithmetic to
// four decimals in the first measurements, so the arithmetic is only the fallback.

export const TOKEN_RATES = {
  // USD per million tokens: input, cached input, output
  "grok-4.7": { input: 2.0, cached: 0.5, output: 6.0 },
  "grok-4.3": { input: 1.25, cached: 0.2, output: 2.5 },
};
export const LONG_PROMPT_TOKENS = 200000; // rates double once one request's prompt reaches this
const USD_PER_TICK = 1e-10;

/** A response's cost, from its usage block. Unknown models are charged at the dearest known rate. */
export function costOf(usage, model, prices = { per_1k_posts: 5, per_1k_users: 10 }) {
  const u = usage || {};
  const tool = u.server_side_tool_usage_details || {};
  const rates = TOKEN_RATES[model] || Object.values(TOKEN_RATES).reduce((a, b) => (b.output > a.output ? b : a));
  const input = u.input_tokens ?? 0;
  const cached = u.input_tokens_details?.cached_tokens ?? 0;
  const output = u.output_tokens ?? 0; // includes reasoning tokens
  const long = input >= LONG_PROMPT_TOKENS ? 2 : 1;
  const tokens = (((input - cached) * rates.input + cached * rates.cached + output * rates.output) * long) / 1e6;
  const posts = ((tool.x_posts_fetched ?? 0) * prices.per_1k_posts) / 1000;
  const users = ((tool.x_users_fetched ?? 0) * prices.per_1k_users) / 1000;
  const ticks = u.cost_in_usd_ticks;
  if (typeof ticks === "number" && ticks >= 0) return { tokens, posts, users, total: ticks * USD_PER_TICK, from_xai: true };
  return { tokens, posts, users, total: tokens + posts + users, from_xai: false };
}

// The first measurements, used until real runs of the same kind exist.
const FIRST = { "PULSE:grok-4.3": 0.106, "PULSE:grok-4.7": 0.198, "DEEP:grok-4.3": 0.307, "DEEP:grok-4.7": 0.84 };

/** A likely cost: the average of recent runs of the same type and model; a pulse scales with the size asked for. */
export function estimate(type, model, posts, recentCosts, basePulsePosts = 20) {
  const base = recentCosts.length ? recentCosts.reduce((a, b) => a + b, 0) / recentCosts.length : FIRST[`${type}:${model}`] ?? Math.max(...Object.values(FIRST));
  if (type === "DEEP") return base;
  return base * Math.max(0.5, posts / basePulsePosts);
}

/** Posts read overshot the target by up to 1.5x in the first measurements, and the API cannot cap it. */
export const worstCase = (likely, factor = 1.5) => likely * factor;
