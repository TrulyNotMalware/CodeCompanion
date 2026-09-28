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

class SlackUserProfileResolver(
    private val restRequester: RestRequester,
    private val slackApiToken: String,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofMinutes(30L),
    private val failureTtl: Duration = Duration.ofSeconds(60L),
    private val maxEntries: Int = 5_000,
) {
    private class CachedView(
        val view: PublisherView,
        val cachedAt: Long,
        val expiresAt: Long,
    )

    private val cache = ConcurrentHashMap<String, CachedView>()

    fun resolve(userId: String): PublisherView {
        val now = clock.millis()
        cache[userId]?.takeIf { now < it.expiresAt }?.let { return it.view }

        val fetched = fetch(userId = userId)
        val view = fetched ?: PublisherView(displayName = "<@$userId>", thumbnailUrl = null)
        val lifetime = if (fetched == null) failureTtl else ttl
        store(userId = userId, entry = CachedView(view = view, cachedAt = now, expiresAt = now + lifetime.toMillis()))
        return view
    }

    private fun store(userId: String, entry: CachedView) {
        if (cache.size >= maxEntries && !cache.containsKey(userId)) {
            cache.entries.removeIf { entry.cachedAt >= it.value.expiresAt }
            val overflow = cache.size - maxEntries + 1
            if (overflow > 0) {
                cache.entries
                    .sortedBy { it.value.cachedAt }
                    .take(overflow.coerceAtLeast(maxEntries / 10))
                    .forEach { cache.remove(it.key, it.value) }
            }
        }
        cache[userId] = entry
    }

    private fun fetch(userId: String): PublisherView? =
        restRequester
            .safeGet(
                uri = "users.profile.get?user={user}",
                authorizationHeader = slackApiToken,
                responseType = SlackUserProfileDto::class.java,
                uriVariables = mapOf("user" to userId),
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
