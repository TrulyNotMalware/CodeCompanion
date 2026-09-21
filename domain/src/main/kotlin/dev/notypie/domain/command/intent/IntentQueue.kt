package dev.notypie.domain.command.intent

interface IntentQueue {
    fun offer(effect: CommandEffect)

    fun snapshot(): List<CommandEffect>

    fun drainSnapshot(): List<CommandEffect>

    fun isEmpty(): Boolean

    val size: Int
}

internal class DefaultIntentQueue : IntentQueue {
    private val queue: ArrayDeque<CommandEffect> = ArrayDeque()

    override fun offer(effect: CommandEffect) = queue.addLast(effect)

    override fun snapshot(): List<CommandEffect> = queue.toList()

    override fun drainSnapshot(): List<CommandEffect> {
        val drained = queue.toList()
        queue.clear()
        return drained
    }

    override fun isEmpty(): Boolean = queue.isEmpty()

    override val size: Int
        get() = queue.size
}
