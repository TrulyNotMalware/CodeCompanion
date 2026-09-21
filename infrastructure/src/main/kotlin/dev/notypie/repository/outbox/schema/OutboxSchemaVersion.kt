package dev.notypie.repository.outbox.schema

// Bump CURRENT and extend SUPPORTED together; drop an old version only once it's guaranteed drained.
object OutboxSchemaVersion {
    const val V2: Int = 2

    const val CURRENT: Int = V2

    val SUPPORTED: Set<Int> = setOf(V2)
}
