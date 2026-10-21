package com.clipcut.domain.taglines

const val FX_END = 1_792_271_838_261L
const val FX_START = FX_END - 24 * HOUR_MS

fun fxStats(natural: Int = 38) = SampleStats(
    sample = 200, originals = 90, naturalPct = natural, botLikePct = 22, copyPastePct = 41, newAccountPct = 17, lowReachPct = 30, fanClubPct = 12,
    top10AuthorsPct = 25, medianLikes = 0, zeroLikePct = 70,
    organizers = listOf("@fan_account_one" to 12, "not a handle" to 3), pairedHashtags = listOf("#BiggBoss20" to 40, "bad tag" to 2),
    topMentions = listOf("@JioHotstar" to 9), languages = listOf("hi" to 120), indiaPctOfLocated = 80, mediaPct = 10,
)

fun fxCheck(tagline: String, natural: Int = 38, withSample: Boolean = true, atMs: Long = FX_END + 900_000L, all: Long = 49_107L): TrendCheckResult = TrendCheckResult(
    tagline = tagline, startMs = FX_START, endMs = FX_END, ended = true, atMs = atMs,
    shares = CountShares(all, all / 10, all / 50, all / 100),
    hourly = listOf(HourBucket(FX_END - 2 * HOUR_MS, all / 4), HourBucket(FX_END - HOUR_MS, all - all / 4)),
    launch = LaunchType.SUDDEN, waves = listOf(FX_END - HOUR_MS),
    stats = if (withSample) fxStats(natural) else null,
    sentiment = if (withSample) Sentiment(60, 20, 20, mapOf("Qazi Jee" to Triple(30, 5, 5), "Nakul" to Triple(5, 12, 3)), 100, "OpenAI · gpt-5.6-terra", 0.01) else null,
    postsRead = if (withSample) 200 else 0, readHours = if (withSample) listOf(FX_END - 2 * HOUR_MS, FX_END - HOUR_MS) else emptyList(),
    costUsd = if (withSample) 0.9 else null,
)
