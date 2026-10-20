package com.clipcut.domain.bossmeter

/** One hashtag across both sources. A source that has no data is null here, never a zero. */
data class OverallRow(val label: String, val x: Long?, val ig: Long?, val total: Long, val share: Double)

/**
 * Boss Meter, X and Instagram added together. Counts are summed per hashtag (case-insensitive) and everything else is
 * recomputed from the sums, never averaged.
 */
data class Overall(
    val rows: List<OverallRow>,
    val total: Long,
    val tracked: Long,
    val uncounted: Long,
    val uncountedPct: Double,
    val hasX: Boolean,
    val hasIg: Boolean,
) {
    val leadText: String? get() = if (rows.size >= 2) "${rows[0].label} leads ${rows[1].label} by ${"%,d".format(rows[0].total - rows[1].total)}" else null
}

private fun key(tag: String) = tag.trim().trimStart('#').lowercase()

/** Null when neither source has data. */
fun overall(x: BossMeterResult?, ig: IgBossResult?): Overall? {
    val igData = ig?.takeUnless { it.empty }
    if (x == null && igData == null) return null
    val order = LinkedHashMap<String, String>() // key -> label
    x?.tags?.forEach { order.putIfAbsent(key(it.tag), it.tag) }
    igData?.items?.forEach { order.putIfAbsent(key(it.label), it.label) }
    val xBy = x?.tags?.associate { key(it.tag) to it.count }.orEmpty()
    val igBy = igData?.items?.associate { key(it.label) to it.count }.orEmpty()
    val total = (x?.totalThread ?: 0L) + (igData?.total ?: 0L)
    val tracked = order.keys.sumOf { (xBy[it] ?: 0L) + (igBy[it] ?: 0L) }
    val rows = order.map { (k, label) ->
        val sum = (xBy[k] ?: 0L) + (igBy[k] ?: 0L)
        OverallRow(label, if (x != null) xBy[k] ?: 0L else null, if (igData != null) igBy[k] ?: 0L else null, sum, if (tracked == 0L) 0.0 else sum * 100.0 / tracked)
    }.sortedByDescending { it.total }
    val uncounted = total - tracked
    return Overall(rows, total, tracked, uncounted, if (total == 0L) 0.0 else uncounted * 100.0 / total, x != null, igData != null)
}
