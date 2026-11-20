package my.umarh.paudio.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import my.umarh.paudio.feature.health.HealthRoute
import my.umarh.paudio.feature.health.healthScreen
import my.umarh.paudio.feature.hearingtest.HearingTestRoute
import my.umarh.paudio.feature.hearingtest.hearingTestScreen
import my.umarh.paudio.feature.home.HomeRoute
import my.umarh.paudio.feature.home.homeScreen
import my.umarh.paudio.feature.onboarding.OnboardingRoute
import my.umarh.paudio.feature.onboarding.onboardingScreen
import my.umarh.paudio.feature.preference.PreferenceRoute
import my.umarh.paudio.feature.preference.preferenceScreen
import my.umarh.paudio.feature.profiles.ProfilesRoute
import my.umarh.paudio.feature.profiles.profilesScreen
import my.umarh.paudio.feature.results.ResultsRoute
import my.umarh.paudio.feature.results.resultsScreen
import my.umarh.paudio.feature.settings.SettingsRoute
import my.umarh.paudio.feature.settings.settingsScreen

/** Single navigation host; each feature contributes its destinations via `NavGraphBuilder.xxxScreen`. */
@Composable
fun PAudioNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = OnboardingRoute) {
        onboardingScreen(
            onFinished = {
                nav.navigate(HomeRoute) { popUpTo(OnboardingRoute) { inclusive = true } }
            },
        )
        homeScreen(
            onHearingTest = { nav.navigate(HearingTestRoute) },
            onPreferenceTest = { nav.navigate(PreferenceRoute) },
            onHealth = { nav.navigate(HealthRoute()) },
            onProfiles = { nav.navigate(ProfilesRoute) },
            onSettings = { nav.navigate(SettingsRoute) },
        )
        hearingTestScreen(
            onShowResults = { nav.navigate(ResultsRoute) { popUpTo(HearingTestRoute) { inclusive = true } } },
            onBack = { nav.popBackStack() },
        )
        resultsScreen(onBack = { nav.popBackStack() })
        healthScreen(onBack = { nav.popBackStack() })
        preferenceScreen(onBack = { nav.popBackStack() })
        profilesScreen(onBack = { nav.popBackStack() })
        settingsScreen(onBack = { nav.popBackStack() })
    }
}
