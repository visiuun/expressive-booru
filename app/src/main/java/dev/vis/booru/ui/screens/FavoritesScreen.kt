package dev.vis.booru.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import dev.vis.booru.Graph
import dev.vis.booru.data.AppSettings
import dev.vis.booru.data.Favorite
import dev.vis.booru.data.Post
import dev.vis.booru.ui.Routes
import dev.vis.booru.ui.components.EmptyView
import dev.vis.booru.ui.components.LocalBottomInset
import dev.vis.booru.ui.components.PostGrid
import dev.vis.booru.ui.components.SourceAvatar
import dev.vis.booru.ui.toast
import dev.vis.booru.ui.tokens

/** Local favorites across every site, filterable by site and by tags ("tag -other"). */
fun filterFavorites(list: List<Favorite>, sourceId: String, query: String): List<Post> {
    val want = tokens(query.lowercase())
    val include = want.filterNot { it.startsWith("-") }
    val exclude = want.filter { it.startsWith("-") }.map { it.drop(1) }
    return list.asSequence()
        .map { it.post }
        .filter { sourceId.isBlank() || it.sourceId == sourceId }
        .filter { p ->
            if (want.isEmpty()) return@filter true
            val tags = p.tags.map { it.lowercase() }.toSet()
            include.all { t -> t in tags || tags.any { it.contains(t) } } && exclude.none { it in tags }
        }
        .toList()
}

@Composable
fun FavoritesScreen(nav: NavHostController, settings: AppSettings) {
    val context = LocalContext.current
    val favorites by Graph.favorites.items.collectAsState()
    val sources by Graph.sources.items.collectAsState()
    var site by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    // Favorites ignore the rating/blacklist filter: you saved them on purpose.
    val shown = remember(favorites, site, query) { filterFavorites(favorites, site, query) }
    val sitesUsed = remember(favorites, sources) {
        val ids = favorites.map { it.post.sourceId }.toSet()
        sources.filter { it.id in ids }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Favorites", style = MaterialTheme.typography.headlineMediumEmphasized, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 12.dp))
        if (favorites.isNotEmpty()) {
            TextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Filter by tags") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                shape = MaterialTheme.shapes.extraLarge,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            if (sitesUsed.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { FilterChip(selected = site.isBlank(), onClick = { site = "" }, label = { Text("All · ${favorites.size}") }) }
                    items(sitesUsed, key = { it.id }) { s ->
                        FilterChip(
                            selected = site == s.id, onClick = { site = if (site == s.id) "" else s.id },
                            leadingIcon = { SourceAvatar(s, 20.dp) },
                            label = { Text(s.name) },
                        )
                    }
                }
            }
        }
        when {
            favorites.isEmpty() -> EmptyView("No favorites yet.\nTap the heart in the viewer, or long-press a thumbnail.", icon = Icons.Rounded.FavoriteBorder)
            shown.isEmpty() -> EmptyView("No favorites match.")
            else -> PostGrid(
                posts = shown,
                settings = settings,
                favorites = emptySet(),
                loadingMore = false,
                endReached = true,
                onLoadMore = {},
                onOpen = { p -> nav.navigate(Routes.viewer("${Routes.FAV_FEED}|$site|$query", p.key)) },
                onLongPress = { p -> Graph.favorites.toggle(p); toast(context, "Removed from favorites") },
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 10.dp + LocalBottomInset.current),
            )
        }
    }
}
