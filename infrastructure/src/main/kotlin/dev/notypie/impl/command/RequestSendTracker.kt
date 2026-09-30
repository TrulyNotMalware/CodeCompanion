package dev.notypie.impl.command

import okhttp3.Call
import okhttp3.EventListener

/**
 * Tells a dispatch whether a failed HTTP call may already have been acted on by Slack, i.e. whether the whole
 * request body was written. OkHttp builds a call's [EventListener] inside `newCall()` on the calling thread, and
 * both Slack clients run their calls synchronously on the dispatching thread, so the probe is handed over through a
 * [ThreadLocal] that is read only at that moment; the listener then writes the probe from whichever thread reports.
 */
object RequestSendTracker : EventListener.Factory {
    private val current = ThreadLocal<RequestSendProbe>()

    override fun create(call: Call): EventListener {
        val probe = current.get() ?: return EventListener.NONE
        probe.observed = true
        return object : EventListener() {
            override fun requestBodyEnd(call: Call, byteCount: Long) {
                probe.bodySent = true
            }
        }
    }

    fun <T> track(probe: RequestSendProbe, block: () -> T): T {
        current.set(probe)
        try {
            return block()
        } finally {
            current.remove()
        }
    }
}

class RequestSendProbe {
    @Volatile
    internal var observed = false

    @Volatile
    internal var bodySent = false

    // A call on a client without the tracker counts as sent, so a misconfigured client fails towards no resend.
    val mayHaveBeenSent: Boolean get() = !observed || bodySent
}
