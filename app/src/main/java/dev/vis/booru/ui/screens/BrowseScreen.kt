package dev.vis.booru.ui.screens

import androidx.compose.material3.HorizontalDivider
import dev.vis.booru.data.Everything
import androidx.compose.foundation.clickable
import dev.vis.booru.data.BulkDownloader
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import dev.vis.booru.Graph
import dev.vis.booru.data.ApiException
import dev.vis.booru.data.AppSettings
import dev.vis.booru.data.FeedRegistry
import dev.vis.booru.data.Source
import dev.vis.booru.data.friendlyError
import dev.vis.booru.ui.Routes
import dev.vis.booru.ui.components.EmptyView
import dev.vis.booru.ui.components.LocalBottomInset
import dev.vis.booru.ui.components.ErrorView
import dev.vis.booru.ui.components.LoadingView
import dev.vis.booru.ui.components.NeedsKeyView
import dev.vis.booru.ui.components.PostGrid
import dev.vis.booru.ui.components.SourceAvatar
import dev.vis.booru.ui.toast
import dev.vis.booru.ui.tokens
import kotlinx.coroutines.launch

/**
 * The Browse tab (latest posts from the active site) and every search result page.
 * [root] is the tab; otherwise it's a pushed results page with a back arrow.
 */
@Composable
fun BrowseScreen(nav: NavHostController, settings: AppSettings, requestedSourceId: String, query: String, root: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by Graph.sources.items.collectAsState()
    val source: Source? = Graph.sources.resolve(requestedSourceId).takeIf { it?.id == Everything.ID || sources.any { s -> s.id == it?.id } }
        ?: sources.firstOrNull()
    if (source == null) {
        ErrorView(
            title = "No sites",
            message = "You removed every site. Add one or restore the built-in list.",
            extra = { TextButton(onClick = { nav.navigate(Routes.SOURCES) }) { Text("Manage sites") } },
            modifier = Modifier.statusBarsPadding(),
        )
        return
    }
    val feed = remember(source, query) { FeedRegistry.get(source.id, query) }
    LaunchedEffect(feed) {
        if (feed.posts.isEmpty() && !feed.loading && feed.error == null && !feed.endReached) feed.loadMore()
    }
    LaunchedEffect(feed) {
        if (!root && settings.saveHistory) Graph.searches.record(source.id, query)
    }
    val favKeys by Graph.favorites.keys.collectAsState()
    val savedList by Graph.searches.saved.items.collectAsState()
    val saved = savedList.any { it.sourceId == source.id && it.query == query.trim() }
    val visible = remember(feed.posts, settings) { feed.posts.filter(settings::allows) }
    var picker by remember { mutableStateOf(false) }
    var confirmBulk by remember { mutableStateOf(false) }
    val bulk by BulkDownloader.state.collectAsState()
    val grid = rememberLazyStaggeredGridState()

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = if (root) 12.dp else 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!root) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Surface(
                    onClick = { nav.navigate(Routes.search(source.id, query)) },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SourceAvatar(source, 34.dp, Modifier.clickable { picker = true })
                        Spacer(Modifier.width(10.dp))
                        Text(
                            query.ifBlank { "Search ${source.name}" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (query.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                SortMenu(source, query) { q -> nav.navigate(Routes.results(source.id, q)) }
                if (!root && query.isNotBlank()) {
                    if (source.id != Everything.ID) IconButton(onClick = { confirmBulk = true }, enabled = bulk?.done != false) {
                        Icon(Icons.Rounded.DownloadForOffline, "Download all")
                    }
                    IconButton(onClick = {
                        val now = Graph.searches.toggleSaved(source.id, query)
                        toast(context, if (now) "Search saved" else "Removed from saved")
                    }) { Icon(if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Save search") }
                } else {
                    IconButton(onClick = { picker = true }) { Icon(Icons.Rounded.SwapHoriz, "Switch site") }
                }
            }

            val err = feed.error
            PullToRefreshBox(isRefreshing = feed.refreshing, onRefresh = { feed.refresh() }, modifier = Modifier.weight(1f)) {
                when {
                    visible.isEmpty() && err is ApiException && err.needsAuth -> NeedsKeyView(
                        source, friendlyError(err),
                        onAddKey = { nav.navigate(Routes.sourceEdit(source.id)) },
                        onSwitch = { picker = true },
                    )
                    visible.isEmpty() && err != null -> ErrorView(friendlyError(err), onRetry = { feed.retry() })
                    visible.isEmpty() && (feed.loading || !feed.endReached) -> LoadingView()
                    visible.isEmpty() -> EmptyView(
                        if (feed.posts.isNotEmpty()) "Everything here is hidden by your rating filter or blacklist."
                        else if (query.isBlank()) "Nothing here yet." else "No posts match “$query”.",
                        icon = if (feed.posts.isNotEmpty()) Icons.Rounded.Tune else Icons.Rounded.SearchOff,
                    )
                    else -> PostGrid(
                        posts = visible,
                        settings = settings,
                        favorites = favKeys,
                        loadingMore = feed.loading,
                        endReached = feed.endReached,
                        blocked = err != null,
                        onLoadMore = { feed.loadMore() },
                        onOpen = { p -> nav.navigate(Routes.viewer(feed.key, p.key)) },
                        onLongPress = { p -> toast(context, if (Graph.favorites.toggle(p)) "Added to favorites" else "Removed from favorites") },
                        state = grid,
                        contentPadding = PaddingValues(
                            start = 10.dp, end = 10.dp, top = 6.dp,
                            bottom = 10.dp + LocalBottomInset.current,
                        ),
                        footer = if (err != null) ({
                            ErrorFooter(friendlyError(err)) { feed.retry() }
                        }) else null,
                    )
                }
            }
        }

        bulk?.let { b ->
            BulkProgressPill(
                b, Graph.sources.resolve(b.sourceId)?.name ?: b.sourceId,
                Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 16.dp + LocalBottomInset.current),
            )
        }

        val showTop by remember { derivedStateOf { grid.firstVisibleItemIndex > 30 } }
        if (showTop) {
            SmallFloatingActionButton(
                onClick = { scope.launch { grid.scrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp + LocalBottomInset.current),
            ) { Icon(Icons.Rounded.KeyboardArrowUp, "Back to top") }
        }
    }

    if (confirmBulk) {
        val hint = BulkDownloader.artistHint(query)
        AlertDialog(
            onDismissRequest = { confirmBulk = false },
            icon = { Icon(Icons.Rounded.DownloadForOffline, null) },
            title = { Text("Download all of “$query”?") },
            text = {
                Text(
                    "Goes through every page on ${source.name} and saves the originals to Downloads/Expressive Booru, up to ${BulkDownloader.MAX_POSTS} posts. " +
                        "Posts hidden by your rating filter or blacklist are skipped, and so is anything you've already downloaded with this app." +
                        (hint?.let { "\n\nWhen ${source.name} doesn't say who the artist is, files are named [$it]." } ?: ""),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmBulk = false; BulkDownloader.start(context, source, query) }) { Text("Download all") }
            },
            dismissButton = { TextButton(onClick = { confirmBulk = false }) { Text("Cancel") } },
        )
    }

    if (picker) {
        SourcePickerSheet(
            sources = sources,
            currentId = source.id,
            onDismiss = { picker = false },
            onPick = { s ->
                picker = false
                if (root) scope.launch { Graph.settings.setActive(s.id) }
                else nav.navigate(Routes.results(s.id, query)) { popUpTo(nav.currentBackStackEntry?.destination?.id ?: 0) { inclusive = true } }
            },
            onManage = { picker = false; nav.navigate(Routes.SOURCES) },
        )
    }
}

@Composable
private fun ErrorFooter(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

/** Newest / top / random: swaps the sort meta-tag in the current query. */
@Composable
private fun SortMenu(source: Source, query: String, onQuery: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val base = tokens(query).filterNot { it.startsWith("sort:") || it.startsWith("order:") }
    IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        val current = tokens(query).firstOrNull { it.startsWith("sort:") || it.startsWith("order:") }
        listOf(
            "Newest" to null,
            "Top score" to source.type.scoreSort,
            "Random" to source.type.randomSort,
        ).forEach { (label, token) ->
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { if (current == token || (token != null && current?.startsWith(token) == true)) Icon(Icons.Rounded.Check, null) },
                onClick = { open = false; onQuery((base + listOfNotNull(token)).joinToString(" ")) },
            )
        }
    }
}

@Composable
fun SourcePickerSheet(
    sources: List<Source>,
    currentId: String,
    onDismiss: () -> Unit,
    onPick: (Source) -> Unit,
    onManage: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Sites", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 8.dp)) {
            item(key = Everything.ID) {
                val usable = Everything.sites()
                ListItem(
                    leadingContent = { SourceAvatar(Everything.source, 40.dp) },
                    headlineContent = { Text("Everything") },
                    supportingContent = {
                        Text("All ${usable.size} sites at once: " + usable.joinToString(", ") { it.name }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = { if (currentId == Everything.ID) Icon(Icons.Rounded.Check, "Current", tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable { onPick(Everything.source) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
            items(sources, key = { it.id }) { s ->
                ListItem(
                    leadingContent = { SourceAvatar(s, 40.dp) },
                    headlineContent = { Text(s.name) },
                    supportingContent = {
                        Text(
                            s.host + " · " + s.type.label + if (s.needsAuth && !s.hasAuth) " · needs API key" else "",
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = { if (s.id == currentId) Icon(Icons.Rounded.Check, "Current", tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable { onPick(s) },
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onManage) { Text("Manage sites") }
                }
                Spacer(Modifier.size(16.dp))
            }
        }
    }
}

/** Floating progress for "Download all", in the same pill style as the nav. */
@Composable
private fun BulkProgressPill(b: BulkDownloader.Progress, siteName: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 8.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (b.done) Icon(Icons.Rounded.DownloadDone, null, Modifier.size(24.dp))
            else androidx.compose.material3.LoadingIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    when {
                        !b.done -> "Downloading all · ${b.queued} queued"
                        b.error != null && b.error != "Stopped" -> "Stopped · ${b.queued} queued"
                        else -> "Queued ${b.queued} download${if (b.queued == 1) "" else "s"}"
                    },
                    style = MaterialTheme.typography.labelLargeEmphasized, maxLines = 1,
                )
                Text(
                    listOfNotNull(
                        "${b.query} on $siteName",
                        b.skipped.takeIf { it > 0 }?.let { "$it skipped" },
                        b.error?.takeIf { it != "Stopped" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(4.dp))
            if (b.done) IconButton(onClick = { BulkDownloader.dismiss() }) { Icon(Icons.Rounded.Close, "Dismiss") }
            else TextButton(onClick = { BulkDownloader.cancel() }) { Text("Stop") }
        }
    }
}
