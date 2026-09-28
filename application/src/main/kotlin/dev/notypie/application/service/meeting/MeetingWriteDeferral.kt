package dev.notypie.application.service.meeting

internal object MeetingWriteDeferral {
    private val pending = ThreadLocal<MutableList<() -> Unit>>()

    fun <T> collecting(block: () -> T): Pair<T, List<() -> Unit>> {
        if (pending.get() != null) return block() to emptyList()
        val writes = mutableListOf<() -> Unit>()
        pending.set(writes)
        try {
            return block() to writes.toList()
        } finally {
            pending.remove()
        }
    }

    fun runOrDefer(write: () -> Unit) {
        val writes = pending.get()
        if (writes == null) write() else writes.add(write)
    }
}
