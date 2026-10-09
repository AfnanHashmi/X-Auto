"""Break one safety rule at a time and confirm the tests notice. Every file is restored afterwards.
Run from this folder:  python mutation-check.py"""
import subprocess
import sys

S = "src/"
MUT = [
    (S + "schedule.js", 'if (!st.on) return', 'if (false) return', "session off is ignored"),
    (S + "schedule.js", "if (now >= st.windowEnd)", "if (false)", "runs past the end of the window"),
    (S + "schedule.js", "if (now < st.windowStart)", "if (false)", "runs before the window opens"),
    (S + "schedule.js", "if (used >= 1)", "if (false)", "no stop at 100%"),
    (S + "schedule.js", "const deepOnly = used >= 0.8;", "const deepOnly = false;", "no deep-only mode at 80%"),
    (S + "schedule.js", "!st.deepDone.includes(t)", "true", "finished deep scans run again"),
    (S + "schedule.js", "if (!st.lastLookAt || now - st.lastLookAt >= every) {", "if (true) {", "pulse every minute"),
    (S + "schedule.js", "inPeak(now, config) ? config.pulse.peak_posts : config.pulse.posts", "config.pulse.posts", "no bigger pulse at the peak"),
    (S + "runner.js", "if (!pv.guard.ok) return", "if (false) return", "budget guard skipped"),
    (S + "runner.js", "if (!env.XAI_API_KEY) return", "if (false) return", "runs without a key"),
    (S + "runner.js", "cost = await addRunCost(db, runId, usageOf(root), model, cfg.prices);", "usageOf(root); cost = { total: 0, from_xai: false };", "cost not recorded for a billed run"),
    (S + "runner.js", "if (streak >= 2) {", "if (false) {", "failures never end the session"),
    (S + "runner.js", 'await setState(db, "session_last_look", now);', "", "a failed run is retried every minute"),
    (S + "runner.js", "if (cooldown[row.key] && now - cooldown[row.key] < cfg.spike.cooldown_hours * 3600000) continue;", "", "no spike cooldown"),
    (S + "runner.js", "if (today.n >= cfg.spike.max_per_day) break;", "", "no daily spike limit"),
    (S + "runner.js", "if (lock && now - lock < LOCK_TTL_MS) return", "if (false) return", "overlapping runs allowed"),
    (S + "runner.js", "dry ? 0 : cost.total, runId", "cost.total, runId", "a sample run is billed"),
    (S + "runner.js", "if (root.status !== \"completed\") throw", "if (false) throw", "unfinished replies accepted"),
    (S + "xai.js", "res.status === 429 ||", "false ||", "rate limits not retried"),
    (S + "xai.js", "enable_image_understanding: false", "enable_image_understanding: true", "images switched on"),
    (S + "xai.js", "r.excludedHandles.slice(0, 20)", "r.excludedHandles.slice(0, 200)", "ignore list not capped"),
    (S + "xai.js", "maybeBilled: true });\n      throw new XaiError(`The connection", "maybeBilled: false });\n      throw new XaiError(`The connection", "a timeout is not marked as possibly billed"),
    (S + "budget.js", "if (spentToday + worst > dailyCap) {", "if (false) {", "daily limit not enforced"),
    (S + "budget.js", "if (sessionBudget != null && sessionSpent + worst > sessionBudget) {", "if (false) {", "session limit not enforced"),
    (S + "budget.js", "if (left != null && worst > left) {", "if (false) {", "credits not enforced"),
    (S + "config.js", "if (v !== current.credits_added_usd) next.credits_added_at = nowMs;", "", "credit count not restarted"),
    (S + "config.js", ".slice(0, MAX_EXCLUDED)", "", "ignore list not capped in settings"),
    (S + "analysis.js", "row.isNew && row.hasHistory &&", "row.isNew &&", "the first run counts everything as a spike"),
    (S + "analysis.js", "growth >= RISING_GROWTH &&", "growth >= 0 &&", "anything counts as rising"),
    (S + "match.js", "/ Math.max(wa.size, wb.size) >= 0.6", "/ Math.max(wa.size, wb.size) >= 0.1", "different topics merged"),
    (S + "pricing.js", 'typeof ticks === "number" && ticks >= 0', "false", "xAI's own cost ignored"),
    (S + "session.js", "return { ok: false, reason: requestedBudget == null", "return { ok: true, reason: requestedBudget == null", "a too-small session limit accepted"),
    (S + "index.js", "return Boolean(env.TREND_APP_TOKEN) && safeEqual(auth, `Bearer ${env.TREND_APP_TOKEN}`);", "return true;", "API open to anyone"),
    (S + "index.js", 'if (jobs.length || (await env.DB.prepare("SELECT id FROM runs WHERE status = \'running\' LIMIT 1").first())) {', "if (false) {", "several runs can be queued"),
]


def run_tests():
    return subprocess.run("npm test", shell=True, capture_output=True, text=True, encoding="utf-8", errors="replace")


base = run_tests()
print("BASELINE exit", base.returncode)
if base.returncode != 0:
    print(base.stdout[-800:])
    sys.exit(1)
caught = missed = invalid = 0
for path, old, new, name in MUT:
    s = open(path, encoding="utf-8").read()
    if s.count(old) != 1:
        print("[SKIP ]", name, "pattern count", s.count(old))
        invalid += 1
        continue
    try:
        open(path, "w", encoding="utf-8", newline="").write(s.replace(old, new, 1))
        r = run_tests()
    finally:
        open(path, "w", encoding="utf-8", newline="").write(s)
    if r.returncode != 0:
        print("[caught]", name)
        caught += 1
    else:
        print("[MISSED]", name)
        missed += 1
    sys.stdout.flush()
print(f"SUMMARY caught={caught} missed={missed} invalid={invalid}")
