package my.umarh.paudio.feature.preference

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.Serializable
import my.umarh.paudio.core.dsp.DspGrid
import my.umarh.paudio.core.preference.AbSide
import my.umarh.paudio.core.preference.DemoClip
import my.umarh.paudio.core.preference.PreferenceAxis
import my.umarh.paudio.designsystem.PAudioIcon
import my.umarh.paudio.designsystem.PAudioIcons
import my.umarh.paudio.designsystem.chart.EqGraph
import my.umarh.paudio.designsystem.component.SectionCard
import my.umarh.paudio.designsystem.component.SegmentedToggle

/**
 * Type-safe navigation destination. [profileId] null = the active listener's profile for the current headset (resolved
 * through the data layer). A profile without a complete hearing test runs the comparisons over flat hearing compensation
 * (the preference curve alone).
 */
@Serializable
data class PreferenceRoute(val profileId: Long? = null)

fun NavGraphBuilder.preferenceScreen(onBack: () -> Unit) {
    composable<PreferenceRoute> {
        PreferenceScreen(onBack)
    }
}

internal const val CHOOSE_A_TAG = "pref_choose_a"
internal const val CHOOSE_B_TAG = "pref_choose_b"
internal const val CANT_TELL_TAG = "pref_cant_tell"
internal const val LISTEN_A_TAG = "pref_listen_a"
internal const val LISTEN_B_TAG = "pref_listen_b"
internal const val ANOTHER_SONG_TAG = "pref_another_song"
internal const val START_TAG = "pref_start"
internal const val FINE_TUNE_TAG = "pref_fine_tune"
internal const val SAVE_TAG = "pref_save"
internal const val SLIDER_STRENGTH_TAG = "pref_slider_strength"
internal const val SLIDER_BASS_TAG = "pref_slider_bass"
internal const val SLIDER_PRESENCE_TAG = "pref_slider_presence"
internal const val SLIDER_TREBLE_TAG = "pref_slider_treble"
internal const val EQ_GRAPH_TAG = "pref_eq_graph"

/** Everything the content needs from the ViewModel, so Compose tests can drive it without Hilt. */
internal class PreferenceActions(
    val onBack: () -> Unit,
    val onBegin: () -> Unit = {},
    /** The round number is passed back so a stale double tap on an answered round is ignored. */
    val onChoose: (AbSide, Int) -> Unit = { _, _ -> },
    val onCantTell: (Int) -> Unit = {},
    val onListen: (AbSide) -> Unit = {},
    val onTogglePlay: () -> Unit = {},
    val onAnotherSong: () -> Unit = {},
    val onRetryAudio: () -> Unit = {},
    val onFineTune: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onStrength: (Float) -> Unit = {},
    val onBass: (Float) -> Unit = {},
    val onPresence: (Float) -> Unit = {},
    val onTreble: (Float) -> Unit = {},
    val onPersonalized: (Boolean) -> Unit = {},
    val onClip: (DemoClip) -> Unit = {},
)

@Composable
internal fun PreferenceScreen(onBack: () -> Unit, viewModel: PreferenceViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    // P0 + background rule: the player (and its bypass token) exists only while the screen is STARTED and composed.
    LifecycleStartEffect(viewModel) {
        viewModel.startAudio()
        onStopOrDispose { viewModel.stopAudio() }
    }
    LaunchedEffect(ui.saved) { if (ui.saved) onBack() }
    PreferenceScreenContent(
        ui = ui,
        actions = PreferenceActions(
            onBack = onBack,
            onBegin = viewModel::begin,
            onChoose = { side, n -> viewModel.choose(side, n) },
            onCantTell = { n -> viewModel.cantTell(n) },
            onListen = viewModel::listen,
            onTogglePlay = viewModel::togglePlay,
            onAnotherSong = viewModel::tryAnotherSong,
            onRetryAudio = viewModel::retryAudio,
            onFineTune = viewModel::openFineTune,
            onSave = viewModel::save,
            onStrength = viewModel::setStrength,
            onBass = viewModel::setBass,
            onPresence = viewModel::setPresence,
            onTreble = viewModel::setTreble,
            onPersonalized = viewModel::setPersonalized,
            onClip = viewModel::setClip,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreferenceScreenContent(ui: PreferenceUiState, actions: PreferenceActions) {
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    // Answers are only kept when saved: leaving mid-test asks first.
    val needsConfirm = ui.phase == PrefPhase.ROUND || ui.phase == PrefPhase.RESULT || ui.phase == PrefPhase.FINE_TUNE
    val requestLeave = { if (needsConfirm) confirmLeave = true else actions.onBack() }
    BackHandler(onBack = requestLeave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_preference_title), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = requestLeave, modifier = Modifier.size(48.dp)) {
                        PAudioIcon(PAudioIcons.ArrowBack, contentDescription = stringResource(R.string.preference_back))
                    }
                },
            )
        },
    ) { padding ->
        when (ui.phase) {
            PrefPhase.LOADING -> Centered(padding, loading = true, text = stringResource(R.string.preference_loading))
            PrefPhase.UNAVAILABLE -> Centered(
                padding,
                title = stringResource(R.string.preference_unavailable_title),
                text = stringResource(R.string.preference_unavailable_body),
            )
            PrefPhase.INTRO -> Page(padding) { IntroCard(ui, actions) }
            PrefPhase.ROUND -> Page(padding) { RoundCard(ui, actions) }
            PrefPhase.RESULT -> Page(padding) { ResultCard(ui, actions) }
            PrefPhase.FINE_TUNE -> Page(padding) { FineTuneCard(ui, actions) }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.preference_discard_title)) },
            text = { Text(stringResource(R.string.preference_discard_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLeave = false
                        actions.onBack()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.preference_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.preference_discard_cancel))
                }
            },
        )
    }
}

@Composable
private fun Centered(padding: PaddingValues, text: String, title: String? = null, loading: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) CircularProgressIndicator()
            if (title != null) Text(title, style = MaterialTheme.typography.titleLarge)
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Page(padding: PaddingValues, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
private fun IntroCard(ui: PreferenceUiState, actions: PreferenceActions) {
    SectionCard(title = stringResource(R.string.preference_intro_title)) {
        Text(stringResource(R.string.preference_intro_body), style = MaterialTheme.typography.bodyMedium)
        Text(
            pluralStringResource(R.plurals.preference_intro_count, ui.maxRounds, ui.maxRounds),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            stringResource(if (ui.hasHearingTest) R.string.preference_intro_hearing else R.string.preference_intro_no_hearing),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.preference_intro_tip),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = actions.onBegin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(START_TAG)) {
            Text(stringResource(R.string.preference_start))
        }
    }
}

@StringRes
internal fun clipNameRes(clip: DemoClip): Int = when (clip) {
    DemoClip.DISCIPLINE -> R.string.preference_clip_discipline
    DemoClip.ECHOPLEX -> R.string.preference_clip_echoplex
    DemoClip.GHOSTS_34 -> R.string.preference_clip_ghosts34
}

@StringRes
private fun hintRes(axis: PreferenceAxis?): Int = when (axis) {
    PreferenceAxis.BASS -> R.string.preference_round_hint_bass
    PreferenceAxis.PRESENCE -> R.string.preference_round_hint_presence
    PreferenceAxis.TREBLE -> R.string.preference_round_hint_treble
    null -> R.string.preference_gate_hint
}

@Composable
private fun RoundCard(ui: PreferenceUiState, actions: PreferenceActions) {
    SectionCard(title = stringResource(R.string.preference_round_progress, ui.roundNumber, ui.maxRounds)) {
        Text(stringResource(hintRes(ui.axis)), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ListenButton(
                label = stringResource(R.string.preference_listen_a),
                selected = ui.activeSide == AbSide.A,
                onClick = { actions.onListen(AbSide.A) },
                enabled = ui.audio == AudioStatus.READY,
                tag = LISTEN_A_TAG,
                modifier = Modifier.weight(1f),
            )
            ListenButton(
                label = stringResource(R.string.preference_listen_b),
                selected = ui.activeSide == AbSide.B,
                onClick = { actions.onListen(AbSide.B) },
                enabled = ui.audio == AudioStatus.READY,
                tag = LISTEN_B_TAG,
                modifier = Modifier.weight(1f),
            )
        }
        val now = when {
            !ui.playing -> stringResource(R.string.preference_now_paused)
            ui.activeSide == AbSide.A -> stringResource(R.string.preference_now_a)
            else -> stringResource(R.string.preference_now_b)
        }
        Text(now, style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        AudioStatusRow(ui, actions)
        Text(stringResource(R.string.preference_gate_hint), style = MaterialTheme.typography.bodySmall)
        // Gate progress is spelled out in words (never colour only).
        val a = stringResource(R.string.preference_option_a)
        val b = stringResource(R.string.preference_option_b)
        Text(
            stringResource(if (ui.aHeard) R.string.preference_heard else R.string.preference_not_heard, a) + "  ·  " +
                stringResource(if (ui.bHeard) R.string.preference_heard else R.string.preference_not_heard, b),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { actions.onChoose(AbSide.A, ui.roundNumber) },
                enabled = ui.canChoose,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(CHOOSE_A_TAG),
            ) { Text(stringResource(R.string.preference_choose_a)) }
            Button(
                onClick = { actions.onChoose(AbSide.B, ui.roundNumber) },
                enabled = ui.canChoose,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(CHOOSE_B_TAG),
            ) { Text(stringResource(R.string.preference_choose_b)) }
        }
        OutlinedButton(
            onClick = { actions.onCantTell(ui.roundNumber) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(CANT_TELL_TAG),
        ) { Text(stringResource(R.string.preference_cant_tell)) }
        TextButton(
            onClick = actions.onAnotherSong,
            modifier = Modifier.heightIn(min = 48.dp).testTag(ANOTHER_SONG_TAG),
        ) { Text(stringResource(R.string.preference_another_song)) }
        SongInfo(ui.clip)
    }
}

@Composable
private fun ListenButton(label: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean, tag: String, modifier: Modifier) {
    val content: @Composable () -> Unit = {
        if (selected) PAudioIcon(PAudioIcons.GraphicEq, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        Text(label)
    }
    // The selected side is marked with an icon and a selected state, not by colour alone.
    val m = modifier.heightIn(min = 56.dp).testTag(tag).semantics { this.selected = selected }
    if (selected) FilledTonalButton(onClick = onClick, enabled = enabled, modifier = m) { content() }
    else OutlinedButton(onClick = onClick, enabled = enabled, modifier = m) { content() }
}

@Composable
private fun AudioStatusRow(ui: PreferenceUiState, actions: PreferenceActions) {
    when (ui.audio) {
        AudioStatus.ERROR -> {
            Text(stringResource(R.string.preference_audio_error), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = actions.onRetryAudio, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.preference_audio_retry))
            }
        }
        AudioStatus.PREPARING -> Text(stringResource(R.string.preference_audio_preparing), style = MaterialTheme.typography.bodySmall)
        else -> FilledTonalButton(
            onClick = actions.onTogglePlay,
            enabled = ui.audio == AudioStatus.READY,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            PAudioIcon(if (ui.playing) PAudioIcons.Pause else PAudioIcons.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(if (ui.playing) R.string.preference_pause else R.string.preference_play))
        }
    }
}

@Composable
private fun SongInfo(clip: DemoClip) {
    val name = stringResource(clipNameRes(clip))
    Text(stringResource(R.string.preference_song, name), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.preference_attribution, name),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---- Result + fine-tune ----------------------------------------------------------------------------------------------

@Composable
private fun CurveGraph(ui: PreferenceUiState, personalized: Boolean) {
    val description = stringResource(R.string.preference_graph_description)
    EqGraph(
        leftDb = ui.gLeft,
        rightDb = ui.gRight,
        gridHz = DspGrid.gridHz,
        personalized = personalized,
        modifier = Modifier.fillMaxWidth().testTag(EQ_GRAPH_TAG).semantics { contentDescription = description },
    )
}

@Composable
private fun GainWords(ui: PreferenceUiState, locale: Locale) {
    GainLine(R.string.preference_value_bass, ui.gB, R.string.preference_word_bass_more, R.string.preference_word_bass_less, R.string.preference_word_bass_same, locale)
    GainLine(R.string.preference_value_presence, ui.gP, R.string.preference_word_presence_more, R.string.preference_word_presence_less, R.string.preference_word_presence_same, locale)
    GainLine(R.string.preference_value_treble, ui.gT, R.string.preference_word_treble_more, R.string.preference_word_treble_less, R.string.preference_word_treble_same, locale)
}

@Composable
private fun GainLine(@StringRes template: Int, gain: Float, @StringRes more: Int, @StringRes less: Int, @StringRes same: Int, locale: Locale) {
    val word = when {
        abs(gain) < UNCHANGED_DB -> same
        gain > 0f -> more
        else -> less
    }
    Text(stringResource(template, formatGain(gain, locale), stringResource(word)), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun ResultCard(ui: PreferenceUiState, actions: PreferenceActions) {
    val locale = LocalConfiguration.current.locales[0]
    SectionCard(title = stringResource(R.string.preference_result_title)) {
        Text(stringResource(R.string.preference_result_body), style = MaterialTheme.typography.bodyMedium)
        CurveGraph(ui, personalized = true)
        GainWords(ui, locale)
        SaveRow(ui, actions)
        OutlinedButton(
            onClick = actions.onFineTune,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(FINE_TUNE_TAG),
        ) { Text(stringResource(R.string.preference_fine_tune)) }
    }
}

@Composable
private fun SaveRow(ui: PreferenceUiState, actions: PreferenceActions) {
    if (ui.saveError) {
        Text(stringResource(R.string.preference_save_error), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    Button(
        onClick = actions.onSave,
        enabled = !ui.saving && !ui.saved,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(SAVE_TAG),
    ) { Text(stringResource(if (ui.saving) R.string.preference_saving else R.string.preference_save)) }
}

@Composable
private fun FineTuneCard(ui: PreferenceUiState, actions: PreferenceActions) {
    val locale = LocalConfiguration.current.locales[0]
    SectionCard(title = stringResource(R.string.preference_fine_title)) {
        Text(stringResource(R.string.preference_fine_body), style = MaterialTheme.typography.bodyMedium)
        SegmentedToggle(
            personalized = ui.personalized,
            onPersonalizedChange = actions.onPersonalized,
            personalizedLabel = stringResource(R.string.preference_toggle_personalized),
            originalLabel = stringResource(R.string.preference_toggle_original),
        )
        Text(
            stringResource(if (ui.personalized) R.string.preference_fine_now_personalized else R.string.preference_fine_now_original),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        AudioStatusRow(ui, actions)
        ClipPicker(ui.clip, actions.onClip)
        CurveGraph(ui, personalized = ui.personalized)
        TuneSlider(
            label = stringResource(R.string.preference_slider_strength),
            valueText = NumberFormat.getPercentInstance(locale).format(ui.strength.toDouble()),
            value = ui.strength,
            range = 0f..1f,
            onChange = actions.onStrength,
            tag = SLIDER_STRENGTH_TAG,
            enabled = ui.hasHearingTest,
        )
        if (!ui.hasHearingTest) {
            Text(
                stringResource(R.string.preference_strength_note_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TuneSlider(
            label = stringResource(R.string.preference_slider_bass),
            valueText = stringResource(R.string.preference_gain_value, formatGain(ui.gB, locale)),
            value = ui.gB,
            range = FineTuneRange.of(PreferenceAxis.BASS).toFloatRange(),
            onChange = actions.onBass,
            tag = SLIDER_BASS_TAG,
        )
        TuneSlider(
            label = stringResource(R.string.preference_slider_presence),
            valueText = stringResource(R.string.preference_gain_value, formatGain(ui.gP, locale)),
            value = ui.gP,
            range = FineTuneRange.of(PreferenceAxis.PRESENCE).toFloatRange(),
            onChange = actions.onPresence,
            tag = SLIDER_PRESENCE_TAG,
        )
        TuneSlider(
            label = stringResource(R.string.preference_slider_treble),
            valueText = stringResource(R.string.preference_gain_value, formatGain(ui.gT, locale)),
            value = ui.gT,
            range = FineTuneRange.of(PreferenceAxis.TREBLE).toFloatRange(),
            onChange = actions.onTreble,
            tag = SLIDER_TREBLE_TAG,
        )
        GainWords(ui, locale)
        SaveRow(ui, actions)
    }
}

private fun ClosedFloatingPointRange<Double>.toFloatRange(): ClosedFloatingPointRange<Float> = start.toFloat()..endInclusive.toFloat()

@Composable
private fun ClipPicker(selected: DemoClip, onClip: (DemoClip) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.preference_song_picker), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DemoClip.entries.forEach { clip ->
                FilterChip(
                    selected = clip == selected,
                    onClick = { onClip(clip) },
                    label = { Text(stringResource(clipNameRes(clip))) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        Text(
            stringResource(R.string.preference_attribution, stringResource(clipNameRes(selected))),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TuneSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    tag: String,
    enabled: Boolean = true,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(valueText, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(tag)
                .semantics {
                    contentDescription = label
                    stateDescription = valueText
                },
        )
    }
}

private const val UNCHANGED_DB = 0.25f

/** Locale-aware signed gain with up to two decimals ("+3", "-2.5"); zero has no sign. */
internal fun formatGain(value: Float, locale: Locale): String {
    val nf = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }
    val text = nf.format(abs(value.toDouble()))
    return when {
        abs(value) < 0.005f -> nf.format(0)
        value > 0 -> "+$text"
        else -> "−$text"
    }
}
