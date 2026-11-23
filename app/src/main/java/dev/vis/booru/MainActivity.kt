package dev.vis.booru

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import dev.vis.booru.data.AppSettings
import dev.vis.booru.data.Downloader
import dev.vis.booru.data.UpdateManager
import dev.vis.booru.ui.AppNavHost
import dev.vis.booru.ui.Routes
import dev.vis.booru.ui.UpdateDialog
import dev.vis.booru.ui.theme.BooruTheme
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    private var pendingLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingLink = linkFrom(intent)
        val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
        Downloader.askPermissions = {
            val needed = buildList {
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                if (Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
        }
        setContent {
            val settings by Graph.settings.settings.collectAsState(initial = AppSettings())
            BooruTheme(settings) {
                val nav = rememberNavController()
                AppNavHost(nav, settings)
                val update by UpdateManager.state.collectAsState()
                UpdateDialog(update, onUpdate = { UpdateManager.install(this@MainActivity) }, onLater = UpdateManager::dismiss)
                LaunchedEffect(Unit) {
                    val s = Graph.settings.settings.first()
                    UpdateManager.checkIfDue(this@MainActivity, s.autoUpdate)
                }
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
