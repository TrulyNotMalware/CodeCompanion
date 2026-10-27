package dev.notypie.templates

import dev.notypie.impl.command.RestRequester
import dev.notypie.impl.command.dto.SlackUserInfoDto
import dev.notypie.impl.command.dto.SlackUserProfileDto
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

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
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<PublisherView>>()
    private val evictionLock = ReentrantLock()

    fun resolve(userId: String): PublisherView {
        cached(userId = userId)?.let { return it }
        val lookup = CompletableFuture<PublisherView>()
        inFlight.putIfAbsent(userId, lookup)?.let { shared ->
            try {
                return shared.join()
            } catch (exception: CompletionException) {
                throw exception.cause ?: exception
            }
        }
        try {
            val view = cached(userId = userId) ?: fetchAndStore(userId = userId)
            lookup.complete(view)
            return view
        } catch (exception: Throwable) {
            lookup.completeExceptionally(exception)
            throw exception
        } finally {
            inFlight.remove(userId, lookup)
        }
    }

    private fun cached(userId: String): PublisherView? = cache[userId]?.takeIf { clock.millis() < it.expiresAt }?.view

    private fun fetchAndStore(userId: String): PublisherView {
        val now = clock.millis()
        val fetched = fetch(userId = userId)
        val view = fetched ?: PublisherView(displayName = "<@$userId>", thumbnailUrl = null)
        val lifetime = if (fetched == null) failureTtl else ttl
        store(userId = userId, entry = CachedView(view = view, cachedAt = now, expiresAt = now + lifetime.toMillis()))
        return view
    }

    private fun store(userId: String, entry: CachedView) {
        if (cache.size >= maxEntries && !cache.containsKey(userId) && evictionLock.tryLock()) {
            try {
                evict(now = entry.cachedAt)
            } finally {
                evictionLock.unlock()
            }
        }
        cache[userId] = entry
    }

    private fun evict(now: Long) {
        cache.entries.removeIf { now >= it.value.expiresAt }
        val overflow = cache.size - maxEntries + 1
        if (overflow > 0) {
            cache.entries
                .sortedBy { it.value.cachedAt }
                .take(overflow.coerceAtLeast(maxEntries / 10))
                .forEach { cache.remove(it.key, it.value) }
        }
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
            ?.let { dto ->
                if (dto.ok) {
                    dto.profile
                } else {
                    logger.warn { "users.profile.get answered ok=false for $userId: error=${dto.error}" }
                    null
                }
            }?.let { profile ->
                PublisherView(
                    displayName =
                        profile.displayName.orEmpty().ifBlank { profile.realName.orEmpty().ifBlank { "<@$userId>" } },
                    thumbnailUrl = profile.imageSize24?.takeIf { it.isNotBlank() },
                )
            }
}

class SlackUserEmailResolver(
    private val restRequester: RestRequester,
    private val slackApiToken: String,
) {
    fun resolve(userId: String): String? {
        val response =
            restRequester
                .safeGet(
                    uri = "users.info?user={user}",
                    authorizationHeader = slackApiToken,
                    responseType = SlackUserInfoDto::class.java,
                    uriVariables = mapOf("user" to userId),
                ).getOrElse { exception ->
                    logger.warn { "users.info failed for $userId: ${exception.javaClass.simpleName}" }
                    return null
                }.body
        if (response == null || !response.ok) {
            logger.warn { "users.info answered ok=false for $userId: error=${response?.error}" }
            return null
        }
        return response.user
            ?.profile
            ?.email
            ?.takeIf { it.isNotBlank() }
    }
}
