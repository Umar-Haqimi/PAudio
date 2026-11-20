package my.umarh.paudio.audio.device

import android.media.AudioDeviceInfo
import com.google.common.truth.Truth.assertThat
import my.umarh.paudio.core.model.EnvSnapshot
import my.umarh.paudio.core.model.OutputDeviceType
import org.junit.Test
import kotlin.math.sqrt

class DeviceLogicTest {
    @Test
    fun typeMapping() {
        val m = DeviceMapping::typeOf
        assertThat(m(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)).isEqualTo(OutputDeviceType.BLUETOOTH_A2DP)
        assertThat(m(26)).isEqualTo(OutputDeviceType.BLUETOOTH_LE) // BLE_HEADSET
        assertThat(m(27)).isEqualTo(OutputDeviceType.BLUETOOTH_LE) // BLE_SPEAKER
        assertThat(m(AudioDeviceInfo.TYPE_WIRED_HEADSET)).isEqualTo(OutputDeviceType.WIRED)
        assertThat(m(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)).isEqualTo(OutputDeviceType.WIRED)
        assertThat(m(AudioDeviceInfo.TYPE_USB_HEADSET)).isEqualTo(OutputDeviceType.USB)
        assertThat(m(AudioDeviceInfo.TYPE_USB_DEVICE)).isEqualTo(OutputDeviceType.USB)
        assertThat(m(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)).isEqualTo(OutputDeviceType.SPEAKER)
        assertThat(m(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)).isEqualTo(OutputDeviceType.EARPIECE)
        assertThat(m(AudioDeviceInfo.TYPE_HDMI)).isEqualTo(OutputDeviceType.HDMI)
        assertThat(m(AudioDeviceInfo.TYPE_HDMI_ARC)).isEqualTo(OutputDeviceType.HDMI)
        assertThat(m(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)).isEqualTo(OutputDeviceType.OTHER)
        assertThat(m(9999)).isEqualTo(OutputDeviceType.OTHER)
    }

    @Test
    fun addressSuffixForFullAndAnonymisedMacAndNonBluetooth() {
        val bt = OutputDeviceType.BLUETOOTH_A2DP
        assertThat(DeviceMapping.addressSuffix(bt, "AA:BB:CC:DD:EE:FF")).isEqualTo("EE:FF")
        assertThat(DeviceMapping.addressSuffix(bt, "XX:XX:XX:XX:EE:FF")).isEqualTo("EE:FF")
        assertThat(DeviceMapping.addressSuffix(bt, "weird-address!")).isEmpty()
        assertThat(DeviceMapping.addressSuffix(bt, "")).isEmpty()
        assertThat(DeviceMapping.addressSuffix(bt, null)).isEmpty()
        assertThat(DeviceMapping.addressSuffix(OutputDeviceType.WIRED, "1:2:3:4:5:6")).isEmpty()
        assertThat(DeviceMapping.addressSuffix(OutputDeviceType.USB, "card=1;device=0")).isEmpty()
    }

    @Test
    fun headsetKeyDerivedFromCandidateIsStableForBothAddressForms() {
        val full = DeviceMapping.toOutputDevice(DeviceCandidate(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, " Buds Pro ", "AA:BB:CC:DD:ee:ff"))
        val anon = DeviceMapping.toOutputDevice(DeviceCandidate(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds Pro", "XX:XX:XX:XX:EE:FF"))
        assertThat(full.headsetKey).isEqualTo(anon.headsetKey)
        assertThat(full.headsetKey.value).isEqualTo("BLUETOOTH_A2DP|Buds Pro|EE:FF")
        val wired = DeviceMapping.toOutputDevice(DeviceCandidate(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Headphones", ""))
        assertThat(wired.headsetKey.value).isEqualTo("WIRED|Headphones|")
    }

    @Test
    fun priorityHeuristic() {
        fun c(t: Int, n: String) = DeviceCandidate(t, n, "")
        val speaker = c(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "spk")
        val wired = c(AudioDeviceInfo.TYPE_WIRED_HEADSET, "wired")
        val usb = c(AudioDeviceInfo.TYPE_USB_HEADSET, "usb")
        val bt = c(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "bt")
        assertThat(DeviceMapping.pickCurrent(emptyList())).isNull()
        assertThat(DeviceMapping.pickCurrent(listOf(speaker))!!.productName).isEqualTo("spk")
        assertThat(DeviceMapping.pickCurrent(listOf(speaker, wired))!!.productName).isEqualTo("wired")
        assertThat(DeviceMapping.pickCurrent(listOf(wired, usb, speaker))!!.productName).isEqualTo("usb")
        assertThat(DeviceMapping.pickCurrent(listOf(speaker, wired, usb, bt))!!.productName).isEqualTo("bt")
        assertThat(DeviceMapping.pickCurrent(listOf(speaker, c(AudioDeviceInfo.TYPE_HEARING_AID, "ha"), wired))!!.productName)
            .isEqualTo("ha")
    }

    @Test
    fun noiseClassification() {
        assertThat(NoiseLevel.classify(-90f)).isEqualTo(NoiseLevel.QUIET)
        assertThat(NoiseLevel.classify(-60.01f)).isEqualTo(NoiseLevel.QUIET)
        assertThat(NoiseLevel.classify(-60f)).isEqualTo(NoiseLevel.OK)
        assertThat(NoiseLevel.classify(-45.01f)).isEqualTo(NoiseLevel.OK)
        assertThat(NoiseLevel.classify(-45f)).isEqualTo(NoiseLevel.TOO_NOISY)
        assertThat(NoiseLevel.classify(-10f)).isEqualTo(NoiseLevel.TOO_NOISY)
    }

    @Test
    fun rmsDbfs() {
        assertThat(NoiseMath.rmsDbfs(ShortArray(100))).isEqualTo(NoiseMath.FLOOR_DBFS)
        val fullScaleSquare = ShortArray(100) { if (it % 2 == 0) Short.MAX_VALUE else (-32768).toShort() }
        assertThat(NoiseMath.rmsDbfs(fullScaleSquare)).isWithin(0.01f).of(0f)
        val half = ShortArray(100) { 16384 }
        assertThat(NoiseMath.rmsDbfs(half)).isWithin(0.01f).of(-6.02f)
        assertThat(sqrt(2.0)).isGreaterThan(1.0)
        assertThat(NoiseMath.rmsDbfs(half, 0)).isEqualTo(NoiseMath.FLOOR_DBFS)
    }

    @Test
    fun verdict() {
        assertThat(EnvironmentVerdict.of(EnvSnapshot(false, 0f, false, false)).isBlocking).isFalse()
        val v = EnvironmentVerdict.of(EnvSnapshot(mono = true, balance = -0.2f, spatializerOn = true, dndOn = true))
        assertThat(v.blocking).containsExactly(
            EnvWarning.MONO_ON, EnvWarning.BALANCE_OFF_CENTER, EnvWarning.SPATIALIZER_ON,
        ).inOrder()
        assertThat(v.dndOn).isTrue()
        assertThat(EnvironmentVerdict.of(EnvSnapshot(balance = 0.05f)).blocking).isEmpty() // not > 0.05
        assertThat(EnvironmentVerdict.of(EnvSnapshot(balance = 0.051f)).blocking).containsExactly(EnvWarning.BALANCE_OFF_CENTER)
        val unknown = EnvironmentVerdict.of(EnvSnapshot())
        assertThat(unknown.isBlocking).isFalse()
        assertThat(unknown.unknown).containsExactly(EnvCheck.MONO, EnvCheck.BALANCE, EnvCheck.SPATIALIZER, EnvCheck.DND)
    }
}
