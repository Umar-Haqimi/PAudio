package my.umarh.paudio.feature.health

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import my.umarh.paudio.core.audiometry.WhoGrade
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.designsystem.PAudioIcon
import my.umarh.paudio.designsystem.PAudioIcons
import my.umarh.paudio.designsystem.chart.Audiogram
import my.umarh.paudio.designsystem.chart.TrendChart
import my.umarh.paudio.designsystem.chart.WhoBandLegend
import my.umarh.paudio.designsystem.component.DisclaimerCard
import my.umarh.paudio.designsystem.component.SectionCard
import my.umarh.paudio.designsystem.component.StatusChip
import my.umarh.paudio.designsystem.component.StatusTone

/**
 * Type-safe navigation destination. Callers pass the profile whose tests to show ([profileId]) and optionally a
 * specific test ([testId]; default = latest). With no [profileId] the most recently tested profile is used.
 */
@Serializable
data class HealthRoute(val profileId: Long? = null, val testId: Long? = null)

fun NavGraphBuilder.healthScreen(onBack: () -> Unit) {
    composable<HealthRoute> {
        HealthScreen(onBack = onBack)
    }
}

@Composable
internal fun HealthScreen(onBack: () -> Unit, viewModel: HealthViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HealthScreenContent(state = state, onBack = onBack)
}

internal const val CONTENT_TAG = "health_content"
internal const val DISCLAIMER_TAG = "health_disclaimer"

@StringRes
internal fun gradeLabelRes(grade: WhoGrade): Int = when (grade) {
    WhoGrade.NORMAL -> R.string.health_grade_normal
    WhoGrade.MILD -> R.string.health_grade_mild
    WhoGrade.MODERATE -> R.string.health_grade_moderate
    WhoGrade.MODERATELY_SEVERE -> R.string.health_grade_moderately_severe
    WhoGrade.SEVERE -> R.string.health_grade_severe
    WhoGrade.PROFOUND -> R.string.health_grade_profound
    WhoGrade.COMPLETE -> R.string.health_grade_complete
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HealthScreenContent(
    state: HealthUiState,
    onBack: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    var showSheet by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_health_title), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        PAudioIcon(PAudioIcons.ArrowBack, contentDescription = stringResource(R.string.health_back))
                    }
                },
            )
        },
        // Pinned footer: outside the scrolling list, so the disclaimer is always visible.
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                DisclaimerCard(
                    text = stringResource(R.string.health_disclaimer),
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag(DISCLAIMER_TAG),
                )
            }
        },
    ) { padding ->
        when (state) {
            HealthUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                val label = stringResource(R.string.health_loading)
                CircularProgressIndicator(Modifier.semantics { contentDescription = label })
            }
            HealthUiState.Empty -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PAudioIcon(PAudioIcons.Hearing, contentDescription = null)
                Text(
                    stringResource(R.string.health_empty_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.health_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is HealthUiState.Content -> ContentList(
                content = state.content,
                padding = padding,
                listState = listState,
                onHowToRead = { showSheet = true },
            )
        }
    }
    if (showSheet) HowToReadSheet(onDismiss = { showSheet = false })
}

@Composable
private fun CardTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun ContentList(
    content: HealthContent,
    padding: PaddingValues,
    listState: LazyListState,
    onHowToRead: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(CONTENT_TAG),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TestInfo(content, locale) }
        item { AudiogramCard(content) }
        item { EarSummaryCard(R.string.health_summary_title_right, content.right, locale) }
        item { EarSummaryCard(R.string.health_summary_title_left, content.left, locale) }
        item { GradeCard(content.grade) }
        if (content.asymmetry) item { AsymmetryCard() }
        if (content.reliability.hasNote) item { ReliabilityCard(content.reliability) }
        item { TrendCard(content) }
        item {
            OutlinedButton(onClick = onHowToRead, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                PAudioIcon(PAudioIcons.Help, contentDescription = null)
                Text(stringResource(R.string.health_how_to_read), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun TestInfo(content: HealthContent, locale: Locale) {
    val date = remember(content.testStartedAt, locale) {
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(content.testStartedAt))
    }
    val mode = stringResource(if (content.mode == TestMode.QUICK) R.string.health_mode_quick else R.string.health_mode_thorough)
    Text(
        stringResource(R.string.health_test_info, date, mode),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AudiogramCard(content: HealthContent) {
    SectionCard {
        CardTitle(stringResource(R.string.health_audiogram_title))
        WhoBandLegend()
        Audiogram(rightEar = content.rightPoints, leftEar = content.leftPoints)
        Text(
            stringResource(R.string.health_audiogram_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (content.anyCensored) {
            Text(
                stringResource(R.string.health_censored_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EarSummaryCard(@StringRes title: Int, summary: EarSummary, locale: Locale) {
    val name = stringResource(title)
    val pta = formatDb(summary.pta3, locale)
    val ehf = formatDb(summary.ehf, locale)
    SectionCard {
        CardTitle(name)
        LabelledValue(stringResource(R.string.health_pta_label), stringResource(R.string.health_pta_value, pta))
        LabelledValue(stringResource(R.string.health_ehf_label), stringResource(R.string.health_ehf_value, ehf))
        Text(
            stringResource(R.string.health_ehf_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (summary.ehfBeyondRange) {
            Text(
                stringResource(R.string.health_ehf_beyond_range),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun GradeCard(grade: WhoGrade) {
    SectionCard {
        CardTitle(stringResource(R.string.health_grade_title))
        Text(stringResource(gradeLabelRes(grade)), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.health_grade_caveat),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AsymmetryCard() {
    SectionCard {
        CardTitle(stringResource(R.string.health_asymmetry_title))
        StatusChip(stringResource(R.string.health_asymmetry_chip), StatusTone.Warn, icon = PAudioIcons.Info)
        Text(stringResource(R.string.health_asymmetry_body), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReliabilityCard(reliability: Reliability) {
    SectionCard {
        CardTitle(stringResource(R.string.health_reliability_title))
        StatusChip(stringResource(R.string.health_reliability_chip), StatusTone.Warn, icon = PAudioIcons.Info)
        if (reliability.unreliable) {
            Text(stringResource(R.string.health_reliability_unreliable), style = MaterialTheme.typography.bodyMedium)
        }
        if (reliability.lowConfidencePoints > 0) {
            Text(
                pluralStringResource(
                    R.plurals.health_reliability_low_confidence,
                    reliability.lowConfidencePoints,
                    reliability.lowConfidencePoints,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TrendCard(content: HealthContent) {
    SectionCard {
        CardTitle(stringResource(R.string.health_trend_title))
        if (content.showTrend) {
            Text(
                pluralStringResource(R.plurals.health_trend_summary, content.trend.size, content.trend.size),
                style = MaterialTheme.typography.bodyMedium,
            )
            TrendChart(points = content.trend)
            Text(
                stringResource(R.string.health_trend_same_headset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(stringResource(R.string.health_trend_empty), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HowToReadSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            Modifier.navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.health_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
            sheetSection(R.string.health_sheet_axes_title, R.string.health_sheet_axes_body)
            sheetSection(R.string.health_sheet_markers_title, R.string.health_sheet_markers_body)
            sheetSection(R.string.health_sheet_bands_title, R.string.health_sheet_bands_body)
            sheetSection(R.string.health_sheet_limits_title, R.string.health_sheet_limits_body)
            sheetSection(R.string.health_sheet_trend_title, R.string.health_sheet_trend_body)
            item {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.health_sheet_close))
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sheetSection(@StringRes title: Int, @StringRes body: Int) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal fun formatDb(value: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 0 }.format(value)
