package my.umarh.paudio.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material Symbols (Rounded, weight 400, 24 px, unfilled; Apache-2.0) vendored as VectorDrawables.
 * Use [PAudioIcon] (or `painterResource(PAudioIcons.Hearing)`).
 */
object PAudioIcons {
    @DrawableRes val Add: Int = R.drawable.ic_add
    @DrawableRes val ArrowBack: Int = R.drawable.ic_arrow_back
    @DrawableRes val Bluetooth: Int = R.drawable.ic_bluetooth
    @DrawableRes val Check: Int = R.drawable.ic_check
    @DrawableRes val ChevronRight: Int = R.drawable.ic_chevron_right
    @DrawableRes val Close: Int = R.drawable.ic_close
    @DrawableRes val ContentCopy: Int = R.drawable.ic_content_copy
    @DrawableRes val Delete: Int = R.drawable.ic_delete
    @DrawableRes val DoNotDisturbOnTotalSilence: Int = R.drawable.ic_do_not_disturb_on_total_silence
    @DrawableRes val Download: Int = R.drawable.ic_download
    @DrawableRes val Earbuds: Int = R.drawable.ic_earbuds
    @DrawableRes val Edit: Int = R.drawable.ic_edit
    @DrawableRes val Equalizer: Int = R.drawable.ic_equalizer
    @DrawableRes val Error: Int = R.drawable.ic_error
    @DrawableRes val GraphicEq: Int = R.drawable.ic_graphic_eq
    @DrawableRes val GraphicEqOff: Int = R.drawable.ic_graphic_eq_off
    @DrawableRes val Group: Int = R.drawable.ic_group
    @DrawableRes val Headphones: Int = R.drawable.ic_headphones
    @DrawableRes val HeadsetMic: Int = R.drawable.ic_headset_mic
    @DrawableRes val HealthAndSafety: Int = R.drawable.ic_health_and_safety
    @DrawableRes val Hearing: Int = R.drawable.ic_hearing
    @DrawableRes val Help: Int = R.drawable.ic_help
    @DrawableRes val Home: Int = R.drawable.ic_home
    @DrawableRes val Info: Int = R.drawable.ic_info
    @DrawableRes val Mic: Int = R.drawable.ic_mic
    @DrawableRes val Monitoring: Int = R.drawable.ic_monitoring
    @DrawableRes val MoreVert: Int = R.drawable.ic_more_vert
    @DrawableRes val NoiseControlOff: Int = R.drawable.ic_noise_control_off
    @DrawableRes val Notifications: Int = R.drawable.ic_notifications
    @DrawableRes val Pause: Int = R.drawable.ic_pause
    @DrawableRes val Person: Int = R.drawable.ic_person
    @DrawableRes val PlayArrow: Int = R.drawable.ic_play_arrow
    @DrawableRes val PowerSettingsNew: Int = R.drawable.ic_power_settings_new
    @DrawableRes val Settings: Int = R.drawable.ic_settings
    @DrawableRes val Speaker: Int = R.drawable.ic_speaker
    @DrawableRes val SwapHoriz: Int = R.drawable.ic_swap_horiz
    @DrawableRes val SyncProblem: Int = R.drawable.ic_sync_problem
    @DrawableRes val Tune: Int = R.drawable.ic_tune
    @DrawableRes val Upload: Int = R.drawable.ic_upload
    @DrawableRes val Usb: Int = R.drawable.ic_usb
    @DrawableRes val VolumeOff: Int = R.drawable.ic_volume_off
    @DrawableRes val Warning: Int = R.drawable.ic_warning
}

/** Tinted icon; [contentDescription] null marks the icon decorative. */
@Composable
fun PAudioIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painter = painterResource(icon), contentDescription = contentDescription, modifier = modifier, tint = tint)
}
