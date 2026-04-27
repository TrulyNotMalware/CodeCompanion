package dev.notypie.domain.command.intent

interface IntentQueue {
    fun offer(intent: CommandIntent)

    fun snapshot(): List<CommandIntent>

    /** Returns a defensive copy and clears the queue atomically. Idempotent for retries. */
    fun drainSnapshot(): List<CommandIntent>

    fun isEmpty(): Boolean

    val size: Int
}

// Thread Unsafe — same contract as existing EventQueue
internal class DefaultIntentQueue : IntentQueue {
    private val queue: ArrayDeque<CommandIntent> = ArrayDeque()

    override fun offer(intent: CommandIntent) {
        queue.addLast(intent)
    }

    override fun snapshot(): List<CommandIntent> = queue.toList()

    override fun drainSnapshot(): List<CommandIntent> {
        val drained = queue.toList()
        queue.clear()
        return drained
    }

    override fun isEmpty(): Boolean = queue.isEmpty()

    override val size: Int
        get() = queue.size
}
