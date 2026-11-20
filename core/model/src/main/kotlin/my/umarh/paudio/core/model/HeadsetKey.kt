package my.umarh.paudio.core.model

import kotlinx.serialization.Serializable

/**
 * Stable identity of a headset (SPEC §5.5): `"${type}|${productName.trim()}|${addressSuffix}"`.
 * `addressSuffix` is the last 5 characters of the Bluetooth address for Bluetooth devices and empty
 * for wired/USB (and every other type), so no `BLUETOOTH_CONNECT` permission is needed.
 *
 * Normalisation: the Bluetooth suffix is upper-cased, and a `'|'` inside `productName` is replaced by `'/'`
 * so the separator stays unambiguous. The enum names in [OutputDeviceType] are part of the key format and
 * are persisted: never rename them (pinned by a test).
 */
@JvmInline
@Serializable
value class HeadsetKey(val value: String) {
    override fun toString(): String = value

    companion object {
        fun of(type: OutputDeviceType, productName: String, addressSuffix: String): HeadsetKey {
            val suffix = if (type.isBluetooth) {
                addressSuffix.takeLast(OutputDevice.ADDRESS_SUFFIX_LENGTH).uppercase()
            } else {
                ""
            }
            return HeadsetKey("${type.name}|${productName.trim().replace('|', '/')}|$suffix")
        }

        fun from(device: OutputDevice): HeadsetKey = of(device.type, device.productName, device.addressSuffix)
    }
}
