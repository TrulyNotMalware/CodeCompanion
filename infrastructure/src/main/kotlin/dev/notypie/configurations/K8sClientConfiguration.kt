package dev.notypie.configurations

import dev.notypie.impl.monitor.K8sMonitor
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.util.Config
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnBean( annotation = [EnableMonitoringK8s::class] )
class K8sClientConfiguration {

    @Bean
    fun apiClient(): ApiClient = Config.defaultClient()

    @Bean
    fun coreV1Api(apiClient: ApiClient) =
        CoreV1Api(apiClient)

    @Bean
    fun monitoring(
        apiClient: ApiClient,
        coreV1Api: CoreV1Api
    ) = K8sMonitor(apiClient = apiClient, coreV1Api = coreV1Api)

}