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
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        val CACHE_TTL: Duration = Duration.ofSeconds(60)
        private const val MAX_CACHED_USERS = 10_000
    }

    // Only USER (no row) is cached, as userId -> expiry. An elevated role is read from the DB on every call, so a
    // revoke committed on another replica applies to the next call here: a cached ADMIN let a revoked admin
    // re-grant themselves within the TTL. A stale USER can only deny, never grant (a grant may take up to
    // CACHE_TTL to reach a replica that has not evicted the user).
    private val cachedUsers = ConcurrentHashMap<String, Long>()
    private val evictionGeneration = AtomicLong(0L)

    val bootstrapAdmins: Set<String> = appConfig.authorization.bootstrapAdmins.toSet()

    fun resolve(userId: String): UserRole {
        if (isBootstrapAdmin(userId = userId)) return UserRole.ADMIN
        val now = clock.millis()
        cachedUsers[userId]?.takeIf { expiresAt -> expiresAt > now }?.let { return UserRole.USER }
        val generationAtLookup = evictionGeneration.get()
        val role =
            try {
                userCommandRoleRepository.findRole(userId = userId) ?: UserRole.USER
            } catch (failure: Exception) {
                log.warn(failure) { "Role lookup failed; falling back to USER for userId=$userId" }
                return UserRole.USER
            }
        if (role == UserRole.USER) rememberUser(userId = userId, now = now, generationAtLookup = generationAtLookup)
        return role
    }

    fun isBootstrapAdmin(userId: String): Boolean = userId in bootstrapAdmins

    fun evict(userId: String) {
        evictionGeneration.incrementAndGet()
        cachedUsers.remove(userId)
    }

    private fun rememberUser(userId: String, now: Long, generationAtLookup: Long) {
        if (cachedUsers.size >= MAX_CACHED_USERS) cachedUsers.entries.removeIf { (_, expiresAt) -> expiresAt <= now }
        if (cachedUsers.size >= MAX_CACHED_USERS) return
        cachedUsers.compute(userId) { _, existing ->
            if (evictionGeneration.get() == generationAtLookup) now + CACHE_TTL.toMillis() else existing
        }
    }
}
