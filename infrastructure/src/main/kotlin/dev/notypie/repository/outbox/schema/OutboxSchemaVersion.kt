package dev.notypie.repository.outbox.schema

// Extend SUPPORTED with every new version; drop an old version only once it's guaranteed drained.
object OutboxSchemaVersion {
    const val V2: Int = 2

    const val V3: Int = 3

    const val V4: Int = 4

    const val V5: Int = 5

    const val V6: Int = 6

    val SUPPORTED: Set<Int> = setOf(V2, V3, V4, V5, V6)
}
