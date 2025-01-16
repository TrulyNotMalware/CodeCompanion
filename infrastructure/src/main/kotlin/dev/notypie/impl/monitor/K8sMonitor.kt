package dev.notypie.impl.monitor

import dev.notypie.domain.monitor.Monitoring
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1PodList


class K8sMonitor(
    private val apiClient: ApiClient
) : Monitoring {

    //FIXME this is example.
    override fun getLog(){
        println("Base Path: " + apiClient.basePath)
        val coreV1Api = CoreV1Api(apiClient)
        val list: V1PodList = coreV1Api.listPodForAllNamespaces().execute()
        for (item in list.items) {
            println(item.metadata.name)
        }
    }
}
