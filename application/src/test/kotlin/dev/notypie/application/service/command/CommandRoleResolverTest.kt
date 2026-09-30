package dev.notypie.application.service.command

import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.repository.authorization.UserCommandRoleRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
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
            `when`("a user without a grant is resolved twice within the TTL") {
                val roleRepository = mockk<UserCommandRoleRepository>()
                every { roleRepository.findRole(userId = unknownUserId) } returns null
                val resolver = resolverWith(roleRepository = roleRepository)

                resolver.resolve(userId = unknownUserId)
                val second = resolver.resolve(userId = unknownUserId)

                then("the second call is served from the cache") {
                    second shouldBe UserRole.USER
                    verify(exactly = 1) { roleRepository.findRole(userId = unknownUserId) }
                }
            }

            `when`("a user with an elevated grant is resolved twice within the TTL") {
                val roleRepository = mockk<UserCommandRoleRepository>()
                every { roleRepository.findRole(userId = grantedDeveloperId) } returns UserRole.DEVELOPER
                val resolver = resolverWith(roleRepository = roleRepository)

                resolver.resolve(userId = grantedDeveloperId)
                val second = resolver.resolve(userId = grantedDeveloperId)

                then("every call reads the DB, because only USER is cached") {
                    second shouldBe UserRole.DEVELOPER
                    verify(exactly = 2) { roleRepository.findRole(userId = grantedDeveloperId) }
                }
            }

            `when`("another replica revokes an admin, so this resolver is never evicted") {
                val roleRepository = mockk<UserCommandRoleRepository>()
                every { roleRepository.findRole(userId = grantedDeveloperId) } returnsMany listOf(UserRole.ADMIN, null)
                val resolver = resolverWith(roleRepository = roleRepository)

                val beforeRevoke = resolver.resolve(userId = grantedDeveloperId)
                val afterRevoke = resolver.resolve(userId = grantedDeveloperId)

                then("the very next call sees USER, so the revoked admin cannot re-grant themselves within the TTL") {
                    beforeRevoke shouldBe UserRole.ADMIN
                    afterRevoke shouldBe UserRole.USER
                }
            }

            `when`("another replica grants a cached user and the TTL then elapses") {
                val roleRepository = mockk<UserCommandRoleRepository>()
                every { roleRepository.findRole(userId = unknownUserId) } returnsMany listOf(null, UserRole.DEVELOPER)
                val clock = MutableClock(instant = start)
                val resolver = resolverWith(roleRepository = roleRepository, clock = clock)

                resolver.resolve(userId = unknownUserId)
                val withinTtl = resolver.resolve(userId = unknownUserId)
                clock.instant = start.plus(CommandRoleResolver.CACHE_TTL).plusMillis(1)
                val afterExpiry = resolver.resolve(userId = unknownUserId)

                then("the grant is denied until the cached USER expires, then looked up again") {
                    withinTtl shouldBe UserRole.USER
                    afterExpiry shouldBe UserRole.DEVELOPER
                    verify(exactly = 2) { roleRepository.findRole(userId = unknownUserId) }
                }
            }

            `when`("a cached user is evicted after a grant committed on this replica") {
                val roleRepository = mockk<UserCommandRoleRepository>()
                every { roleRepository.findRole(userId = unknownUserId) } returnsMany listOf(null, UserRole.ADMIN)
                val resolver = resolverWith(roleRepository = roleRepository)

                resolver.resolve(userId = unknownUserId)
                resolver.evict(userId = unknownUserId)
                val afterGrant = resolver.resolve(userId = unknownUserId)

                then("the next lookup reads the new role") {
                    afterGrant shouldBe UserRole.ADMIN
                    verify(exactly = 2) { roleRepository.findRole(userId = unknownUserId) }
                }
            }

            `when`("an eviction lands while a lookup is reading the old USER role") {
                val roleRepository = mockk<UserCommandRoleRepository>()
                lateinit var resolver: CommandRoleResolver
                every { roleRepository.findRole(userId = unknownUserId) } answers {
                    resolver.evict(userId = unknownUserId)
                    null
                } andThenAnswer { UserRole.ADMIN }
                resolver = resolverWith(roleRepository = roleRepository)

                val inFlight = resolver.resolve(userId = unknownUserId)
                val next = resolver.resolve(userId = unknownUserId)

                then("the stale USER is answered once but never cached") {
                    inFlight shouldBe UserRole.USER
                    next shouldBe UserRole.ADMIN
                    verify(exactly = 2) { roleRepository.findRole(userId = unknownUserId) }
                }
            }
        }

        given("a failing role lookup") {
            val roleRepository = mockk<UserCommandRoleRepository>()
            every { roleRepository.findRole(userId = grantedDeveloperId) } throws
                IllegalStateException("db down") andThen UserRole.DEVELOPER
            val resolver = resolverWith(roleRepository = roleRepository)

            `when`("the repository throws") {
                val degraded = resolver.resolve(userId = grantedDeveloperId)
                val recovered = resolver.resolve(userId = grantedDeveloperId)

                then("the user degrades to USER and the failure is not cached") {
                    degraded shouldBe UserRole.USER
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
