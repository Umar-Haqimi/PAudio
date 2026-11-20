package my.umarh.paudio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import my.umarh.paudio.designsystem.PAudioTheme
import my.umarh.paudio.navigation.PAudioNavHost
import my.umarh.paudio.service.LaunchRequests
import my.umarh.paudio.service.ServiceIntents
import my.umarh.paudio.service.engine.EngineController
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var engineController: EngineController

    @Inject lateinit var launchRequests: LaunchRequests

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleLaunch(intent)
        setContent {
            // TODO(M8): pass the active profile's seed colour.
            PAudioTheme {
                PAudioNavHost()
            }
        }
        // Foreground: starting the FGS is allowed here (boot fallback and normal app starts).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                engineController.refreshNotificationPermission()
                engineController.startIfAllowed()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    /** Notification deep links: "Tap to start PAudio" starts the engine; other routes are left for the nav (M8b). */
    private fun handleLaunch(intent: Intent?) {
        when (launchRequests.offer(intent)) {
            ServiceIntents.ROUTE_START_SERVICE -> {
                engineController.start()
                launchRequests.consume()
            }
            else -> Unit
        }
    }
}
