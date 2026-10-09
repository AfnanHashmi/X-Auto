// JSON schemas for what Grok must return. Strict mode: every property is required and nothing extra is allowed.
// There are deliberately NO likes / replies / views fields: the model must never be able to invent engagement numbers.

const SENTIMENTS = ["positive", "negative", "mixed", "neutral"];

const subtopic = {
  type: "object",
  additionalProperties: false,
  required: ["name", "key", "keywords", "mentions", "sentiment", "example_urls"],
  properties: {
    name: { type: "string", maxLength: 120 },
    // A short lower-case label for matching across runs, e.g. "arishfa kanika fight".
    key: { type: "string", maxLength: 80 },
    keywords: { type: "array", maxItems: 8, items: { type: "string", maxLength: 60 } },
    // How many of the posts actually retrieved in THIS run talk about it. Never an estimate of the whole of X.
    mentions: { type: "integer", minimum: 0, maximum: 1000 },
    sentiment: { type: "string", enum: SENTIMENTS },
    example_urls: { type: "array", maxItems: 2, items: { type: "string", maxLength: 200 } },
  },
};

export const PULSE_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["sample_size", "subtopics"],
  properties: {
    // How many distinct posts you actually looked at.
    sample_size: { type: "integer", minimum: 0, maximum: 1000 },
    subtopics: { type: "array", maxItems: 12, items: subtopic },
  },
};

export const DEEP_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["sample_size", "subtopics", "questions", "reply_targets", "angles"],
  properties: {
    sample_size: { type: "integer", minimum: 0, maximum: 1000 },
    subtopics: { type: "array", maxItems: 8, items: subtopic },
    questions: { type: "array", maxItems: 8, items: { type: "string", maxLength: 200 } },
    reply_targets: {
      type: "array",
      maxItems: 6,
      items: {
        type: "object",
        additionalProperties: false,
        required: ["url", "why"],
        properties: { url: { type: "string", maxLength: 200 }, why: { type: "string", maxLength: 200 } },
      },
    },
    angles: {
      type: "array",
      maxItems: 6,
      items: {
        type: "object",
        additionalProperties: false,
        required: ["idea", "subtopic_key"],
        properties: { idea: { type: "string", maxLength: 240 }, subtopic_key: { type: "string", maxLength: 80 } },
      },
    },
  },
};

const POST_URL = /^https:\/\/(?:x|twitter)\.com\/[A-Za-z0-9_]{1,15}\/status\/[0-9]{1,20}$/;

/** A post link is only kept when it looks exactly like one; anything else is dropped, never repaired. */
export const isPostUrl = (u) => typeof u === "string" && POST_URL.test(u);
