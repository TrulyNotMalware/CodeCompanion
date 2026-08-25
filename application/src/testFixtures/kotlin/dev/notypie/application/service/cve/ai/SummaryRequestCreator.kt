package dev.notypie.application.service.cve.ai

import dev.notypie.repository.cve.schema.CveTopicCategory

fun createSummaryRequest(
    eventId: Long = 1L,
    topicDisplayName: String = "Java CVE",
    category: CveTopicCategory = CveTopicCategory.CVE,
    eventTitle: String = "Sample advisory",
    rawContent: String = "Raw advisory content.",
): SummaryRequest =
    SummaryRequest(
        eventId = eventId,
        topicDisplayName = topicDisplayName,
        category = category,
        eventTitle = eventTitle,
        rawContent = rawContent,
    )
