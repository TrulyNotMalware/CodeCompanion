package dev.notypie.application.service.command

import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.repository.authorization.UserCommandRoleRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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

    private val cachedUserExpiries = ConcurrentHashMap<String, Long>()
    private val evictionGeneration = AtomicLong(0L)

    val bootstrapAdmins: Set<String> = appConfig.authorization.bootstrapAdmins.toSet()

    fun resolve(userId: String): UserRole {
        if (isBootstrapAdmin(userId = userId)) return UserRole.ADMIN
        val now = clock.millis()
        cachedUserExpiries[userId]?.takeIf { expiresAt -> expiresAt > now }?.let { return UserRole.USER }
        val generationAtLookup = evictionGeneration.get()
        // A failed lookup propagates: the Slack handlers resolve inside their transaction, which it left rollback-only.
        val role = userCommandRoleRepository.findRole(userId = userId) ?: UserRole.USER
        if (role == UserRole.USER) rememberUser(userId = userId, now = now, generationAtLookup = generationAtLookup)
        return role
    }

    fun isBootstrapAdmin(userId: String): Boolean = userId in bootstrapAdmins

    fun evict(userId: String) {
        evictionGeneration.incrementAndGet()
        cachedUserExpiries.remove(userId)
    }

    private fun rememberUser(userId: String, now: Long, generationAtLookup: Long) {
        if (cachedUserExpiries.size >= MAX_CACHED_USERS) {
            cachedUserExpiries.entries.removeIf { (_, expiresAt) -> expiresAt <= now }
        }
        if (cachedUserExpiries.size >= MAX_CACHED_USERS) return
        cachedUserExpiries.compute(userId) { _, existing ->
            if (evictionGeneration.get() == generationAtLookup) now + CACHE_TTL.toMillis() else existing
        }
    }
}
