package my.umarh.paudio.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import my.umarh.paudio.core.model.BypassController
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.model.BypassToken
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Reference-counted fake; records every reason ever acquired so tests can assert bypass coverage. */
class FakeBypassController : BypassController {
    private val open = AtomicInteger(0)
    private val _bypassed = MutableStateFlow(false)
    override val bypassed: StateFlow<Boolean> = _bypassed
    val acquiredReasons: MutableList<BypassReason> = java.util.concurrent.CopyOnWriteArrayList()
    val openCount: Int get() = open.get()

    override fun acquire(reason: BypassReason): BypassToken {
        acquiredReasons += reason
        open.incrementAndGet()
        _bypassed.value = true
        return object : BypassToken {
            private val closed = AtomicBoolean(false)
            override val reason = reason
            override fun close() {
                if (closed.compareAndSet(false, true) && open.decrementAndGet() == 0) _bypassed.value = false
            }
        }
    }
}
