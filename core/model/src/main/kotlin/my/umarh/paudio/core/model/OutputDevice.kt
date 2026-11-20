package my.umarh.paudio.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class OutputDeviceType {
    WIRED,
    USB,
    BLUETOOTH_A2DP,
    BLUETOOTH_LE,
    SPEAKER,
    EARPIECE,
    HDMI,
    OTHER,
    ;

    /**
     * Whether PAudio may process audio routed to this device (SPEC §5.5: speaker, earpiece and HDMI are
     * never processed). [OTHER] is treated as not processable: an unknown route is never touched.
     */
    val isProcessable: Boolean
        get() = when (this) {
            WIRED, USB, BLUETOOTH_A2DP, BLUETOOTH_LE -> true
            SPEAKER, EARPIECE, HDMI, OTHER -> false
        }

    val isBluetooth: Boolean get() = this == BLUETOOTH_A2DP || this == BLUETOOTH_LE
}

/**
 * The current audio output as seen without `BLUETOOTH_CONNECT`.
 * [addressSuffix]: last 5 chars of the (anonymized `XX:XX:XX:XX:AB:CD`) Bluetooth address, i.e. `AB:CD`;
 * empty for wired/USB.
 */
@Serializable
data class OutputDevice(
    val type: OutputDeviceType,
    val productName: String,
    val addressSuffix: String = "",
) {
    val isProcessable: Boolean get() = type.isProcessable
    val headsetKey: HeadsetKey get() = HeadsetKey.from(this)

    companion object {
        const val ADDRESS_SUFFIX_LENGTH = 5

        /** Derives the stored suffix from a raw `AudioDeviceInfo.getAddress()` value. */
        fun addressSuffixOf(type: OutputDeviceType, rawAddress: String?): String =
            if (type.isBluetooth && rawAddress != null) rawAddress.takeLast(ADDRESS_SUFFIX_LENGTH) else ""
    }
}
