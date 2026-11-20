package my.umarh.paudio.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HeadsetKeyTest {
    @Test
    fun bluetoothUsesLastFiveCharsOfAnonymizedAddress() {
        val suffix = OutputDevice.addressSuffixOf(OutputDeviceType.BLUETOOTH_A2DP, "XX:XX:XX:XX:AB:CD")
        assertThat(suffix).isEqualTo("AB:CD")
        val key = HeadsetKey.from(OutputDevice(OutputDeviceType.BLUETOOTH_A2DP, "  Pixel Buds Pro ", suffix))
        assertThat(key.value).isEqualTo("BLUETOOTH_A2DP|Pixel Buds Pro|AB:CD")
    }

    @Test
    fun wiredAndUsbHaveEmptySuffixEvenIfAddressGiven() {
        assertThat(OutputDevice.addressSuffixOf(OutputDeviceType.WIRED, "whatever")).isEmpty()
        val key = HeadsetKey.from(OutputDevice(OutputDeviceType.USB, "USB-C Dongle", "12:34"))
        assertThat(key.value).isEqualTo("USB|USB-C Dongle|")
    }

    @Test
    fun differentSuffixMeansDifferentKey() {
        val a = HeadsetKey.of(OutputDeviceType.BLUETOOTH_A2DP, "Buds", "AB:CD")
        val b = HeadsetKey.of(OutputDeviceType.BLUETOOTH_A2DP, "Buds", "AB:CE")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun processableTypes() {
        val processable = OutputDeviceType.entries.filter { it.isProcessable }
        assertThat(processable).containsExactly(
            OutputDeviceType.WIRED, OutputDeviceType.USB,
            OutputDeviceType.BLUETOOTH_A2DP, OutputDeviceType.BLUETOOTH_LE,
        )
    }

    @Test
    fun suffixIsUpperCasedAndPipesInNameAreReplaced() {
        val key = HeadsetKey.of(OutputDeviceType.BLUETOOTH_LE, " A|B Buds ", "ab:cd")
        assertThat(key.value).isEqualTo("BLUETOOTH_LE|A/B Buds|AB:CD")
    }

    @Test
    fun enumNamesUsedInKeysArePinned() {
        // These names are persisted in keys and in exported JSON; renaming them breaks existing profiles.
        assertThat(OutputDeviceType.entries.map { it.name }).containsExactly(
            "WIRED", "USB", "BLUETOOTH_A2DP", "BLUETOOTH_LE", "SPEAKER", "EARPIECE", "HDMI", "OTHER",
        ).inOrder()
    }
}
