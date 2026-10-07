// Pure functions: no network, no storage, so every rule here is unit-tested.

const enc = new TextEncoder();

function toBase64(bytes) {
  let s = "";
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s);
}

/** "sha256=" + base64(HMAC-SHA256(secret, bytes)): the format X uses for CRC answers and POST signatures. */
export async function sign(secret, bytes) {
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = await crypto.subtle.sign("HMAC", key, bytes);
  return "sha256=" + toBase64(mac);
}

/** Constant-time string comparison. */
export function safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const x = enc.encode(a);
  const y = enc.encode(b);
  let diff = x.length ^ y.length;
  const n = Math.max(x.length, y.length);
  for (let i = 0; i < n; i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0;
}

export async function crcResponse(secret, crcToken) {
  return { response_token: await sign(secret, enc.encode(crcToken)) };
}

/** True only when [header] is a valid signature of the raw body bytes. Missing or malformed is false. */
export async function verifySignature(secret, rawBytes, header) {
  if (!header) return false;
  return safeEqual(await sign(secret, rawBytes), header.trim());
}

const NUMERIC_ID = /^[0-9]{1,19}$/;

/**
 * Reads a delivered body into unfollow events. Anything that is not exactly "someone unfollowed the tracked
 * account" is reported as a problem instead of being stored: a wrong target or type means the subscription filter
 * is not what we think, and that would bill events we do not want.
 */
export function parseEvents(body, mainUserId) {
  const items = Array.isArray(body) ? body : body && typeof body === "object" && body.data ? [body] : [];
  const events = [];
  const problems = [];
  if (items.length === 0) problems.push({ kind: "unrecognised", detail: "the body holds no event" });
  for (const item of items) {
    const d = item && item.data;
    if (!d || typeof d !== "object") {
      problems.push({ kind: "unrecognised", detail: "an item has no data" });
      continue;
    }
    if (d.event_type !== "follow.unfollow") {
      problems.push({ kind: "wrong_type", detail: String(d.event_type) });
      continue;
    }
    const src = d.payload && d.payload.source && d.payload.source.data;
    const tgt = d.payload && d.payload.target && d.payload.target.data;
    if (!src || !tgt || !NUMERIC_ID.test(String(src.id)) || !NUMERIC_ID.test(String(tgt.id)) || !d.event_uuid) {
      problems.push({ kind: "unrecognised", detail: "source, target or event_uuid is missing" });
      continue;
    }
    if (String(tgt.id) !== String(mainUserId)) {
      problems.push({ kind: "wrong_target", detail: `target ${tgt.id} is not the tracked account` });
      continue;
    }
    if (String(src.id) === String(mainUserId)) {
      problems.push({ kind: "wrong_target", detail: "the tracked account itself is the one unfollowing" });
      continue;
    }
    const m = src.public_metrics || {};
    events.push({
      event_uuid: String(d.event_uuid),
      user_id: String(src.id),
      username: src.username ?? null,
      name: src.name ?? null,
      followers_count: Number.isInteger(m.followers_count) ? m.followers_count : null,
      following_count: Number.isInteger(m.following_count) ? m.following_count : null,
    });
  }
  return { events, problems };
}

export function utcDay(date) {
  return date.toISOString().slice(0, 10);
}
