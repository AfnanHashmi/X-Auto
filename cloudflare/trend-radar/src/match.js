// Sub-topics from different runs are the same thing when their normalized keys match. Normalizing is: lower-case, no '#',
// no punctuation, filler words and lone letters dropped, common Hinglish spelling variants merged, words sorted. A new
// key that is merely similar to a known one (most words shared, or a one-fifth edit distance) is treated as the known key.

const FILLER = new Set(["the", "a", "an", "of", "and", "in", "on", "to", "is", "are", "for", "with", "about", "ka", "ki", "ke", "ko", "hai", "aur", "ye", "wo", "vs", "v"]);

// Spelling variants of names that fans write many ways. Edit freely; unknown words pass through.
const ALIASES = {
  touqeer: "touqeer", tauqeer: "touqeer", tauqir: "touqeer", touqir: "touqeer", toqeer: "touqeer",
  kazi: "qazi", qazi: "qazi", kaazi: "qazi",
  arishfa: "arishfa", arishfaa: "arishfa", arisha: "arishfa", arshifa: "arishfa",
  kanika: "kanika", kanikaa: "kanika",
  uditi: "uditi", udhiti: "uditi", udiiti: "uditi",
  yungdsa: "yungdsa", yung: "yungdsa", dsa: "yungdsa",
  bb20: "bb20", bigboss20: "bb20", biggboss20: "bb20",
  wkv: "weekend", weekendkavaar: "weekend", weekend: "weekend",
  eviction: "evict", evicted: "evict", evictions: "evict", nomination: "nominate", nominations: "nominate", nominated: "nominate",
  fights: "fight", fighting: "fight", drama: "fight", jhagda: "fight",
};

export function normalize(text) {
  const words = String(text)
    .toLowerCase()
    .replace(/#/g, "")
    .replace(/[^\p{L}\p{N}\s]/gu, " ")
    .split(/\s+/)
    .filter((w) => w && !FILLER.has(w) && (w.length > 1 || /\d/.test(w)))
    .map((w) => ALIASES[w] ?? w);
  return [...new Set(words)].sort().join(" ");
}

const wordSet = (key) => new Set(key.split(" ").filter(Boolean));

function editDistance(a, b) {
  if (a === b) return 0;
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++) cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    prev = cur;
  }
  return prev[b.length];
}

/** True when two normalized keys are the same thing: 60% of the words shared, or at most a fifth of the letters differ. */
export function similar(a, b) {
  if (!a || !b) return false;
  if (a === b) return true;
  const wa = wordSet(a);
  const wb = wordSet(b);
  let shared = 0;
  for (const w of wa) if (wb.has(w)) shared++;
  if (shared / Math.max(wa.size, wb.size) >= 0.6) return true;
  return editDistance(a, b) / Math.max(a.length, b.length) <= 0.2;
}

/** The key to store a sub-topic under: an existing similar one if there is one, otherwise the new normalized key. */
export function matchKey(rawKeyOrName, known) {
  const key = normalize(rawKeyOrName);
  const list = [...known];
  if (list.includes(key)) return key;
  return list.find((k) => similar(key, k)) ?? key;
}

/** The same sub-topic can come back twice under different names in one answer: merge those into one. */
export function mergeByKey(subtopics, known) {
  const out = new Map();
  for (const s of subtopics) {
    const key = matchKey(s.key || s.name, [...known, ...out.keys()]);
    const e = out.get(key);
    out.set(
      key,
      e
        ? {
            ...e,
            mentions: e.mentions + s.mentions,
            keywords: [...new Set([...e.keywords, ...s.keywords])].slice(0, 8),
            example_urls: [...new Set([...e.example_urls, ...s.example_urls])].slice(0, 2),
          }
        : s,
    );
  }
  return [...out.entries()];
}
