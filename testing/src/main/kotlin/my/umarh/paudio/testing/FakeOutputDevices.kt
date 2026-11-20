package my.umarh.paudio.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.OutputDeviceType
import my.umarh.paudio.core.model.OutputDevices

class FakeOutputDevices(initial: OutputDevice? = null) : OutputDevices {
    private val state = MutableStateFlow(initial)
    override val current: StateFlow<OutputDevice?> = state

    fun set(device: OutputDevice?) {
        state.value = device
    }

    companion object {
        val WIRED_HEADPHONES = OutputDevice(OutputDeviceType.WIRED, "Wired headphones")
        val BLUETOOTH_BUDS = OutputDevice(OutputDeviceType.BLUETOOTH_A2DP, "Test Buds", "AB:CD")
        val SPEAKER = OutputDevice(OutputDeviceType.SPEAKER, "Speaker")
    }
}
