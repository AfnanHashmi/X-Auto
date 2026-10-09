// The cost guard. Nothing here can see your real xAI balance: it works from what the radar itself recorded spending.
// A run is checked at its WORST case, because the number of posts a run reads cannot be capped through the API.

const usd = (v) => "$" + v.toFixed(2);

export const creditsLeft = (creditsAdded, spentSince) => (creditsAdded == null ? null : Math.max(0, creditsAdded - spentSince));

/**
 * {ok: true, ...} or {ok: false, reason}. [sessionBudget]/[sessionSpent] are given only for runs that belong to a day
 * session (scheduled and spike runs); manual runs are checked against the daily limit and the credits only.
 */
export function check({ worst, spentToday, dailyCap, creditsAdded, spentSinceCredits, sessionBudget = null, sessionSpent = 0 }) {
  if (spentToday + worst > dailyCap) {
    return {
      ok: false,
      kind: "daily",
      reason: `Today's limit of ${usd(dailyCap)} would be passed: ${usd(spentToday)} already spent today and this run could cost up to ${usd(worst)}. You can raise the limit in Settings.`,
    };
  }
  if (sessionBudget != null && sessionSpent + worst > sessionBudget) {
    return {
      ok: false,
      kind: "session",
      reason: `This session's limit of ${usd(sessionBudget)} would be passed: ${usd(sessionSpent)} spent and this run could cost up to ${usd(worst)}.`,
    };
  }
  const left = creditsLeft(creditsAdded, spentSinceCredits);
  if (left != null && worst > left) {
    return {
      ok: false,
      kind: "credits",
      reason: `Only ${usd(left)} is left of the credits you said you added, and this run could cost up to ${usd(worst)}. Add credits on xAI, then update the amount in Settings.`,
    };
  }
  return { ok: true, spentToday, dailyCap, creditsLeft: left };
}
