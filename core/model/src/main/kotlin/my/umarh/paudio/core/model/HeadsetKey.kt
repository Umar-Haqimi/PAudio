package my.umarh.paudio.core.model

import kotlinx.serialization.Serializable

/**
 * Stable identity of a headset (SPEC §5.5): `"${type}|${productName.trim()}|${addressSuffix}"`.
 * `addressSuffix` is the last 5 characters of the Bluetooth address for Bluetooth devices and empty
 * for wired/USB (and every other type), so no `BLUETOOTH_CONNECT` permission is needed.
 */
@JvmInline
@Serializable
value class HeadsetKey(val value: String) {
    override fun toString(): String = value

    companion object {
        fun of(type: OutputDeviceType, productName: String, addressSuffix: String): HeadsetKey {
            val suffix = if (type.isBluetooth) addressSuffix.takeLast(OutputDevice.ADDRESS_SUFFIX_LENGTH) else ""
            return HeadsetKey("${type.name}|${productName.trim()}|$suffix")
        }

        fun from(device: OutputDevice): HeadsetKey = of(device.type, device.productName, device.addressSuffix)
    }
}
