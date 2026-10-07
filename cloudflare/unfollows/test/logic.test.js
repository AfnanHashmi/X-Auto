import test from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { crcResponse, parseEvents, safeEqual, sign, utcDay, verifySignature } from "../src/logic.js";
import { MAIN, unfollowEvent } from "./fixture.js";

const SECRET = "consumer-secret-for-tests";
const enc = new TextEncoder();
const nodeSig = (secret, data) => "sha256=" + createHmac("sha256", secret).update(data).digest("base64");

test("the CRC answer matches an independent HMAC-SHA256 implementation", async () => {
  const r = await crcResponse(SECRET, "challenge_string_123");
  assert.equal(r.response_token, nodeSig(SECRET, "challenge_string_123"));
});

test("a valid signature over the raw bytes verifies", async () => {
  const body = enc.encode(JSON.stringify(unfollowEvent()));
  assert.equal(await verifySignature(SECRET, body, nodeSig(SECRET, body)), true);
});

test("a tampered body, a wrong secret, a missing or malformed header all fail", async () => {
  const body = enc.encode(JSON.stringify(unfollowEvent()));
  const good = nodeSig(SECRET, body);
  assert.equal(await verifySignature(SECRET, enc.encode(JSON.stringify(unfollowEvent({ uuid: "other" }))), good), false);
  assert.equal(await verifySignature("another-secret", body, good), false);
  assert.equal(await verifySignature(SECRET, body, null), false);
  assert.equal(await verifySignature(SECRET, body, ""), false);
  assert.equal(await verifySignature(SECRET, body, "sha256=notbase64!!"), false);
});

test("safeEqual compares whole strings", () => {
  assert.equal(safeEqual("abc", "abc"), true);
  assert.equal(safeEqual("abc", "abd"), false);
  assert.equal(safeEqual("abc", "abcd"), false);
  assert.equal(safeEqual("abc", undefined), false);
});

test("sign is stable and prefixed", async () => {
  assert.match(await sign("k", enc.encode("m")), /^sha256=[A-Za-z0-9+/]+=*$/);
});

test("an unfollow of the tracked account becomes an event", () => {
  const { events, problems } = parseEvents(unfollowEvent(), MAIN);
  assert.deepEqual(problems, []);
  assert.deepEqual(events, [
    { event_uuid: "-4233426745281461957", user_id: "1111111111111111111", username: "ExampleUser", name: "Example User", followers_count: 5502, following_count: 651 },
  ]);
});

test("a batch of events is read one by one", () => {
  const { events } = parseEvents([unfollowEvent({ uuid: "a", sourceId: "1" }), unfollowEvent({ uuid: "b", sourceId: "2" })], MAIN);
  assert.deepEqual(events.map((e) => e.event_uuid), ["a", "b"]);
});

test("an event about someone else being unfollowed is a wrong_target problem, never stored", () => {
  const { events, problems } = parseEvents(unfollowEvent({ targetId: "999" }), MAIN);
  assert.equal(events.length, 0);
  assert.equal(problems[0].kind, "wrong_target");
});

test("the tracked account unfollowing someone else (outbound) is a wrong_target problem", () => {
  const { events, problems } = parseEvents(unfollowEvent({ sourceId: MAIN, targetId: MAIN }), MAIN);
  assert.equal(events.length, 0);
  assert.equal(problems[0].kind, "wrong_target");
});

test("any other event type is a wrong_type problem", () => {
  const { events, problems } = parseEvents(unfollowEvent({ type: "follow.follow" }), MAIN);
  assert.equal(events.length, 0);
  assert.equal(problems[0].kind, "wrong_type");
});

test("bodies that are not events are unrecognised, not events", () => {
  for (const body of [{}, [], null, "x", { data: "x" }, { data: { event_type: "follow.unfollow" } }]) {
    const { events, problems } = parseEvents(body, MAIN);
    assert.equal(events.length, 0, JSON.stringify(body));
    assert.ok(problems.length > 0);
  }
});

test("ids must be numeric and an event_uuid is required", () => {
  assert.equal(parseEvents(unfollowEvent({ sourceId: "abc" }), MAIN).events.length, 0);
  assert.equal(parseEvents(unfollowEvent({ uuid: "" }), MAIN).events.length, 0);
});

test("utcDay is the UTC date", () => {
  assert.equal(utcDay(new Date("2026-10-07T23:59:59Z")), "2026-10-07");
  assert.equal(utcDay(new Date("2026-10-08T00:00:00Z")), "2026-10-08");
});
