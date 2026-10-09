// What Grok is told to do. The API has no parameter for sort order, thread fetching or how many posts to read,
// so those limits are stated here and the REAL numbers are read back from the usage block of every response.

const topicLines = (topic) =>
  [
    `Topic: ${topic.name}`,
    `English keywords: ${topic.keywords_en.join(" | ")}`,
    `Hindi keywords: ${topic.keywords_hi.join(" | ")}`,
    `Hinglish keywords: ${topic.keywords_hinglish.join(" | ")}`,
    `Contestants: ${topic.contestants.join(", ")}`,
    `Show hashtags: ${topic.hashtags.join(" ")}`,
  ].join("\n");

const RULES = [
  "You are a research assistant that only READS X. You never write posts and never suggest automating replies.",
  "Count only posts you actually retrieved in this run. 'mentions' for a sub-topic is how many of THOSE posts discuss it. Never estimate for all of X.",
  "Do not report likes, replies, reposts or views: they are not part of the answer.",
  "Every example URL must be a real post link you retrieved, in the form https://x.com/<handle>/status/<id>. Never guess a link.",
  "Group posts that are about the same thing into one sub-topic. Give each a short lower-case 'key' (3-5 words).",
  "Write in plain English. Keep Hindi/Hinglish names as they are.",
].join("\n");

export function pulseInstructions(topic, { posts, focus }) {
  return [
    RULES,
    "",
    "TASK: a quick pulse check of what people are saying right now.",
    `Use keyword or semantic search sorted by LATEST only. Do NOT fetch threads or replies. Read about ${posts} recent posts, no more.`,
    focus ? `Focus on this sub-topic and anything closely related: ${focus}.` : "",
    "List the sub-topics in the sample with how many of the posts you read mention each.",
    "",
    topicLines(topic),
  ].filter(Boolean).join("\n");
}

export function deepInstructions(topic, { posts, focus }) {
  return [
    RULES,
    "",
    "TASK: a deep scan of the conversation.",
    `Search both TOP and LATEST. You may fetch threads for the most important posts. Read about ${posts} posts.`,
    focus ? `Focus on this sub-topic and anything closely related: ${focus}.` : "",
    "Return: the top sub-topics (at most 8); the main questions people are asking; up to 6 high-engagement posts worth a person replying to, each with a one-line reason (a person will reply, never a bot); and up to 6 angles for the account owner's OWN posts as short ideas only, not finished posts.",
    "",
    topicLines(topic),
  ].filter(Boolean).join("\n");
}
