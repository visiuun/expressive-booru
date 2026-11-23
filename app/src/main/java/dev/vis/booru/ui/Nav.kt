package dev.vis.booru.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import dev.vis.booru.ui.components.LocalBottomInset
import dev.vis.booru.ui.components.PillNav
import dev.vis.booru.ui.components.PillNavHeight
import dev.vis.booru.ui.components.PillNavItem
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import dev.vis.booru.Graph
import dev.vis.booru.data.AppSettings
import dev.vis.booru.data.FeedRegistry
import dev.vis.booru.ui.screens.BrowseScreen
import dev.vis.booru.ui.screens.FavoritesScreen
import dev.vis.booru.ui.screens.SearchScreen
import dev.vis.booru.ui.screens.SearchesScreen
import dev.vis.booru.ui.screens.SettingsScreen
import dev.vis.booru.ui.screens.SourceEditScreen
import dev.vis.booru.ui.screens.SourcesScreen
import dev.vis.booru.ui.screens.ViewerScreen
import java.net.URLDecoder
import java.net.URLEncoder

object Routes {
    const val BROWSE = "browse"
    const val SEARCHES = "searches"
    const val FAVORITES = "favorites"
    const val SETTINGS = "settings"
    const val SOURCES = "sources"

    const val SEARCH_PATTERN = "search?source={source}&q={q}"
    const val FAV_FEED = "@fav"

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    fun dec(s: String?): String = s?.let { URLDecoder.decode(it, "UTF-8") } ?: ""

    fun search(sourceId: String, q: String = "") = "search?source=${enc(sourceId)}&q=${enc(q)}"
    fun results(sourceId: String, q: String) = "results?source=${enc(sourceId)}&q=${enc(q)}"
    fun viewer(feedKey: String, postKey: String) = "viewer?feed=${enc(feedKey)}&post=${enc(postKey)}"
    fun sourceEdit(id: String = "") = "source_edit?id=${enc(id)}"

    /** Map a booru web URL (deep link or shared text) to a route, if it belongs to a known site. */
    fun fromWebUrl(url: String): String? {
        val u = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return null
        val host = u.host?.removePrefix("www.")?.removePrefix("api.") ?: return null
        val source = Graph.sources.items.value.firstOrNull { s ->
            val h = s.host.removePrefix("api.")
            h == host || runCatching { Uri.parse(s.apiUrl).host?.removePrefix("www.")?.removePrefix("api.") }.getOrNull() == host
        } ?: return null
        val segs = u.pathSegments
        val id = when {
            u.getQueryParameter("page") == "post" && u.getQueryParameter("s") == "view" -> u.getQueryParameter("id")
            segs.size >= 2 && segs[0] == "posts" -> segs[1]
            segs.size >= 3 && segs[0] == "post" && segs[1] == "show" -> segs[2]
            segs.size == 2 && segs[0] == "post" && segs[1].endsWith(".html") -> segs[1].removeSuffix(".html") // nozomi.la
            else -> null
        }?.toLongOrNull()
        if (id != null) {
            val key = FeedRegistry.key(source.id, "id:$id")
            return viewer(key, "${source.id}:$id")
        }
        val tags = u.getQueryParameter("tags")
        return if (tags != null) results(source.id, tags) else null
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.BROWSE, "Browse", Icons.Rounded.GridView),
    Tab(Routes.SEARCHES, "Searches", Icons.Rounded.Bookmarks),
    Tab(Routes.FAVORITES, "Favorites", Icons.Rounded.Favorite),
    Tab(Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
)

@Composable
fun AppNavHost(nav: NavHostController, settings: AppSettings) {
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = tabs.any { it.route == currentRoute }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = if (showBar) PillNavHeight + 28.dp + navBottom else navBottom
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val scale = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalBottomInset provides bottomInset) {
                NavHost(
                    navController = nav,
                    startDestination = Routes.BROWSE,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = { slideInHorizontally(spatial) { it / 3 } + fadeIn(effects) },
                    exitTransition = { scaleOut(scale, targetScale = 0.94f) + fadeOut(effects) },
                    popEnterTransition = { scaleIn(scale, initialScale = 0.94f) + fadeIn(effects) },
                    popExitTransition = { slideOutHorizontally(spatial) { it / 3 } + fadeOut(effects) },
                ) {
            composable(Routes.BROWSE) { BrowseScreen(nav, settings, settings.activeSourceId, "", root = true) }
            composable(Routes.SEARCHES) { SearchesScreen(nav, settings) }
            composable(Routes.FAVORITES) { FavoritesScreen(nav, settings) }
            composable(Routes.SETTINGS) { SettingsScreen(nav, settings) }
            composable(Routes.SOURCES) { SourcesScreen(nav, settings) }
            composable(
                "source_edit?id={id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" }),
            ) { e -> SourceEditScreen(nav, Routes.dec(e.arguments?.getString("id"))) }
            composable(
                Routes.SEARCH_PATTERN,
                arguments = listOf(
                    navArgument("source") { type = NavType.StringType; defaultValue = "" },
                    navArgument("q") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { e -> SearchScreen(nav, Routes.dec(e.arguments?.getString("source")), Routes.dec(e.arguments?.getString("q"))) }
            composable(
                "results?source={source}&q={q}",
                arguments = listOf(
                    navArgument("source") { type = NavType.StringType; defaultValue = "" },
                    navArgument("q") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { e ->
                BrowseScreen(nav, settings, Routes.dec(e.arguments?.getString("source")), Routes.dec(e.arguments?.getString("q")), root = false)
            }
            composable(
                "viewer?feed={feed}&post={post}",
                arguments = listOf(
                    navArgument("feed") { type = NavType.StringType; defaultValue = "" },
                    navArgument("post") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { e ->
                ViewerScreen(nav, settings, Routes.dec(e.arguments?.getString("feed")), Routes.dec(e.arguments?.getString("post")))
            }
                }
            }
            AnimatedVisibility(
                visible = showBar,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
                enter = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it * 2 } + fadeIn(),
                exit = slideOutVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it * 2 } + fadeOut(),
            ) {
                PillNav(
                    tabs.map { t ->
                        PillNavItem(t.label, t.icon, currentRoute == t.route) {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
            }
        }
    }
}
