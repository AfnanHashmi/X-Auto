package com.clipcut.domain.deletion

import org.json.JSONObject
import java.time.Instant

/** [isRetweet] is true for a plain repost: it is not a post of its own and this endpoint does not un-repost it. */
data class TimelinePost(val id: String, val text: String, val createdAt: Instant, val isRetweet: Boolean = false)
data class TimelinePage(val posts: List<TimelinePost>, val nextToken: String?)

/**
 * Parses one page of `GET /2/users/:id/tweets`. Kept out of the HTTP layer so it can be tested
 * against captured JSON. A page with no `data` (X omits the key entirely when nothing matched) is an
 * empty page, not an error; a post without `created_at` is dropped rather than guessed at, since the
 * timestamp is what decides whether it is inside the window.
 */
fun parseTimelinePage(body: String): TimelinePage {
    val root = JSONObject(body)
    val data = root.optJSONArray("data")
    val posts = buildList {
        for (i in 0 until (data?.length() ?: 0)) {
            val o = data!!.getJSONObject(i)
            val createdAt = o.optString("created_at").takeIf { it.isNotBlank() } ?: continue
            add(
                TimelinePost(
                    id = o.getString("id"),
                    text = o.optString("text"),
                    createdAt = Instant.parse(createdAt),
                    isRetweet = o.optJSONArray("referenced_tweets").let { refs ->
                        refs != null && (0 until refs.length()).any { refs.getJSONObject(it).optString("type") == "retweeted" }
                    },
                ),
            )
        }
    }
    val nextToken = root.optJSONObject("meta")?.optString("next_token")?.takeIf { it.isNotBlank() }
    return TimelinePage(posts, nextToken)
}
