// xAI's Responses API with the x_search tool. This is the ONLY outbound request the Worker ever makes, and it only reads.
//
// Retries: only on 429 (rate limited) and 5xx, twice, with a pause. A TIMEOUT is never retried: the request may already be
// running and billed on xAI's side, and a second attempt would pay again. It is reported as "may have been billed".

export class XaiError extends Error {
  constructor(message, { status = null, maybeBilled = false } = {}) {
    super(message);
    this.status = status;
    this.maybeBilled = maybeBilled;
  }
}

export const XAI_URL = "https://api.x.ai/v1/responses";
const MAX_RETRIES = 2;

/**
 * The request body. X Search is the ONLY tool offered, so the model has no way to do anything but read. Images and video
 * are not looked at. The exclusion list is capped at the 20 handles X accepts.
 */
export function requestBody(r) {
  const tool = { type: "x_search", from_date: r.fromDate, to_date: r.toDate, enable_image_understanding: false, enable_video_understanding: false };
  if (r.excludedHandles?.length) tool.excluded_x_handles = r.excludedHandles.slice(0, 20);
  return {
    model: r.model,
    instructions: r.instructions,
    input: r.input,
    tools: [tool],
    text: { format: { type: "json_schema", name: r.schemaName, schema: r.schema, strict: true } },
    max_turns: r.maxTurns,
  };
}

const errorText = (text) => {
  try {
    const j = JSON.parse(text);
    return j?.error?.message ?? (typeof j?.error === "string" ? j.error : text.slice(0, 200));
  } catch {
    return text.slice(0, 200);
  }
};

/** The raw reply body (text). Throws XaiError. [deps] = {fetch, sleep} for tests. */
export async function callXai(apiKey, request, deps = {}) {
  const doFetch = deps.fetch ?? fetch;
  const sleep = deps.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
  const body = JSON.stringify(requestBody(request));
  for (let attempt = 1; ; attempt++) {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), request.timeoutSec * 1000);
    let res;
    try {
      res = await doFetch(XAI_URL, { method: "POST", headers: { authorization: `Bearer ${apiKey}`, "content-type": "application/json" }, body, signal: ctl.signal });
    } catch (e) {
      clearTimeout(timer);
      if (e?.name === "AbortError") throw new XaiError(`xAI did not answer within ${request.timeoutSec} seconds. The run may still have been carried out and billed.`, { maybeBilled: true });
      throw new XaiError(`The connection to xAI failed (${e?.message}). The run may have been carried out and billed.`, { maybeBilled: true });
    }
    let text;
    try {
      text = await res.text();
    } catch (e) {
      clearTimeout(timer);
      throw new XaiError(`xAI's answer was cut off (${e?.message}). The run may have been billed.`, { maybeBilled: true });
    }
    clearTimeout(timer);
    if (res.ok) return text;
    const retryable = res.status === 429 || (res.status >= 500 && res.status <= 599);
    if (!retryable || attempt > MAX_RETRIES) throw new XaiError(`xAI answered HTTP ${res.status}: ${errorText(text)}`, { status: res.status });
    const retryAfter = Number(res.headers?.get?.("retry-after"));
    await sleep(Math.min(30000, Math.max(1000, Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter * 1000 : attempt * 4000)));
  }
}
