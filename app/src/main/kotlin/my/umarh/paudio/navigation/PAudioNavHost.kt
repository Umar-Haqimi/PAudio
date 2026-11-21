package my.umarh.paudio.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import my.umarh.paudio.feature.profiles.ProfileDetailRoute
import my.umarh.paudio.feature.profiles.ProfilesRoute
import my.umarh.paudio.feature.profiles.profilesScreen
import my.umarh.paudio.feature.results.ResultsRoute
import my.umarh.paudio.feature.results.resultsScreen
import my.umarh.paudio.feature.settings.AboutRoute
import my.umarh.paudio.feature.settings.SettingsRoute
import my.umarh.paudio.feature.settings.aboutScreen
import my.umarh.paudio.feature.settings.settingsScreen
import my.umarh.paudio.service.ServiceIntents

/**
 * Single navigation host; each feature contributes its destinations via `NavGraphBuilder.xxxScreen`.
 *
 * @param startDestination [HomeRoute] once onboarding is done, else [OnboardingRoute] (decided by `AppViewModel`).
 * @param launchRoute a pending `ServiceIntents.ROUTE_*` from a notification; [onLaunchRouteHandled] consumes it.
 */
@Composable
fun PAudioNavHost(startDestination: Any, launchRoute: String?, onLaunchRouteHandled: () -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(launchRoute) {
        when (launchRoute) {
            ServiceIntents.ROUTE_CREATE_PROFILE -> {
                // Only meaningful after onboarding (a listener must exist).
                if (startDestination == HomeRoute) nav.navigate(ProfilesRoute(createForCurrent = true))
                onLaunchRouteHandled()
            }
            else -> Unit
        }
    }
    NavHost(navController = nav, startDestination = startDestination) {
        onboardingScreen(
            onFinished = {
                nav.navigate(HomeRoute) { popUpTo(OnboardingRoute) { inclusive = true } }
            },
        )
        homeScreen(
            onHearingTest = { nav.navigate(HearingTestRoute()) },
            // null = the active listener's profile for the current headset (resolved by :feature:preference).
            onPreferenceTest = { nav.navigate(PreferenceRoute()) },
            onHealth = { profileId -> nav.navigate(HealthRoute(profileId = profileId)) },
            onProfiles = { nav.navigate(ProfilesRoute()) },
            onSettings = { nav.navigate(SettingsRoute) },
            onCreateProfile = { nav.navigate(ProfilesRoute(createForCurrent = true)) },
        )
        hearingTestScreen(
            onShowResults = { id -> nav.navigate(ResultsRoute(testId = id)) { popUpTo<HearingTestRoute> { inclusive = true } } },
            onBack = { nav.popBackStack() },
        )
        resultsScreen(
            onSeeHealth = { profileId, testId -> nav.navigate(HealthRoute(profileId = profileId, testId = testId)) },
            onPreferenceTest = { profileId -> nav.navigate(PreferenceRoute(profileId)) },
            onDone = { nav.popBackStack() },
            // Leaves Results (no exit dialog) and starts a fresh test; the new Results replaces this one.
            onRedoTest = { profileId -> nav.navigate(HearingTestRoute(profileId = profileId)) { popUpTo<ResultsRoute> { inclusive = true } } },
        )
        healthScreen(onBack = { nav.popBackStack() })
        preferenceScreen(onBack = { nav.popBackStack() })
        profilesScreen(
            onBack = { nav.popBackStack() },
            onOpenProfile = { nav.navigate(ProfileDetailRoute(it)) },
            onRetest = { nav.navigate(HearingTestRoute(profileId = it)) },
            onHealth = { nav.navigate(HealthRoute(profileId = it)) },
            onPreferenceTest = { nav.navigate(PreferenceRoute(it)) },
        )
        settingsScreen(onBack = { nav.popBackStack() }, onAbout = { nav.navigate(AboutRoute) })
        aboutScreen(onBack = { nav.popBackStack() })
    }
}
