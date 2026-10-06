package dev.notypie.configurations

import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "spring.datasource.hikari.maximum-pool-size=7",
        "spring.datasource.hikari.connection-timeout=4321",
        "spring.datasource.hikari.pool-name=binding_test_pool",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED",
    ],
)
@ApplyExtension(extensions = [SpringExtension::class])
class HikariDataSourceBindingTest
    @Autowired
    constructor(
        private val hikariDataSource: HikariDataSource,
    ) : BehaviorSpec({
            given("spring.datasource.hikari properties") {
                `when`("JpaConfiguration builds the pool") {
                    then("every hikari property reaches the pool") {
                        hikariDataSource.maximumPoolSize shouldBe 7
                        hikariDataSource.connectionTimeout shouldBe 4321L
                        hikariDataSource.poolName shouldBe "binding_test_pool"
                        hikariDataSource.transactionIsolation shouldBe "TRANSACTION_READ_COMMITTED"
                    }
                }
            }
        })
