package dev.notypie.templates

import dev.notypie.impl.command.RestRequester
import dev.notypie.impl.command.dto.SlackUserProfileDto
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

data class PublisherView(
    val displayName: String,
    val thumbnailUrl: String?,
)

// users.profile.get is Tier 4 (~100/min) and purely decorative here, so a lookup must never fail the message:
// results are cached per user and any failure degrades to the <@id> mention Slack renders as the name.
class SlackUserProfileResolver(
    private val restRequester: RestRequester,
    private val slackApiToken: String,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofMinutes(30L),
    private val maxEntries: Int = 5_000,
) {
    private class CachedView(
        val view: PublisherView,
        val cachedAt: Long,
    )

    private val cache = ConcurrentHashMap<String, CachedView>()

    fun resolve(userId: String): PublisherView {
        val now = clock.millis()
        cache[userId]?.takeIf { now - it.cachedAt < ttl.toMillis() }?.let { return it.view }

        val view = fetch(userId = userId) ?: return PublisherView(displayName = "<@$userId>", thumbnailUrl = null)
        if (cache.size >= maxEntries) cache.clear()
        cache[userId] = CachedView(view = view, cachedAt = now)
        return view
    }

    private fun fetch(userId: String): PublisherView? =
        restRequester
            .safeGet(
                uri = "users.profile.get?user=$userId",
                authorizationHeader = slackApiToken,
                responseType = SlackUserProfileDto::class.java,
            ).map { response -> response.body }
            .onFailure { logger.warn(it) { "users.profile.get failed for $userId; rendering the bare mention" } }
            .getOrNull()
            ?.takeIf { it.ok }
            ?.let { dto ->
                PublisherView(
                    displayName = dto.profile.displayName.ifBlank { dto.profile.realName.ifBlank { "<@$userId>" } },
                    thumbnailUrl = dto.profile.imageSize24.takeIf { it.isNotBlank() },
                )
            }
}
