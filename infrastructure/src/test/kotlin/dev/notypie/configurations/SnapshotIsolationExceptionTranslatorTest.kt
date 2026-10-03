package dev.notypie.configurations

import dev.notypie.repository.createRawSnapshotIsolationFailure
import dev.notypie.repository.meeting.isMeetingWriteConflict
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.hibernate.exception.ConstraintViolationException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.support.PersistenceExceptionTranslator
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.orm.jpa.JpaSystemException
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.vendor.HibernateJpaDialect
import org.springframework.transaction.PlatformTransactionManager
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException

@SpringBootTest
@ApplyExtension(extensions = [SpringExtension::class])
class SnapshotIsolationExceptionTranslatorTest
    @Autowired
    constructor(
        private val applicationContext: ApplicationContext,
        private val transactionManager: PlatformTransactionManager,
        private val jdbcTemplate: JdbcTemplate,
    ) : BehaviorSpec({
            val translator = SnapshotIsolationExceptionTranslator()
            val checkRead =
                SQLException(
                    "Record has changed since last read in table 'meetings'; try restarting transaction",
                    "HY000",
                    SnapshotIsolationExceptionTranslator.ER_CHECKREAD,
                )
            val duplicateKey =
                SQLIntegrityConstraintViolationException(
                    "Duplicate entry '7-U_A' for key 'uk_meeting_participants_meeting_user'",
                    "23000",
                    1062,
                )

            given("MariaDB ER_CHECKREAD 1020") {
                `when`("Hibernate hands it over for an operation, directly or as a cause") {
                    val direct =
                        translator.translate(
                            task = "Hibernate operation: update",
                            sql = "update",
                            ex = checkRead,
                        )
                    val nested =
                        translator.translate(
                            task = "Hibernate operation: update",
                            sql = "update",
                            ex = SQLException("wrapper", checkRead),
                        )

                    then("it becomes an optimistic-locking failure that the meeting retry treats as a conflict") {
                        direct.shouldBeInstanceOf<SnapshotIsolationConflictException>()
                        direct.cause shouldBeSameInstanceAs checkRead
                        nested.shouldBeInstanceOf<SnapshotIsolationConflictException>()
                        direct.shouldBeInstanceOf<ConcurrencyFailureException>().isMeetingWriteConflict() shouldBe true
                    }
                }
            }

            given("any other SQL error") {
                `when`("Hibernate hands it over for an operation") {
                    then("the translator abstains, so Hibernate's own mapping still decides") {
                        translator
                            .translate(task = "Hibernate operation: insert", sql = "insert", ex = duplicateKey)
                            .shouldBeNull()
                    }
                }

                `when`("it comes from a Hibernate transaction or from JdbcTemplate") {
                    val defaultTranslator = SQLExceptionSubclassTranslator()

                    then("it is translated as the default translator those callers had before") {
                        listOf("Hibernate transaction: commit", "PreparedStatementCallback").forEach { task ->
                            translator.translate(task = task, sql = null, ex = duplicateKey)!!::class shouldBe
                                defaultTranslator.translate(task, null, duplicateKey)!!::class
                        }
                    }
                }
            }

            given("Spring's HibernateJpaDialect") {
                val raw = createRawSnapshotIsolationFailure(table = "meetings")

                `when`("it has no JDBC exception translator, as before") {
                    then("the 1020 form Hibernate throws falls through to an uncategorized JpaSystemException") {
                        HibernateJpaDialect().translateExceptionIfPossible(raw).shouldBeInstanceOf<JpaSystemException>()
                    }
                }

                `when`("it has this translator") {
                    val dialect = HibernateJpaDialect().apply { setJdbcExceptionTranslator(translator) }
                    val constraintViolation =
                        ConstraintViolationException(
                            "could not execute statement",
                            duplicateKey,
                            "insert",
                            ConstraintViolationException.ConstraintKind.UNIQUE,
                            "uk_meeting_participants_meeting_user",
                        )

                    then("1020 is a conflict and a unique violation keeps its usual DataIntegrityViolationException") {
                        dialect
                            .translateExceptionIfPossible(
                                raw,
                            ).shouldBeInstanceOf<SnapshotIsolationConflictException>()
                        dialect.translateExceptionIfPossible(constraintViolation)!!::class shouldBe
                            DataIntegrityViolationException::class
                    }
                }
            }

            given("the application context") {
                val raw = createRawSnapshotIsolationFailure(table = "meetings")

                `when`("repository proxies, the JPA transaction manager and JdbcTemplate translate exceptions") {
                    val entityManagerFactory = applicationContext.getBean("&entityManagerFactory")

                    then("each uses this translator, so the bean is the only SQLExceptionTranslator Boot wires") {
                        entityManagerFactory
                            .shouldBeInstanceOf<PersistenceExceptionTranslator>()
                            .translateExceptionIfPossible(raw)
                            .shouldBeInstanceOf<SnapshotIsolationConflictException>()
                        transactionManager
                            .shouldBeInstanceOf<JpaTransactionManager>()
                            .jpaDialect
                            .translateExceptionIfPossible(raw)
                            .shouldBeInstanceOf<SnapshotIsolationConflictException>()
                        jdbcTemplate.exceptionTranslator.shouldBeInstanceOf<SnapshotIsolationExceptionTranslator>()
                    }
                }
            }
        })
