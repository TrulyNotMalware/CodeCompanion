package dev.notypie.application.service.command

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.service.interaction.SlackInteractionHandlerImpl
import dev.notypie.application.service.meeting.createH2DataSource
import dev.notypie.application.service.mention.SlackMentionEventHandlerImpl
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.command.createMentionInboundCommand
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.inbound.SubmissionParseObserver
import dev.notypie.impl.command.InteractionPayloadParser
import dev.notypie.impl.command.slack.createInteractionPayloadInput
import dev.notypie.impl.command.slack.selectedApplyButtonStates
import dev.notypie.repository.authorization.JpaUserCommandRoleRepository
import dev.notypie.repository.authorization.UserCommandRoleRepositoryImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.LinkedMultiValueMap
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class RoleLookupJpaTransactionTest :
    BehaviorSpec({
        val actorId = TEST_USER_ID
        val missingTable = roleStore(createSchema = false)
        val healthy = roleStore(createSchema = true)
        afterSpec {
            missingTable.close()
            healthy.close()
        }

        class RecordingExecutor {
            val completions = CopyOnWriteArrayList<Int>()
            val executor = mockk<CommandExecutor>()

            init {
                every { executor.execute(command = any<Command<*>>()) } answers {
                    TransactionSynchronizationManager.registerSynchronization(
                        object : TransactionSynchronization {
                            override fun afterCompletion(status: Int) {
                                completions.add(status)
                            }
                        },
                    )
                    CommandOutput.empty()
                }
            }
        }

        fun interactionHandler(store: RoleStore, executor: CommandExecutor): SlackInteractionHandlerImpl {
            val parser = mockk<InteractionPayloadParser>()
            every { parser.parseStringPayload(payload = any()) } answers {
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.MEETING_CREATE_REQUEST,
                    currentAction = selectedApplyButtonStates(),
                    states = listOf(selectedApplyButtonStates()),
                    idempotencyKey = UUID.randomUUID(),
                )
            }
            return SlackInteractionHandlerImpl(
                interactionPayloadParser = parser,
                applicationEventPublisher = mockk(relaxed = true),
                commandExecutor = executor,
                submissionParseObserver = SubmissionParseObserver.NONE,
                commandRoleResolver = store.resolver,
                transactionManager = store.transactionManager,
            )
        }

        fun mentionHandler(store: RoleStore, executor: CommandExecutor) =
            SlackMentionEventHandlerImpl(
                commandExecutor = executor,
                commandRoleResolver = store.resolver,
                transactionManager = store.transactionManager,
            )

        given("a role lookup that fails in SQL because the role table is missing") {
            `when`("it runs inside a JPA transaction") {
                val failure =
                    shouldThrow<UnexpectedRollbackException> {
                        TransactionTemplate(missingTable.transactionManager).execute {
                            missingTable.resolver.resolve(userId = actorId)
                        }
                    }

                then("the USER fallback cannot save that transaction: its commit fails as rolled back") {
                    failure.message.orEmpty().lowercase() shouldContain "rolled back"
                }
            }

            `when`("an interaction is handled") {
                val recording = RecordingExecutor()
                val roles =
                    missingTable.rolesResolvedDuring {
                        interactionHandler(store = missingTable, executor = recording.executor)
                            .handleInteraction(headers = LinkedMultiValueMap(), payload = "dummy-payload")
                    }

                then("the actor degrades to USER and the interaction transaction still commits") {
                    roles shouldBe listOf(UserRole.USER)
                    recording.completions shouldBe listOf(TransactionSynchronization.STATUS_COMMITTED)
                }
            }

            `when`("a mention is handled") {
                val recording = RecordingExecutor()
                val roles =
                    missingTable.rolesResolvedDuring {
                        mentionHandler(store = missingTable, executor = recording.executor)
                            .handleEvent(commandData = createMentionInboundCommand(actorId = actorId))
                    }

                then("the actor degrades to USER and the mention transaction still commits") {
                    roles shouldBe listOf(UserRole.USER)
                    recording.completions shouldBe listOf(TransactionSynchronization.STATUS_COMMITTED)
                }
            }
        }

        given("an actor with an ADMIN row") {
            healthy.inTransaction { healthy.repository.saveRole(userId = actorId, role = UserRole.ADMIN) }

            `when`("an interaction and a mention are handled") {
                val recording = RecordingExecutor()
                val roles =
                    healthy.rolesResolvedDuring {
                        interactionHandler(store = healthy, executor = recording.executor)
                            .handleInteraction(headers = LinkedMultiValueMap(), payload = "dummy-payload")
                        mentionHandler(store = healthy, executor = recording.executor)
                            .handleEvent(commandData = createMentionInboundCommand(actorId = actorId))
                    }

                then("both resolve the stored role and commit") {
                    roles shouldBe listOf(UserRole.ADMIN, UserRole.ADMIN)
                    recording.completions shouldBe List(size = 2) { TransactionSynchronization.STATUS_COMMITTED }
                }
            }
        }
    })

private class RoleStore(
    private val factoryBean: LocalContainerEntityManagerFactoryBean,
) : AutoCloseable {
    private val entityManagerFactory = checkNotNull(factoryBean.`object`)
    val transactionManager = JpaTransactionManager(entityManagerFactory)
    val repository =
        UserCommandRoleRepositoryImpl(
            jpaUserCommandRoleRepository =
                JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory))
                    .apply {
                        addRepositoryProxyPostProcessor { proxyFactory, _ ->
                            proxyFactory.addAdvice(PersistenceExceptionTranslationInterceptor(factoryBean))
                        }
                    }.getRepository(JpaUserCommandRoleRepository::class.java),
        )
    private val resolvedRoles = CopyOnWriteArrayList<UserRole>()

    val resolver =
        spyk(
            objToCopy =
                CommandRoleResolver(
                    appConfig = AppConfig(),
                    userCommandRoleRepository = repository,
                    clock = Clock.systemUTC(),
                    meterRegistry = SimpleMeterRegistry(),
                ),
        ).also { spy ->
            every { spy.resolve(userId = any()) } answers
                { callOriginal().also { resolvedRoles.add(it) } }
        }

    fun rolesResolvedDuring(action: () -> Unit): List<UserRole> {
        val before = resolvedRoles.size
        action()
        return resolvedRoles.drop(before)
    }

    fun inTransaction(action: () -> Unit) = TransactionTemplate(transactionManager).executeWithoutResult { action() }

    override fun close() = entityManagerFactory.close()
}

private fun roleStore(createSchema: Boolean): RoleStore =
    RoleStore(
        factoryBean =
            LocalContainerEntityManagerFactoryBean().apply {
                dataSource = createH2DataSource()
                setPackagesToScan("dev.notypie.repository.authorization.schema")
                jpaVendorAdapter = HibernateJpaVendorAdapter()
                setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to if (createSchema) "create-drop" else "none"))
                afterPropertiesSet()
            },
    )
