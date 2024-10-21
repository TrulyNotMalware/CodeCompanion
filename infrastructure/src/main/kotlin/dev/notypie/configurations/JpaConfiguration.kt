package dev.notypie.configurations

import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy

const val PRIMARY_DATASOURCE_CONFIG = "primaryPersistenceUnit"
const val JPA_ENTITY_PACKAGES = "dev.notypie.repository"

@Configuration
@EnableJpaRepositories(basePackages = [JPA_ENTITY_PACKAGES])
class JpaConfiguration {

    @Bean
    fun hikariDataSource(dataSourceProperties: DataSourceProperties): HikariDataSource =
        dataSourceProperties.initializeDataSourceBuilder()
            .type(HikariDataSource::class.java)
            .build()

    @Bean
    @Primary
    fun lazyConnectionDataSourceProxy(
        hikariDataSource: HikariDataSource
    ) = LazyConnectionDataSourceProxy(hikariDataSource)

    // Multi datasource
//    @Bean
//    @Primary
//    fun entityManagerFactory(
//        builder: EntityManagerFactoryBuilder,
//        @Qualifier(value = "lazyConnectionDataSourceProxy") dataSource: DataSource
//    ): LocalContainerEntityManagerFactoryBean =
//        builder.dataSource(dataSource)
//            .packages(JPA_ENTITY_PACKAGES)
//            .persistenceUnit(PRIMARY_DATASOURCE_CONFIG)
//            .build()
}