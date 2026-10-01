package dev.notypie.application.service.command

import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.repository.authorization.UserCommandRoleRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class CommandRoleResolverTest :
    BehaviorSpec({
        val bootstrapAdminId = "U_OWNER"
        val grantedDeveloperId = "U_DEV"
        val unknownUserId = "U_NOBODY"
        val start = Instant.ofEpochSecond(1714280000)

        fun resolverWith(roleRepository: UserCommandRoleRepository, clock: Clock = Clock.fixed(start, ZoneOffset.UTC)) =
            CommandRoleResolver(
                appConfig =
                    AppConfig(
                        authorization = AppConfig.Authorization(bootstrapAdmins = listOf(bootstrapAdminId)),
                    ),
                userCommandRoleRepository = roleRepository,
                clock = clock,
            )

        given("resolve") {
            val roleRepository = mockk<UserCommandRoleRepository>()
            every { roleRepository.findRole(userId = grantedDeveloperId) } returns UserRole.DEVELOPER
            every { roleRepository.findRole(userId = unknownUserId) } returns null
            val resolver = resolverWith(roleRepository = roleRepository)

            `when`("the actor is a configured bootstrap admin") {
                then("ADMIN is returned without a DB row") {
                    resolver.resolve(userId = bootstrapAdminId) shouldBe UserRole.ADMIN
                }
            }

            `when`("the actor has a DB grant") {
                then("the granted role is returned") {
                    resolver.resolve(userId = grantedDeveloperId) shouldBe UserRole.DEVELOPER
                }
            }

            `when`("the actor has no grant") {
                then("the USER default is returned") {
                    resolver.resolve(userId = unknownUserId) shouldBe UserRole.USER
                }
            }
        }

        given("the role cache") {
            `when`("the same user is resolved twice within the TTL") {
                val roleRepository = mockk<UserCommandRoleRepository>()
                every { roleRepository.findRole(userId = grantedDeveloperId) } returns UserRole.DEVELOPER
                val resolver = resolverWith(roleRepository = roleRepository)

                resolver.resolve(userId = grantedDeveloperId)
                val second = resolver.resolve(userId = grantedDeveloperId)

                then("the second call is served from the cache") {
                    second shouldBe UserRole.DEVELOPER
                    verify(exactly = 1) { roleRepository.findRole(userId = grantedDeveloperId) }
                }
            }

            `when`("the TTL has elapsed") {
                val roleRepository = mockk<UserCommandRoleRepository>()
                every { roleRepository.findRole(userId = grantedDeveloperId) } returnsMany
                    listOf(UserRole.DEVELOPER, UserRole.USER)
                val clock = MutableClock(instant = start)
                val resolver = resolverWith(roleRepository = roleRepository, clock = clock)

                resolver.resolve(userId = grantedDeveloperId)
                clock.instant = start.plus(CommandRoleResolver.CACHE_TTL).plusMillis(1)
                val afterExpiry = resolver.resolve(userId = grantedDeveloperId)

                then("the role is looked up again") {
                    afterExpiry shouldBe UserRole.USER
                    verify(exactly = 2) { roleRepository.findRole(userId = grantedDeveloperId) }
                }
            }

            `when`("the user is evicted after a committed role change") {
                val roleRepository = mockk<UserCommandRoleRepository>()
                every { roleRepository.findRole(userId = grantedDeveloperId) } returnsMany
                    listOf(UserRole.ADMIN, UserRole.USER)
                val resolver = resolverWith(roleRepository = roleRepository)

                resolver.resolve(userId = grantedDeveloperId)
                resolver.evict(userId = grantedDeveloperId)
                val afterRevoke = resolver.resolve(userId = grantedDeveloperId)
                val cachedAfterRevoke = resolver.resolve(userId = grantedDeveloperId)

                then("the next lookup reads the new role and caches it again") {
                    afterRevoke shouldBe UserRole.USER
                    cachedAfterRevoke shouldBe UserRole.USER
                    verify(exactly = 2) { roleRepository.findRole(userId = grantedDeveloperId) }
                }
            }

            `when`("an eviction lands while a lookup is reading the old role") {
                val roleRepository = mockk<UserCommandRoleRepository>()
                lateinit var resolver: CommandRoleResolver
                every { roleRepository.findRole(userId = grantedDeveloperId) } answers {
                    resolver.evict(userId = grantedDeveloperId)
                    UserRole.ADMIN
                } andThenAnswer { UserRole.USER }
                resolver = resolverWith(roleRepository = roleRepository)

                val inFlight = resolver.resolve(userId = grantedDeveloperId)
                val next = resolver.resolve(userId = grantedDeveloperId)

                then("the stale role is answered once but never cached") {
                    inFlight shouldBe UserRole.ADMIN
                    next shouldBe UserRole.USER
                    verify(exactly = 2) { roleRepository.findRole(userId = grantedDeveloperId) }
                }
            }
        }

        given("a failing role lookup") {
            val roleRepository = mockk<UserCommandRoleRepository>()
            every { roleRepository.findRole(userId = grantedDeveloperId) } throws
                IllegalStateException("db down") andThen UserRole.DEVELOPER
            val resolver = resolverWith(roleRepository = roleRepository)

            `when`("the repository throws") {
                val failure = runCatching { resolver.resolve(userId = grantedDeveloperId) }.exceptionOrNull()
                val recovered = resolver.resolve(userId = grantedDeveloperId)

                then("the failure propagates instead of a USER fallback the caller's transaction could not commit") {
                    failure.shouldBeInstanceOf<IllegalStateException>()
                }

                then("the failure is not cached, so the next lookup sees the stored role") {
                    recovered shouldBe UserRole.DEVELOPER
                }
            }
        }
    })

private class MutableClock(
    var instant: Instant,
) : Clock() {
    override fun instant(): Instant = instant

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
