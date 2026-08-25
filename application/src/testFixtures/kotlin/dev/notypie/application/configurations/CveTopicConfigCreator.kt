package dev.notypie.application.configurations

import dev.notypie.repository.cve.schema.CveDeliveryMode
import dev.notypie.repository.cve.schema.CveSourceType
import dev.notypie.repository.cve.schema.CveTopicCategory

fun createCveTopicConfigDefinition(
    key: String = "cve-java",
    displayName: String = "Java CVE",
    category: CveTopicCategory = CveTopicCategory.CVE,
    sourceType: CveSourceType = CveSourceType.NVD_CVE,
    sourceConfig: String? = """{"cpe":"oracle:jdk"}""",
    deliveryMode: CveDeliveryMode = CveDeliveryMode.IMMEDIATE,
    active: Boolean = true,
): AppConfig.Cve.TopicDefinition =
    AppConfig.Cve.TopicDefinition(
        key = key,
        displayName = displayName,
        category = category,
        sourceType = sourceType,
        sourceConfig = sourceConfig,
        deliveryMode = deliveryMode,
        active = active,
    )
