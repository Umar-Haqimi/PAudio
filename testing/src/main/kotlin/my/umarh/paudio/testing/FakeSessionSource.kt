package my.umarh.paudio.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import my.umarh.paudio.core.model.SessionEvent
import my.umarh.paudio.core.model.SessionSource

/** Test-driven session source: push events with [open] / [close] / [emit]. */
class FakeSessionSource : SessionSource {
    private val flow = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
    override val events: Flow<SessionEvent> = flow

    fun emit(event: SessionEvent) {
        flow.tryEmit(event)
    }

    fun open(session: Int, pkg: String) = emit(SessionEvent.Open(session, pkg))
    fun close(session: Int, pkg: String) = emit(SessionEvent.Close(session, pkg))
}
