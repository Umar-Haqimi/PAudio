package my.umarh.paudio.feature.results

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.sign
import kotlin.math.truncate
import kotlinx.serialization.Serializable
import my.umarh.paudio.core.dsp.DspGrid
import my.umarh.paudio.core.preference.DemoClip
import my.umarh.paudio.designsystem.PAudioIcon
import my.umarh.paudio.designsystem.PAudioIcons
import my.umarh.paudio.designsystem.chart.EqGraph
import my.umarh.paudio.designsystem.chart.HearingButterfly
import my.umarh.paudio.designsystem.component.DisclaimerCard
import my.umarh.paudio.designsystem.component.SectionCard
import my.umarh.paudio.designsystem.component.SegmentedToggle
import my.umarh.paudio.designsystem.component.StatusChip
import my.umarh.paudio.designsystem.component.StatusTone

/** Type-safe navigation destination: the results of one stored hearing test. */
@Serializable
data class ResultsRoute(val testId: Long)

/**
 * @param onSeeHealth opens Hearing health for (profileId, testId).
 * @param onPreferenceTest starts the A/B preference test for [profileId] (chosen in the exit dialog).
 * @param onDone leaves Results (also the "Not now" answer of the exit dialog).
 * @param onRedoTest optional: offered only when the Quick test looks unreliable; leaves Results without the exit dialog.
 */
fun NavGraphBuilder.resultsScreen(
    onSeeHealth: (profileId: Long, testId: Long) -> Unit,
    onPreferenceTest: (profileId: Long) -> Unit,
    onDone: () -> Unit,
    onRedoTest: (() -> Unit)? = null,
) {
    composable<ResultsRoute> {
        ResultsScreen(onSeeHealth, onPreferenceTest, onDone, onRedoTest)
    }
}

@Composable
internal fun ResultsScreen(
    onSeeHealth: (profileId: Long, testId: Long) -> Unit,
    onPreferenceTest: (profileId: Long) -> Unit,
    onDone: () -> Unit,
    onRedoTest: (() -> Unit)?,
    viewModel: ResultsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val ab by viewModel.ab.collectAsStateWithLifecycle()
    // P0 + background rule: the player (and its bypass token) exists only while the screen is STARTED and composed.
    LifecycleStartEffect(viewModel) {
        viewModel.startAb()
        onStopOrDispose { viewModel.stopAb() }
    }
    val context = LocalContext.current
    // SAF only (SPEC 6): OpenDocument needs no storage permission; the temporary grant lasts for this session.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.useOwnFile(uri.toString(), displayNameOf(context, uri))
    }
    ResultsScreenContent(
        ui = ui,
        ab = ab,
        onSetPersonalized = viewModel::setPersonalized,
        onTogglePlay = viewModel::togglePlay,
        onPickFile = { picker.launch(arrayOf("audio/*")) },
        onUseDemo = viewModel::useDemoClip,
        onRetryAb = viewModel::retryAb,
        onSeeHealth = onSeeHealth,
        onPreferenceTest = onPreferenceTest,
        onDone = onDone,
        onRedoTest = onRedoTest,
    )
}

private fun displayNameOf(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (_: Exception) {
    null
}

internal const val EXIT_DIALOG_TAG = "results_exit_dialog"
internal const val AB_CARD_TAG = "results_ab_card"
internal const val PLAY_TAG = "results_play"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResultsScreenContent(
    ui: ResultsUiState,
    ab: AbUiState,
    onSetPersonalized: (Boolean) -> Unit,
    onTogglePlay: () -> Unit,
    onPickFile: () -> Unit,
    onUseDemo: () -> Unit,
    onRetryAb: () -> Unit,
    onSeeHealth: (profileId: Long, testId: Long) -> Unit,
    onPreferenceTest: (profileId: Long) -> Unit,
    onDone: () -> Unit,
    onRedoTest: (() -> Unit)? = null,
) {
    var showExit by rememberSaveable { mutableStateOf(false) }
    val content = (ui as? ResultsUiState.Content)?.content
    // F12: any way out (system back, top-bar back, Done) asks about the preference test first; nothing to ask when
    // there are no results to fine-tune.
    val requestLeave = { if (content != null) showExit = true else onDone() }
    BackHandler(onBack = requestLeave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_results_title), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = requestLeave) {
                        PAudioIcon(PAudioIcons.ArrowBack, contentDescription = stringResource(R.string.results_back))
                    }
                },
            )
        },
    ) { padding ->
        when (ui) {
            ResultsUiState.Loading -> CenteredMessage(padding, loading = true, text = stringResource(R.string.results_loading))
            is ResultsUiState.Unavailable -> CenteredMessage(
                padding,
                title = stringResource(R.string.results_unavailable_title),
                text = stringResource(unavailableRes(ui.reason)),
            )
            is ResultsUiState.Content -> ResultsBody(
                content = ui.content,
                ab = ab,
                padding = padding,
                onSetPersonalized = onSetPersonalized,
                onTogglePlay = onTogglePlay,
                onPickFile = onPickFile,
                onUseDemo = onUseDemo,
                onRetryAb = onRetryAb,
                onSeeHealth = { onSeeHealth(ui.content.profileId, ui.content.testId) },
                onDone = requestLeave,
                onRedoTest = onRedoTest,
            )
        }
    }

    if (showExit && content != null) {
        AlertDialog(
            modifier = Modifier.testTag(EXIT_DIALOG_TAG),
            onDismissRequest = { showExit = false },
            title = { Text(stringResource(R.string.results_exit_title)) },
            text = { Text(stringResource(R.string.results_exit_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExit = false
                        onPreferenceTest(content.profileId)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.results_exit_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showExit = false
                        onDone()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.results_exit_dismiss)) }
            },
        )
    }
}

@StringRes
internal fun unavailableRes(reason: UnavailableReason): Int = when (reason) {
    UnavailableReason.TEST_MISSING -> R.string.results_unavailable_test_missing
    UnavailableReason.PROFILE_MISSING -> R.string.results_unavailable_profile_missing
    UnavailableReason.INCOMPLETE -> R.string.results_unavailable_incomplete
    UnavailableReason.ERROR -> R.string.results_unavailable_error
}

@StringRes
internal fun wordingRes(w: PtaWording): Int = when (w) {
    PtaWording.SIMILAR -> R.string.results_wording_similar
    PtaWording.SLIGHTLY_HIGHER -> R.string.results_wording_slightly_higher
    PtaWording.HIGHER -> R.string.results_wording_higher
    PtaWording.MUCH_HIGHER -> R.string.results_wording_much_higher
}

@StringRes
internal fun clipNameRes(clip: DemoClip): Int = when (clip) {
    DemoClip.DISCIPLINE -> R.string.results_clip_discipline
    DemoClip.ECHOPLEX -> R.string.results_clip_echoplex
    DemoClip.GHOSTS_34 -> R.string.results_clip_ghosts34
}

@Composable
private fun CenteredMessage(padding: PaddingValues, text: String, title: String? = null, loading: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) CircularProgressIndicator()
            if (title != null) Text(title, style = MaterialTheme.typography.titleLarge)
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ResultsBody(
    content: ResultsContent,
    ab: AbUiState,
    padding: PaddingValues,
    onSetPersonalized: (Boolean) -> Unit,
    onTogglePlay: () -> Unit,
    onPickFile: () -> Unit,
    onUseDemo: () -> Unit,
    onRetryAb: () -> Unit,
    onSeeHealth: () -> Unit,
    onDone: () -> Unit,
    onRedoTest: (() -> Unit)?,
) {
    val locale = LocalConfiguration.current.locales[0]
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ButterflyCard(content, locale)
        SummaryCard(content, locale)
        NotesCard(content, onRedoTest)
        AbCard(ab, onSetPersonalized, onTogglePlay, onPickFile, onUseDemo, onRetryAb)
        EqCard(ab)
        OutlinedButton(onClick = onSeeHealth, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            PAudioIcon(PAudioIcons.HealthAndSafety, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.results_see_health))
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.results_done))
        }
        DisclaimerCard(stringResource(R.string.results_disclaimer_short))
    }
}

@Composable
private fun earSentence(r: EarResult, locale: Locale): String =
    stringResource(R.string.results_pta_value, formatDb(r.pta3, locale)) + ". " + stringResource(wordingRes(r.wording))

@Composable
private fun ButterflyCard(content: ResultsContent, locale: Locale) {
    val description = stringResource(
        R.string.results_butterfly_description,
        earSentence(content.right, locale),
        earSentence(content.left, locale),
    )
    SectionCard(title = stringResource(R.string.results_butterfly_title)) {
        // Wings are labelled in text: left/right is never carried by colour alone.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.results_wing_left_label), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.results_wing_right_label), style = MaterialTheme.typography.labelLarge)
        }
        HearingButterfly(
            leftHl = content.leftDisplay,
            rightHl = content.rightDisplay,
            frequenciesHz = content.frequenciesHz,
            censoredLeft = content.censoredLeft,
            censoredRight = content.censoredRight,
            contentDescription = description,
            modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
        )
        Text(
            stringResource(R.string.results_butterfly_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryCard(content: ResultsContent, locale: Locale) {
    SectionCard(title = stringResource(R.string.results_summary_title)) {
        EarSummary(stringResource(R.string.results_ear_right), content.right, locale)
        EarSummary(stringResource(R.string.results_ear_left), content.left, locale)
        Text(
            stringResource(R.string.results_summary_caveat),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EarSummary(name: String, r: EarResult, locale: Locale) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.results_pta_label) + ": " + stringResource(R.string.results_pta_value, formatDb(r.pta3, locale)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(stringResource(wordingRes(r.wording)), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NotesCard(content: ResultsContent, onRedoTest: (() -> Unit)?) {
    val rel = content.reliability
    if (!rel.hasNote && !content.asymmetry && !content.anyCensored) return
    SectionCard(title = stringResource(R.string.results_notes_title)) {
        if (rel.unreliable) {
            Note(StatusTone.Warn, PAudioIcons.Warning, stringResource(R.string.results_unreliable_chip), stringResource(R.string.results_unreliable_body))
            if (rel.offerRedo && onRedoTest != null) {
                OutlinedButton(onClick = onRedoTest, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.results_redo))
                }
            }
        }
        if (rel.lowConfidencePoints > 0) {
            Text(
                pluralStringResource(R.plurals.results_low_confidence, rel.lowConfidencePoints, rel.lowConfidencePoints),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (content.asymmetry) {
            Note(StatusTone.Info, PAudioIcons.Info, stringResource(R.string.results_asymmetry_chip), stringResource(R.string.results_asymmetry_body))
        }
        if (content.anyCensored) {
            val body = when {
                content.left.anyCensored && content.right.anyCensored -> R.string.results_beyond_range_both
                content.left.anyCensored -> R.string.results_beyond_range_left
                else -> R.string.results_beyond_range_right
            }
            Note(StatusTone.Info, PAudioIcons.Info, stringResource(R.string.results_beyond_range_chip), stringResource(body))
        }
    }
}

@Composable
private fun Note(tone: StatusTone, icon: Int, chip: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusChip(chip, tone, icon = icon)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AbCard(
    ab: AbUiState,
    onSetPersonalized: (Boolean) -> Unit,
    onTogglePlay: () -> Unit,
    onPickFile: () -> Unit,
    onUseDemo: () -> Unit,
    onRetryAb: () -> Unit,
) {
    SectionCard(modifier = Modifier.testTag(AB_CARD_TAG), title = stringResource(R.string.results_ab_title)) {
        Text(stringResource(R.string.results_ab_intro), style = MaterialTheme.typography.bodyMedium)
        SegmentedToggle(
            personalized = ab.personalized,
            onPersonalizedChange = onSetPersonalized,
            personalizedLabel = stringResource(R.string.results_ab_personalized),
            originalLabel = stringResource(R.string.results_ab_original),
        )
        Text(
            stringResource(if (ab.personalized) R.string.results_ab_now_personalized else R.string.results_ab_now_original),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        when (ab.status) {
            AbStatus.ERROR -> {
                Text(stringResource(R.string.results_ab_error), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetryAb, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.results_ab_retry)) }
            }
            else -> {
                val ready = ab.status == AbStatus.READY
                FilledTonalButton(
                    onClick = onTogglePlay,
                    enabled = ready,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(PLAY_TAG),
                ) {
                    PAudioIcon(if (ab.playing) PAudioIcons.Pause else PAudioIcons.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(if (ab.playing) R.string.results_ab_pause else R.string.results_ab_play))
                }
                if (ab.status == AbStatus.PREPARING) {
                    Text(stringResource(R.string.results_ab_preparing), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (ab.fileError) {
            Text(stringResource(R.string.results_ab_file_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        val ownFile = ab.sourceKind == AbSourceKind.USER_FILE
        Text(
            if (ownFile) {
                ab.fileName?.let { stringResource(R.string.results_ab_source_file, it) } ?: stringResource(R.string.results_ab_source_file_unnamed)
            } else {
                stringResource(R.string.results_ab_source_clip, stringResource(clipNameRes(ab.clip)))
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!ownFile) {
            Text(
                stringResource(R.string.results_ab_attribution, stringResource(clipNameRes(ab.clip))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onPickFile, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.results_ab_use_own)) }
            if (ownFile) {
                TextButton(onClick = onUseDemo, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.results_ab_use_demo)) }
            }
        }
    }
}

@Composable
private fun EqCard(ab: AbUiState) {
    SectionCard(title = stringResource(R.string.results_eq_title)) {
        // Springs to flat when Original is selected and back to G(f) for Personalized (EqGraph animates `personalized`).
        EqGraph(
            leftDb = ab.gLeft,
            rightDb = ab.gRight,
            gridHz = DspGrid.gridHz,
            personalized = ab.personalized,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(if (ab.personalized) R.string.results_eq_body_personalized else R.string.results_eq_body_original),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Locale-aware, one decimal, truncated toward zero (never rounded up) so a value just under a boundary is not shown as
 * the boundary itself (19.6 stays 19.6). Never prints "-0".
 */
internal fun formatDb(value: Double, locale: Locale): String {
    val tenths = truncate(value * 10 + sign(value) * 1e-6)
    val shown = if (tenths == 0.0) 0.0 else tenths / 10
    return NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 1
        roundingMode = java.math.RoundingMode.HALF_UP
    }.format(shown)
}
