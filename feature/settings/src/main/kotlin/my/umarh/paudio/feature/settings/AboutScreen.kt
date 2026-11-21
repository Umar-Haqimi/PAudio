package my.umarh.paudio.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import my.umarh.paudio.designsystem.PAudioIcon
import my.umarh.paudio.designsystem.PAudioIcons
import my.umarh.paudio.designsystem.component.DisclaimerCard
import my.umarh.paudio.designsystem.component.SectionCard

/** About (F24): version, disclaimer, attributions, open-source licenses. */
fun NavGraphBuilder.aboutScreen(onBack: () -> Unit) {
    composable<AboutRoute> {
        val context = LocalContext.current
        val version = remember { appVersionName(context) }
        AboutContent(versionName = version, onBack = onBack)
    }
}

internal const val TAG_ATTR_NIN = "about_attr_nin"
internal const val TAG_ATTR_FONT = "about_attr_font"
internal const val TAG_ATTR_ICONS = "about_attr_icons"

private fun appVersionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
} catch (_: Exception) {
    ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutContent(versionName: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        PAudioIcon(PAudioIcons.ArrowBack, stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.titleLarge)
            if (versionName.isNotBlank()) {
                Text(
                    stringResource(R.string.about_version, versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.about_disclaimer_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            DisclaimerCard(stringResource(R.string.about_disclaimer_text))

            Text(
                stringResource(R.string.about_attributions_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            SectionCard(title = stringResource(R.string.about_attr_nin_title), modifier = Modifier.testTag(TAG_ATTR_NIN)) {
                Text(stringResource(R.string.about_attr_nin_body))
                Text(stringResource(R.string.about_attr_nin_note), style = MaterialTheme.typography.bodyMedium)
            }
            SectionCard(title = stringResource(R.string.about_attr_font_title), modifier = Modifier.testTag(TAG_ATTR_FONT)) {
                Text(stringResource(R.string.about_attr_font_body))
            }
            SectionCard(title = stringResource(R.string.about_attr_icons_title), modifier = Modifier.testTag(TAG_ATTR_ICONS)) {
                Text(stringResource(R.string.about_attr_icons_body))
            }

            Text(
                stringResource(R.string.about_licenses_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.about_licenses_intro), style = MaterialTheme.typography.bodyMedium)
            OpenSourceLicenses.forEach { lib ->
                SectionCard {
                    Text(lib.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            when (lib.kind) {
                                LicenseKind.APACHE_2 -> R.string.about_license_apache
                                LicenseKind.MIT -> R.string.about_license_mit
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
