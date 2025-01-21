package dev.notypie.impl.monitor

import dev.notypie.domain.monitor.Monitoring
import io.kubernetes.client.PodLogs
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1PodList


class K8sMonitor(
    private val apiClient: ApiClient,
    private val coreV1Api: CoreV1Api
) : Monitoring {

    //FIXME this is example.
    override fun getLog(){
        val list: V1PodList = this.coreV1Api.listPodForAllNamespaces().execute()
        for (item in list.items) {
            println(item.metadata.name)
        }
    }

    fun watchLogs(){
    }


    private fun podLogs(namespace: String, podName: String){
        val podLogs: PodLogs = PodLogs()
        val pod = this.coreV1Api.readNamespacedPod(
            podName, namespace
        )
    }

    private suspend fun getPods(namespace: String, podName: String){
         
    }
}
