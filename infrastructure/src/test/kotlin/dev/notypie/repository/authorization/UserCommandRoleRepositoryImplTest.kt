package dev.notypie.repository.authorization

import dev.notypie.domain.command.authorization.UserRole
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager

@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class UserCommandRoleRepositoryImplTest
    @Autowired
    constructor(
        private val jpaUserCommandRoleRepository: JpaUserCommandRoleRepository,
        private val entityManager: TestEntityManager,
    ) : BehaviorSpec({
            val repository = UserCommandRoleRepositoryImpl(jpaUserCommandRoleRepository = jpaUserCommandRoleRepository)

            given("a user granted DEVELOPER and then promoted") {
                `when`("the role is saved twice") {
                    then("the grant row is updated in place to the new role") {
                        repository.saveRole(userId = "U_ROLE", role = UserRole.DEVELOPER)
                        repository.saveRole(userId = "U_ROLE", role = UserRole.ADMIN)
                        entityManager.flush()
                        entityManager.clear()

                        repository.findRole(userId = "U_ROLE") shouldBe UserRole.ADMIN
                        jpaUserCommandRoleRepository.findAll().count { it.userId == "U_ROLE" } shouldBe 1
                    }
                }
            }
        })
