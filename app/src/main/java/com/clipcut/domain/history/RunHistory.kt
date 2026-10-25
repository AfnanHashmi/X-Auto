package com.clipcut.domain.history

import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.decodeReport
import com.clipcut.domain.taglines.encodeReport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A trending position the user typed when sharing: for which count and tagline, and when. */
data class TrendingMark(val reportAtMs: Long, val tagline: String, val position: Int, val atMs: Long)

/** How much is kept since a moment, for the "collected this week" line. */
data class HistorySummary(val taglineRuns: Int, val trendingMarks: Int)

/** Runs are kept this long; X's counts endpoint itself only reaches back 7 days, so these are the only copy. */
const val HISTORY_KEEP_DAYS = 90L

/**
 * Every Tagline Counter count, each in its own file under [dir], never replaced: the raw material for the website's
 * tagline timeline. Contestant names are
 * not written in here; they are looked up when the data is used, so fixing a link later fixes every past run too.
 * Files are written to a temporary name and renamed, so a crash never leaves half a run.
 */
class RunHistory(private val dir: File, private val nowMs: () -> Long = System::currentTimeMillis) {
    init {
        dir.mkdirs()
    }

    fun saveTagline(report: CountReport) = write("tagline-${report.fetchedAtMs}.json", encodeReport(report))

    fun recordTrending(reportAtMs: Long, tagline: String, position: Int) {
        val marks = trending(0) + TrendingMark(reportAtMs, tagline, position, nowMs())
        write("trending.json", JSONArray(marks.map { JSONObject().put("report", it.reportAtMs).put("tagline", it.tagline).put("position", it.position).put("at", it.atMs) }).toString())
    }

    /** Counts fetched at or after [sinceMs], oldest first. */
    fun taglineRuns(sinceMs: Long = 0): List<CountReport> =
        files("tagline-", sinceMs).mapNotNull { f -> runCatching { decodeReport(f.readText()) }.getOrNull() }

    fun trending(sinceMs: Long = 0): List<TrendingMark> {
        val f = File(dir, "trending.json")
        if (!f.exists()) return emptyList()
        return runCatching {
            JSONArray(f.readText()).let { a ->
                List(a.length()) { i -> a.getJSONObject(i).let { TrendingMark(it.getLong("report"), it.getString("tagline"), it.getInt("position"), it.getLong("at")) } }
            }
        }.getOrDefault(emptyList()).filter { it.atMs >= sinceMs }
    }

    fun summary(sinceMs: Long): HistorySummary = HistorySummary(files("tagline-", sinceMs).size, trending(sinceMs).size)

    val isEmpty: Boolean get() = files("tagline-", 0).isEmpty()

    /** Drops runs older than [HISTORY_KEEP_DAYS]. */
    fun prune() {
        val cutoff = nowMs() - HISTORY_KEEP_DAYS * 24 * 3_600_000L
        (dir.listFiles() ?: return).filter { f -> stamp(f)?.let { it < cutoff } == true }.forEach { it.delete() }
        val kept = trending(cutoff)
        if (kept.size != trending(0).size) {
            write("trending.json", JSONArray(kept.map { JSONObject().put("report", it.reportAtMs).put("tagline", it.tagline).put("position", it.position).put("at", it.atMs) }).toString())
        }
    }

    private fun stamp(f: File): Long? = Regex("""^(?:tagline|boss)-(\d+)\.json$""").matchEntire(f.name)?.groupValues?.get(1)?.toLongOrNull()

    private fun files(prefix: String, sinceMs: Long): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.name.startsWith(prefix) && (stamp(it) ?: -1) >= sinceMs }.sortedBy { stamp(it) }

    private fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        val target = File(dir, name)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not save $name" }
        }
    }
}
