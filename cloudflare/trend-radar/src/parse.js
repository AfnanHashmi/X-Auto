// Reading xAI's reply. Strict: a missing field, a wrong type or an unknown sentiment makes the whole run invalid.
// Only post links are filtered (a link that is not a real post link is dropped, never repaired).

import { isPostUrl } from "./schemas.js";

export class ParseError extends Error {}

const SENTIMENTS = ["positive", "negative", "mixed", "neutral"];

const str = (o, k, max) => {
  const v = o?.[k];
  if (typeof v !== "string" || !v.trim()) throw new ParseError(`The answer is missing '${k}'`);
  return v.trim().slice(0, max);
};
const int = (o, k) => {
  const v = o?.[k];
  if (typeof v !== "number" || !Number.isInteger(v) || v < 0) throw new ParseError(`The answer has a bad '${k}'`);
  return v;
};
const arr = (o, k) => {
  if (!Array.isArray(o?.[k])) throw new ParseError(`The answer is missing the list '${k}'`);
  return o[k];
};
const strings = (list, what, max) =>
  list.map((x) => {
    if (typeof x !== "string") throw new ParseError(`The answer has a non-text entry in '${what}'`);
    return x.trim().slice(0, max);
  });
const objects = (list, what) =>
  list.map((x) => {
    if (!x || typeof x !== "object" || Array.isArray(x)) throw new ParseError(`The answer has a malformed entry in '${what}'`);
    return x;
  });

/** The model's JSON answer (the `output_text`). [type] is "PULSE" or "DEEP". */
export function parseReport(text, type) {
  let root;
  try {
    root = JSON.parse(text);
  } catch {
    throw new ParseError("The answer is not valid JSON");
  }
  let dropped = 0;
  const keepUrls = (list) => list.filter((u) => (isPostUrl(u) ? true : (dropped++, false)));
  const subtopics = objects(arr(root, "subtopics"), "subtopics").map((o) => {
    if (!SENTIMENTS.includes(o.sentiment)) throw new ParseError("The answer has an unknown sentiment");
    return {
      name: str(o, "name", 120),
      key: str(o, "key", 80),
      keywords: strings(arr(o, "keywords"), "keywords", 60),
      mentions: int(o, "mentions"),
      sentiment: o.sentiment,
      example_urls: keepUrls(strings(arr(o, "example_urls"), "example_urls", 200)).slice(0, 2),
    };
  });
  const sample_size = int(root, "sample_size");
  if (type === "PULSE") return { sample_size, subtopics, questions: [], reply_targets: [], angles: [], dropped_urls: dropped };

  const reply_targets = [];
  for (const o of objects(arr(root, "reply_targets"), "reply_targets")) {
    const url = str(o, "url", 200);
    if (isPostUrl(url)) reply_targets.push({ url, why: str(o, "why", 200) });
    else dropped++;
  }
  return {
    sample_size,
    subtopics,
    questions: strings(arr(root, "questions"), "questions", 200),
    reply_targets,
    angles: objects(arr(root, "angles"), "angles").map((o) => ({ idea: str(o, "idea", 240), subtopic_key: str(o, "subtopic_key", 80) })),
    dropped_urls: dropped,
  };
}

/** The text of the model's final message in a Responses API reply. */
export function answerText(root) {
  if (typeof root.output_text === "string" && root.output_text.trim()) return root.output_text;
  const output = root.output;
  if (!Array.isArray(output)) throw new ParseError("The reply has no output");
  for (let i = output.length - 1; i >= 0; i--) {
    const item = output[i];
    if (item?.type !== "message" || !Array.isArray(item.content)) continue;
    for (const c of item.content) if (c?.type === "output_text" && typeof c.text === "string" && c.text.trim()) return c.text;
  }
  throw new ParseError("The reply has no answer text");
}

/** What the reply says it used. Throws when there is no usage block, because then the cost is unknown. */
export function usageOf(root) {
  const u = root?.usage;
  if (!u || typeof u !== "object") throw new ParseError("The reply has no usage, so its cost is unknown");
  return u;
}

export function parseJson(body) {
  try {
    return JSON.parse(body);
  } catch {
    throw new ParseError("xAI sent a reply that is not valid JSON");
  }
}

/** A whole xAI reply: finished, with usage, and an answer that passes the schema. Returns {model, report, usage}. */
export function parseXaiResponse(body, type) {
  const root = typeof body === "string" ? parseJson(body) : body;
  if (root.error) throw new ParseError(`xAI reported an error: ${root.error.message ?? "unknown"}`);
  if (root.status !== "completed") throw new ParseError(`The run did not finish (status '${root.status}')`);
  const usage = usageOf(root);
  return { model: root.model ?? "", report: parseReport(answerText(root), type), usage };
}
