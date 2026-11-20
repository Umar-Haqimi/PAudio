package my.umarh.paudio.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import my.umarh.paudio.core.model.EffectBackend
import my.umarh.paudio.core.model.EffectCreationException
import my.umarh.paudio.core.model.EffectFailureKind
import my.umarh.paudio.core.model.EffectHandle
import my.umarh.paudio.core.model.EqConfig
import java.util.concurrent.CopyOnWriteArrayList

/** Records the state of every effect instance so tests can assert e.g. "no enabled effect while tones play". */
class FakeEffectBackend : EffectBackend {
    val handles: MutableList<FakeEffectHandle> = CopyOnWriteArrayList()

    /**
     * Handles that would currently process audio: enabled, not released AND still holding control.
     * (A handle that lost control to another EQ app, SPEC trap 15, does nothing.) Use [enabledRequested]
     * to ignore the control state, e.g. for the P0 "no enabled effect while tones play" assertion.
     */
    val activeEnabled: List<FakeEffectHandle>
        get() = handles.filter { it.enabled && !it.released && it.control.value }

    /** Handles whose `setEnabled(true)` is in effect and that are not released, regardless of control. */
    val enabledRequested: List<FakeEffectHandle>
        get() = handles.filter { it.enabled && !it.released }

    val anyEnabled: Boolean get() = activeEnabled.isNotEmpty()

    /** When set, every [create] throws an [EffectCreationException] of this kind (until cleared). */
    @Volatile var failCreates: EffectFailureKind? = null
    val createCalls: MutableList<Int> = CopyOnWriteArrayList()

    override fun create(session: Int, cfg: EqConfig): EffectHandle {
        createCalls += session
        failCreates?.let { throw EffectCreationException(it) }
        return FakeEffectHandle(session, cfg).also { handles += it }
    }
}

class FakeEffectHandle(val session: Int, initial: EqConfig) : EffectHandle {
    var config: EqConfig = initial
        private set
    val appliedConfigs: MutableList<EqConfig> = mutableListOf(initial)

    @Volatile var enabled: Boolean = false
        private set
    @Volatile var released: Boolean = false
        private set

    private val _control = MutableStateFlow(true)
    override val control: StateFlow<Boolean> = _control

    private val _clamped = MutableStateFlow(false)
    override val clamped: StateFlow<Boolean> = _clamped

    /** Simulates a readback mismatch (HAL clamped the layout). */
    fun setClamped(value: Boolean) {
        _clamped.value = value
    }

    override fun apply(cfg: EqConfig) {
        check(!released) { "released" }
        config = cfg
        appliedConfigs += cfg
    }

    override fun setEnabled(on: Boolean) {
        check(!released) { "released" }
        enabled = on
    }

    override fun release() {
        released = true
        enabled = false
    }

    /** Simulates another EQ app taking over (SPEC trap 15). */
    fun setControl(has: Boolean) {
        _control.value = has
    }
}
