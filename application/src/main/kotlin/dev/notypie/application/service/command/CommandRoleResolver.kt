package dev.notypie.application.service.command

import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.repository.authorization.UserCommandRoleRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger {}

@Service
class CommandRoleResolver(
    appConfig: AppConfig,
    private val userCommandRoleRepository: UserCommandRoleRepository,
    private val clock: Clock,
) {
    companion object {
        val CACHE_TTL: Duration = Duration.ofSeconds(60)
        private const val MAX_CACHED_USERS = 10_000
    }

    private data class CachedRole(
        val role: UserRole,
        val expiresAt: Long,
    )

    private val cache = ConcurrentHashMap<String, CachedRole>()
    private val evictionGeneration = AtomicLong(0L)

    val bootstrapAdmins: Set<String> = appConfig.authorization.bootstrapAdmins.toSet()

    fun resolve(userId: String): UserRole {
        if (isBootstrapAdmin(userId = userId)) return UserRole.ADMIN
        val now = clock.millis()
        cache[userId]?.takeIf { it.expiresAt > now }?.let { return it.role }
        val generationAtLookup = evictionGeneration.get()
        val role =
            try {
                userCommandRoleRepository.findRole(userId = userId) ?: UserRole.USER
            } catch (failure: Exception) {
                log.warn(failure) { "Role lookup failed; falling back to USER for userId=$userId" }
                return UserRole.USER
            }
        remember(userId = userId, role = role, now = now, generationAtLookup = generationAtLookup)
        return role
    }

    fun isBootstrapAdmin(userId: String): Boolean = userId in bootstrapAdmins

    fun evict(userId: String) {
        evictionGeneration.incrementAndGet()
        cache.remove(userId)
    }

    private fun remember(
        userId: String,
        role: UserRole,
        now: Long,
        generationAtLookup: Long,
    ) {
        if (cache.size >= MAX_CACHED_USERS) cache.entries.removeIf { (_, cached) -> cached.expiresAt <= now }
        if (cache.size >= MAX_CACHED_USERS) return
        cache.compute(userId) { _, existing ->
            if (evictionGeneration.get() == generationAtLookup) {
                CachedRole(role = role, expiresAt = now + CACHE_TTL.toMillis())
            } else {
                existing
            }
        }
    }
}
