package dev.notypie.schema

import dev.notypie.repository.cve.CveTopic
import dev.notypie.repository.cve.CveTopicDefinition
import dev.notypie.repository.cve.schema.CveDeliveryMode
import dev.notypie.repository.cve.schema.CveSourceType
import dev.notypie.repository.cve.schema.CveSubscriptionSchema
import dev.notypie.repository.cve.schema.CveTopicCategory
import dev.notypie.repository.cve.schema.CveTopicSchema

fun createCveTopicSchema(
    id: Long = 0L,
    topicKey: String = "cve-java",
    displayName: String = "Java CVE",
    category: CveTopicCategory = CveTopicCategory.CVE,
    sourceType: CveSourceType = CveSourceType.NVD_CVE,
    sourceConfig: String? = """{"cpe":"oracle:jdk"}""",
    deliveryMode: CveDeliveryMode = CveDeliveryMode.IMMEDIATE,
    active: Boolean = true,
): CveTopicSchema =
    CveTopicSchema(
        id = id,
        topicKey = topicKey,
        displayName = displayName,
        category = category,
        sourceType = sourceType,
        sourceConfig = sourceConfig,
        deliveryMode = deliveryMode,
        active = active,
    )

fun createCveTopicDefinition(
    topicKey: String = "cve-java",
    displayName: String = "Java CVE",
    category: CveTopicCategory = CveTopicCategory.CVE,
    sourceType: CveSourceType = CveSourceType.NVD_CVE,
    sourceConfig: String? = """{"cpe":"oracle:jdk"}""",
    deliveryMode: CveDeliveryMode = CveDeliveryMode.IMMEDIATE,
    active: Boolean = true,
): CveTopicDefinition =
    CveTopicDefinition(
        topicKey = topicKey,
        displayName = displayName,
        category = category,
        sourceType = sourceType,
        sourceConfig = sourceConfig,
        deliveryMode = deliveryMode,
        active = active,
    )

fun createCveTopic(
    id: Long = 1L,
    topicKey: String = "cve-java",
    displayName: String = "Java CVE",
    category: CveTopicCategory = CveTopicCategory.CVE,
    sourceType: CveSourceType = CveSourceType.NVD_CVE,
    sourceConfig: String? = """{"cpe":"oracle:jdk"}""",
    deliveryMode: CveDeliveryMode = CveDeliveryMode.IMMEDIATE,
    active: Boolean = true,
): CveTopic =
    CveTopic(
        id = id,
        topicKey = topicKey,
        displayName = displayName,
        category = category,
        sourceType = sourceType,
        sourceConfig = sourceConfig,
        deliveryMode = deliveryMode,
        active = active,
    )

fun createCveSubscriptionSchema(
    id: Long = 0L,
    userId: String = "U_SUBSCRIBER",
    topicId: Long = 1L,
): CveSubscriptionSchema =
    CveSubscriptionSchema(
        id = id,
        userId = userId,
        topicId = topicId,
    )
