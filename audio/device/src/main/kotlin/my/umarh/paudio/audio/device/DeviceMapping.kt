package my.umarh.paudio.audio.device

import android.media.AudioDeviceInfo
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.OutputDeviceType

/** The few AudioDeviceInfo fields we use; keeps mapping/selection pure and testable. */
data class DeviceCandidate(val type: Int, val productName: String, val address: String)

/** Pure AudioDeviceInfo -> [OutputDevice] mapping and "which one is the media route" heuristic. */
object DeviceMapping {
    // Newer constants written as literals so the mapping is independent of compile-time API level.
    private const val TYPE_BLE_HEADSET = 26
    private const val TYPE_BLE_SPEAKER = 27
    private const val TYPE_BLE_BROADCAST = 30
    private const val TYPE_HDMI_EARC = 29

    fun typeOf(audioDeviceType: Int): OutputDeviceType = when (audioDeviceType) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> OutputDeviceType.BLUETOOTH_A2DP
        TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_BLE_BROADCAST -> OutputDeviceType.BLUETOOTH_LE
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> OutputDeviceType.WIRED
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> OutputDeviceType.USB
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputDeviceType.SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputDeviceType.EARPIECE
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, TYPE_HDMI_EARC -> OutputDeviceType.HDMI
        else -> OutputDeviceType.OTHER
    }

    /**
     * Last [OutputDevice.ADDRESS_SUFFIX_LENGTH] chars of a Bluetooth address (`AB:CD`); empty for non-BT types.
     * docs/DECISIONS.md §7: for a full 17-char MAC or its anonymised form the suffix is the same; for any other
     * length it is used only if it looks like a MAC tail. The full address is never kept.
     */
    fun addressSuffix(type: OutputDeviceType, rawAddress: String?): String {
        if (!type.isBluetooth || rawAddress == null) return ""
        val suffix = OutputDevice.addressSuffixOf(type, rawAddress)
        if (rawAddress.length == 17) return suffix
        return if (suffix.length == OutputDevice.ADDRESS_SUFFIX_LENGTH && suffix.all { it in "0123456789abcdefABCDEF:" }) suffix else ""
    }

    /** Bluetooth types that are not mapped to a processable type but are still "BT-like" for ranking. */
    private fun rank(audioDeviceType: Int): Int = when (audioDeviceType) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID,
        -> 0
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 1
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 2
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, TYPE_HDMI_EARC -> 3
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 4
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 5
        else -> 6
    }

    fun toOutputDevice(c: DeviceCandidate): OutputDevice {
        val type = typeOf(c.type)
        return OutputDevice(type, c.productName.trim(), addressSuffix(type, c.address))
    }

    /** Priority heuristic (API < 33): BT A2DP/LE/hearing aid > USB > wired > HDMI > speaker > earpiece > other. */
    fun pickCurrent(candidates: List<DeviceCandidate>): OutputDevice? =
        candidates.minByOrNull { rank(it.type) }?.let(::toOutputDevice)
}
