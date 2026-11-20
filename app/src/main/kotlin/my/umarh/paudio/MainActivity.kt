package my.umarh.paudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import my.umarh.paudio.designsystem.PAudioTheme
import my.umarh.paudio.navigation.PAudioNavHost

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // TODO(M8): pass the active profile's seed colour.
            PAudioTheme {
                PAudioNavHost()
            }
        }
    }
}
