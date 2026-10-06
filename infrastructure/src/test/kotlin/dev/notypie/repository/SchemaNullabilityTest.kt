package dev.notypie.repository

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.jdbc.core.JdbcTemplate

@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class SchemaNullabilityTest
    @Autowired
    constructor(
        private val jdbcTemplate: JdbcTemplate,
    ) : StringSpec({
            fun isNullable(table: String, column: String): String =
                jdbcTemplate.queryForObject(
                    "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE UPPER(TABLE_NAME) = UPPER(?) AND UPPER(COLUMN_NAME) = UPPER(?)",
                    String::class.java,
                    table,
                    column,
                )!!

            "columns mapped to non-null Kotlin properties are NOT NULL in the generated schema" {
                isNullable(table = "outbox_message", column = "status") shouldBe "NO"
                isNullable(table = "meeting_participants", column = "absent_reason") shouldBe "NO"
            }
        })
