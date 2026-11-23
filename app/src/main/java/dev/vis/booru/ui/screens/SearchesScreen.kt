package dev.vis.booru.ui.screens

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import dev.vis.booru.Graph
import dev.vis.booru.data.AppSettings
import dev.vis.booru.data.SavedSearch
import dev.vis.booru.data.Source
import dev.vis.booru.data.relativeTime
import dev.vis.booru.ui.Routes
import dev.vis.booru.ui.components.EmptyView
import dev.vis.booru.ui.components.SettingsAction
import dev.vis.booru.ui.components.SettingsGroup
import dev.vis.booru.ui.components.SourceAvatar
import dev.vis.booru.ui.components.SubScreen
import dev.vis.booru.ui.toast
import androidx.compose.ui.platform.LocalContext

@Composable
fun SearchesScreen(nav: NavHostController, settings: AppSettings) {
    val context = LocalContext.current
    val saved by Graph.searches.saved.items.collectAsState()
    val history by Graph.searches.history.items.collectAsState()
    val sources by Graph.sources.items.collectAsState()
    val recent = if (settings.saveHistory) history.take(40) else emptyList()

    if (saved.isEmpty() && recent.isEmpty()) {
        EmptyView(
            "Saved and recent searches show up here.\nTap the bookmark on a results page to save one.",
            icon = Icons.Rounded.Bookmarks, modifier = Modifier.statusBarsPadding(),
        )
        return
    }

    @Composable
    fun Row(s: SavedSearch, i: Int, n: Int, onRemove: () -> Unit, onLong: (() -> Unit)?) {
        val src: Source? = sources.firstOrNull { it.id == s.sourceId } ?: Graph.sources.resolve(s.sourceId)
        SettingsAction(
            title = s.query,
            subtitle = listOf(src?.name ?: s.sourceId, relativeTime(s.time / 1000)).joinToString(" · "),
            icon = null, shape = MaterialShapes.Circle, index = i, count = n,
            lead = { SourceAvatar(src, 40.dp) },
            trailing = { IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Remove") } },
            onLongClick = onLong,
            onClick = { nav.navigate(Routes.results(s.sourceId, s.query)) },
        )
    }

    SubScreen("Searches", onBack = null) { m ->
        if (saved.isNotEmpty()) {
            item {
                SettingsGroup("Saved", m) {
                    saved.forEachIndexed { i, s -> Row(s, i, saved.size, onRemove = { Graph.searches.toggleSaved(s.sourceId, s.query) }, onLong = null) }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item {
                SettingsGroup("Recent · long-press to save", m) {
                    recent.forEachIndexed { i, s ->
                        Row(s, i, recent.size, onRemove = { Graph.searches.history.update { l -> l - s } }, onLong = {
                            toast(context, if (Graph.searches.toggleSaved(s.sourceId, s.query)) "Search saved" else "Removed from saved")
                        })
                    }
                    TextButton(onClick = { Graph.searches.history.update { emptyList() } }) { Text("Clear history") }
                }
            }
        }
    }
}
