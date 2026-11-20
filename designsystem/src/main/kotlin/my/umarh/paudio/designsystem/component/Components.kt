package my.umarh.paudio.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import my.umarh.paudio.designsystem.PAudioIcon
import my.umarh.paudio.designsystem.R
import my.umarh.paudio.designsystem.chart.PAudioPreviews
import my.umarh.paudio.designsystem.chart.PreviewFrame
import my.umarh.paudio.designsystem.PAudioIcons

enum class StatusTone { Info, Ok, Warn, Error }

/** Small status pill: text + optional leading icon; the tone is never the only carrier of meaning (text always shown). */
@Composable
fun StatusChip(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        StatusTone.Info -> Pair(scheme.secondaryContainer, scheme.onSecondaryContainer)
        StatusTone.Ok -> Pair(scheme.tertiaryContainer, scheme.onTertiaryContainer)
        StatusTone.Warn -> Pair(scheme.primaryContainer, scheme.onPrimaryContainer)
        StatusTone.Error -> Pair(scheme.errorContainer, scheme.onErrorContainer)
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(50), color = container, contentColor = content) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) PAudioIcon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Hearing-health disclaimer (F13): always visible, NO dismiss / collapse / click affordance. The text comes from the
 * caller's strings (the normative copy lives in the feature module).
 */
@Composable
fun DisclaimerCard(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PAudioIcon(PAudioIcons.Info, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Titled card section. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

/** Pre-test instruction row (F2): text with the icon on the RIGHT. */
@Composable
fun InstructionRow(
    text: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        PAudioIcon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

/** Big Personalized / Original toggle (single choice segmented button, 56 dp tall). */
@Composable
fun SegmentedToggle(
    personalized: Boolean,
    onPersonalizedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    personalizedLabel: String = stringResource(R.string.ds_toggle_personalized),
    originalLabel: String = stringResource(R.string.ds_toggle_original),
    enabled: Boolean = true,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = personalized,
            onClick = { onPersonalizedChange(true) },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
            enabled = enabled,
            modifier = Modifier.heightIn(min = 56.dp),
        ) { Text(personalizedLabel, style = MaterialTheme.typography.titleMedium) }
        SegmentedButton(
            selected = !personalized,
            onClick = { onPersonalizedChange(false) },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
            enabled = enabled,
            modifier = Modifier.heightIn(min = 56.dp),
        ) { Text(originalLabel, style = MaterialTheme.typography.titleMedium) }
    }
}

@PAudioPreviews
@Composable
private fun ComponentsPreview() = PreviewFrame {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("Active", StatusTone.Ok, icon = PAudioIcons.Check)
            StatusChip("Conflict", StatusTone.Warn, icon = PAudioIcons.Warning)
            StatusChip("Error", StatusTone.Error, icon = PAudioIcons.Error)
            StatusChip("Info", StatusTone.Info)
        }
        InstructionRow("Turn off noise cancellation", PAudioIcons.GraphicEqOff)
        SectionCard(title = "Section") { Text("Body") }
        DisclaimerCard("PAudio is not a medical device.")
        SegmentedToggle(personalized = true, onPersonalizedChange = {})
    }
}
