package dev.vis.booru

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import dev.vis.booru.data.AppSettings
import dev.vis.booru.ui.AppNavHost
import dev.vis.booru.ui.Routes
import dev.vis.booru.ui.theme.BooruTheme

class MainActivity : ComponentActivity() {
    private var pendingLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingLink = linkFrom(intent)
        setContent {
            val settings by Graph.settings.settings.collectAsState(initial = AppSettings())
            BooruTheme(settings) {
                val nav = rememberNavController()
                AppNavHost(nav, settings)
                val link = pendingLink
                LaunchedEffect(link) {
                    if (link != null) {
                        val route = Routes.fromWebUrl(link)
                        if (route != null) nav.navigate(route)
                        else Toast.makeText(this@MainActivity, "Not a link from a site in your list", Toast.LENGTH_SHORT).show()
                        pendingLink = null
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingLink = linkFrom(intent)
    }

    private fun linkFrom(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Regex("https?://\\S+").find(it)?.value }
        else -> null
    }
}
