import test from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { authorizationHeader, baseString, percentEncode, queryString } from "../src/oauth1.js";

// The worked example from X's own documentation ("Creating a signature"), with its published signature.
const DOC = {
  method: "POST",
  url: "https://api.twitter.com/1.1/statuses/update.json",
  params: { include_entities: "true", status: "Hello Ladies + Gentlemen, a signed OAuth request!" },
  creds: {
    consumerKey: "xvz1evFS4wEEPTGEFPHBog",
    consumerSecret: "kAcSOqF21Fu85e7zjz7ZN2U4ZRhfV3WpwPAoE3Z7kBw",
    token: "370773112-GmHxMAgYyLbNEtIKZeRNFsMKPR9EyMZeS9weJAEb",
    tokenSecret: "LswwdoUaIvS8ltyTt5jkRh4J50vUPVVHtR2YPi5kE",
  },
  nonce: "kYjzVBB8Y0ZFabxSWbWovY3uYSQ2pTgmZeNu2VS4cg",
  timestamp: 1318622958,
  signature: "hCtSmYh+iHYCEqBWrE7C7hYmtUk=",
};

test("matches the signature published in X's OAuth 1.0a documentation", async () => {
  const h = await authorizationHeader(DOC.method, DOC.url, DOC.params, DOC.creds, DOC.nonce, DOC.timestamp);
  assert.ok(h.includes(`oauth_signature="${percentEncode(DOC.signature)}"`), h);
});

test("agrees with an independent HMAC-SHA1 implementation on an arbitrary request", async () => {
  const creds = { consumerKey: "ck", consumerSecret: "c&s", token: "tk", tokenSecret: "t s" };
  const params = { ids: "1,2,3", "user.fields": "connection_status,username" };
  const h = await authorizationHeader("GET", "https://api.x.com/2/users", params, creds, "nonce123", 1790000000);
  const base = baseString("GET", "https://api.x.com/2/users", {
    ...params, oauth_consumer_key: "ck", oauth_nonce: "nonce123", oauth_signature_method: "HMAC-SHA1",
    oauth_timestamp: "1790000000", oauth_token: "tk", oauth_version: "1.0",
  });
  const expected = createHmac("sha1", `${percentEncode("c&s")}&${percentEncode("t s")}`).update(base).digest("base64");
  assert.ok(h.includes(`oauth_signature="${percentEncode(expected)}"`));
});

test("the JSON body is not part of the signature, and parameters are sorted", () => {
  assert.equal(baseString("post", "https://a/b", { b: "2", a: "1" }), "POST&https%3A%2F%2Fa%2Fb&a%3D1%26b%3D2");
});

test("percentEncode is RFC 3986 strict", () => {
  assert.equal(percentEncode("a b!'()*~-._"), "a%20b%21%27%28%29%2A~-._");
});

test("the query string is encoded exactly as it is signed", () => {
  assert.equal(queryString({ ids: "1,2", "user.fields": "a b" }), "ids=1%2C2&user.fields=a%20b");
});

test("every call gets a fresh nonce", async () => {
  const creds = DOC.creds;
  const a = await authorizationHeader("GET", "https://a/b", {}, creds);
  const b = await authorizationHeader("GET", "https://a/b", {}, creds);
  assert.notEqual(a.match(/oauth_nonce="([^"]+)"/)[1], b.match(/oauth_nonce="([^"]+)"/)[1]);
});
