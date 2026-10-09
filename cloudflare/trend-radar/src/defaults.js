// Starting configuration. Everything here is editable later from the app and stored in the database.
// All times are IST (UTC+5:30, no daylight saving).

export const IST_OFFSET_MIN = 330;

export const DEFAULT_CONFIG = {
  topics: [
    {
      name: "Bigg Boss 20",
      keywords_en: ["Bigg Boss 20", "BB20", "Bigg Boss 20 episode", "Bigg Boss 20 eviction", "Bigg Boss 20 nomination", "Weekend Ka Vaar"],
      keywords_hi: ["बिग बॉस 20", "बिग बॉस 20 एपिसोड", "बिग बॉस 20 एविक्शन"],
      keywords_hinglish: ["bigg boss 20 kal ka episode", "bb20 aaj ka episode", "bigg boss 20 kaun nikla", "bigg boss 20 jhagda", "bigg boss 20 nominations"],
      contestants: ["Qazi Touqeer", "Arishfa Khan", "Uditi Singh", "YungDSA", "Kanika Mann", "Gullu"],
      hashtags: ["#BiggBoss20", "#QaziTouqeer", "#ArishfaKhan", "#UditiSingh", "#YungDSA", "#KanikaMann", "#Gullu"],
    },
  ],
  // Spam or bot accounts to ignore. X allows at most 20 and it cannot be combined with an allow-list.
  excluded_x_handles: [],
  pulse_model: "grok-4.3",
  deep_model: "grok-4.7",
  pulse_sizes: [20, 40, 50],
  pulse: { posts: 20, peak_posts: 40, max_turns: 1, days_back: 1 },
  deep: { posts: 90, max_turns: 2, days_back: 2 },
  timeouts_sec: { pulse: 120, deep: 300 },
  daily_cap_usd: 6,
  // Optional: the credits you said you added; the radar subtracts what its own runs spent since credits_added_at.
  credits_added_usd: null,
  credits_added_at: 0,
  prices: { per_1k_posts: 5, per_1k_users: 10 },
  schedule: {
    // Each day names a profile. A window that ends after midnight belongs to the day it started.
    days: { mon: "weekday", tue: "weekday", wed: "weekday", thu: "weekday", fri: "weekday", sat: "weekend", sun: "weekend" },
    profiles: {
      weekday: { start: "12:00", end: "01:00", deep_times: ["12:00", "18:00", "23:00"] },
      weekend: { start: "20:00", end: "01:00", deep_times: ["20:00", "23:00"] },
    },
    pulse_every_min: 60,
    // Episode airtime (9 PM) to +2 h: pulses use the larger size.
    peak: { start: "21:00", end: "23:00" },
  },
  spike: { min_mentions: 3, cooldown_hours: 3, max_per_day: 3 },
  worst_case_factor: 1.5,
};
