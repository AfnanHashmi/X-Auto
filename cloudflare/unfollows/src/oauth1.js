// OAuth 1.0a request signing (HMAC-SHA1) with Web Crypto, for the calls the Worker makes to X as RW and as main.
// Query parameters are signed AND sent in the same encoding; a JSON body is not part of the signature.

const enc = new TextEncoder();

/** RFC 3986 percent-encoding, which is stricter than encodeURIComponent. */
export function percentEncode(s) {
  return encodeURIComponent(String(s)).replace(/[!'()*]/g, (c) => "%" + c.charCodeAt(0).toString(16).toUpperCase());
}

function toBase64(bytes) {
  let s = "";
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s);
}

async function hmacSha1(key, message) {
  const k = await crypto.subtle.importKey("raw", enc.encode(key), { name: "HMAC", hash: "SHA-1" }, false, ["sign"]);
  return toBase64(await crypto.subtle.sign("HMAC", k, enc.encode(message)));
}

/** The signature base string: METHOD & encoded URL & encoded, sorted, encoded parameters. */
export function baseString(method, url, params) {
  const pairs = Object.entries(params).map(([k, v]) => [percentEncode(k), percentEncode(v)]);
  pairs.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : a[1] < b[1] ? -1 : a[1] > b[1] ? 1 : 0));
  return [method.toUpperCase(), percentEncode(url), percentEncode(pairs.map(([k, v]) => `${k}=${v}`).join("&"))].join("&");
}

/**
 * The Authorization header for a request. [creds] is {consumerKey, consumerSecret, token, tokenSecret}; [params] are
 * the query parameters (not the JSON body). [nonce] and [timestamp] are only supplied by tests.
 */
export async function authorizationHeader(method, url, params, creds, nonce, timestamp) {
  const oauth = {
    oauth_consumer_key: creds.consumerKey,
    oauth_nonce: nonce ?? crypto.randomUUID().replace(/-/g, ""),
    oauth_signature_method: "HMAC-SHA1",
    oauth_timestamp: String(timestamp ?? Math.floor(Date.now() / 1000)),
    oauth_token: creds.token,
    oauth_version: "1.0",
  };
  const base = baseString(method, url, { ...params, ...oauth });
  oauth.oauth_signature = await hmacSha1(`${percentEncode(creds.consumerSecret)}&${percentEncode(creds.tokenSecret)}`, base);
  return "OAuth " + Object.entries(oauth).map(([k, v]) => `${percentEncode(k)}="${percentEncode(v)}"`).sort().join(", ");
}

/** The query string with every value encoded the same way it was signed. */
export function queryString(params) {
  return Object.entries(params).map(([k, v]) => `${percentEncode(k)}=${percentEncode(v)}`).join("&");
}
