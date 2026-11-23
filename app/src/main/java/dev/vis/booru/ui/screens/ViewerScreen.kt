package dev.vis.booru.ui.screens

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.vis.booru.Graph
import dev.vis.booru.data.AppSettings
import dev.vis.booru.data.Downloader
import dev.vis.booru.data.FeedRegistry
import dev.vis.booru.data.Post
import dev.vis.booru.data.TagCategory
import dev.vis.booru.data.formatDate
import dev.vis.booru.data.humanSize
import dev.vis.booru.ui.Routes
import dev.vis.booru.ui.components.ErrorView
import dev.vis.booru.ui.components.LoadingView
import dev.vis.booru.ui.components.RatingBadge
import dev.vis.booru.ui.copyText
import dev.vis.booru.ui.openUrl
import dev.vis.booru.ui.shareText
import dev.vis.booru.ui.theme.isDarkScheme
import dev.vis.booru.ui.theme.tagColor
import dev.vis.booru.ui.toast
import dev.vis.booru.ui.withToken
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/**
 * Full-screen swipeable viewer over a feed (or the favorites list). Swiping near the end of a
 * feed loads the next page, so you can keep going without returning to the grid.
 */
@Composable
fun ViewerScreen(nav: NavHostController, settings: AppSettings, feedKey: String, postKey: String) {
    val context = LocalContext.current
    val isFav = feedKey.startsWith(Routes.FAV_FEED)
    val feed = remember(feedKey) { if (isFav) null else FeedRegistry.get(feedKey) }
    val favorites by Graph.favorites.items.collectAsState()
    val favKeys by Graph.favorites.keys.collectAsState()

    // Favorites are a snapshot so un-hearting a post doesn't yank it out from under the pager.
    val favSnapshot = remember(feedKey) {
        if (!isFav) emptyList() else {
            val parts = feedKey.split('|', limit = 3)
            filterFavorites(favorites, parts.getOrNull(1).orEmpty(), parts.getOrNull(2).orEmpty())
        }
    }
    val list: List<Post> = if (feed != null) remember(feed.posts, settings) { feed.posts.filter(settings::allows) } else favSnapshot

    LaunchedEffect(feed) {
        if (feed != null && feed.posts.isEmpty() && !feed.loading && !feed.endReached) feed.loadMore()
    }

    var chrome by remember { mutableStateOf(true) }
    ImmersiveMode(hidden = !chrome)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (list.isEmpty()) {
            val err = feed?.error
            when {
                err != null -> ErrorView(dev.vis.booru.data.friendlyError(err), onRetry = { feed.retry() })
                feed != null && (feed.loading || !feed.endReached) -> LoadingView()
                else -> ErrorView("This post isn't available any more.", title = "Not found")
            }
            IconButton(onClick = { nav.popBackStack() }, modifier = Modifier.padding(top = 32.dp, start = 4.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
            }
            return@Box
        }

        val startIndex = remember { list.indexOfFirst { it.key == postKey }.coerceAtLeast(0) }
        val pager = rememberPagerState(initialPage = startIndex) { list.size }
        var info by remember { mutableStateOf(false) }

        LaunchedEffect(pager.currentPage, list.size) {
            if (feed != null && pager.currentPage >= list.size - 4) feed.loadMore()
        }

        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { list[it].key }) { page ->
            val post = list[page]
            if (post.isVideo) {
                VideoPage(post, active = pager.settledPage == page, settings = settings, onControllerVisible = { chrome = it })
            } else {
                ImagePage(post, settings, onTap = { chrome = !chrome }, onLongPress = { info = true })
            }
        }

        val current = list.getOrNull(pager.currentPage) ?: list.first()
        val source = Graph.sources.get(current.sourceId)
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            var menu by remember { mutableStateOf(false) }
            TopAppBar(
                title = {
                    Column {
                        Text("#${current.id}", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            listOfNotNull(source?.name, "${pager.currentPage + 1} / ${list.size}${if (feed != null && !feed.endReached) "+" else ""}").joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 1,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    val fav = current.key in favKeys
                    IconButton(onClick = { Graph.favorites.toggle(current) }) {
                        Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorite", tint = if (fav) Color(0xFFFF5C8A) else Color.White)
                    }
                    IconButton(onClick = { Downloader.download(context, current) }) { Icon(Icons.Rounded.Download, "Download") }
                    IconButton(onClick = { info = true }) { Icon(Icons.Rounded.Info, "Details") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val page = source?.postPageUrl(current.id)
                        DropdownMenuItem(text = { Text("Share link") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = {
                            menu = false; shareText(context, page ?: current.fileUrl.orEmpty())
                        })
                        DropdownMenuItem(text = { Text("Open in browser") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) }, onClick = {
                            menu = false; page?.let { openUrl(context, it) }
                        })
                        DropdownMenuItem(text = { Text("Copy file link") }, leadingIcon = { Icon(Icons.Rounded.Link, null) }, onClick = {
                            menu = false; copyText(context, current.fileUrl ?: current.sampleUrl.orEmpty())
                        })
                        DropdownMenuItem(text = { Text("Copy tags") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = {
                            menu = false; copyText(context, current.tags.joinToString(" "))
                        })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    titleContentColor = Color.White, navigationIconContentColor = Color.White, actionIconContentColor = Color.White,
                ),
            )
        }

        if (info) {
            InfoSheet(
                post = current,
                searchQuery = feed?.query,
                onDismiss = { info = false },
                onSearch = { q ->
                    info = false
                    nav.navigate(Routes.results(current.sourceId, q))
                },
            )
        }
    }
}

@Composable
private fun ImagePage(post: Post, settings: AppSettings, onTap: () -> Unit, onLongPress: () -> Unit) {
    val context = LocalContext.current
    val url = post.viewerUrl(settings.originalInViewer)
    // The grid cached the thumbnail under its URL; show it instantly while the full image loads.
    val thumb = if (settings.hdThumbs) post.sampleUrl ?: post.previewUrl else post.previewUrl ?: post.sampleUrl
    val request = remember(url) { ImageRequest.Builder(context).data(url).placeholderMemoryCacheKey(thumb).build() }
    val zoom = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 8f)))
    Box(Modifier.fillMaxSize()) {
        ZoomableAsyncImage(
            model = request,
            contentDescription = post.tags.take(8).joinToString(" "),
            state = zoom,
            contentScale = ContentScale.Fit,
            onClick = { onTap() },
            onLongClick = { onLongPress() },
            modifier = Modifier.fillMaxSize(),
        )
        if (!zoom.isImageDisplayed) {
            LoadingIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Color.White)
        }
    }
}

@Composable
private fun VideoPage(post: Post, active: Boolean, settings: AppSettings, onControllerVisible: (Boolean) -> Unit) {
    val url = post.playableUrl()
    if (!active || url == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AsyncImage(post.sampleUrl ?: post.previewUrl, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            if (url == null) Text("No playable file", color = Color.White)
        }
        return
    }
    val context = LocalContext.current
    val player = remember(url) {
        val ds = OkHttpDataSource.Factory(Graph.api.client).setUserAgent(Graph.api.userAgent)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .build().apply {
                setMediaItem(MediaItem.fromUri(url))
                repeatMode = Player.REPEAT_MODE_ONE
                volume = if (settings.muted) 0f else 1f
                prepare()
                playWhenReady = settings.autoplay
            }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) player.pause() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                keepScreenOn = true
                setShowNextButton(false)
                setShowPreviousButton(false)
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> onControllerVisible(v == View.VISIBLE) })
            }
        },
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
    )
}

@Composable
private fun ImmersiveMode(hidden: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    val controller = remember(window) { WindowCompat.getInsetsController(window, view) }
    LaunchedEffect(hidden) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(Unit) {
        // Light status-bar icons over the black viewer; the theme puts them back on exit.
        val wasLight = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = wasLight
        }
    }
}

@Composable
private fun InfoSheet(post: Post, searchQuery: String?, onDismiss: () -> Unit, onSearch: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = isDarkScheme()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Post #${post.id}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                RatingBadge(post.rating)
                Spacer(Modifier.width(6.dp))
                Text(post.rating.label, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                post.score?.let { Stat(Icons.Rounded.Star, "$it") }
                post.favCount?.let { Stat(Icons.Rounded.Favorite, "$it") }
                if (post.width > 0) Text("${post.width}×${post.height}", style = MaterialTheme.typography.bodyMedium)
                if (post.extension.isNotBlank()) Text(post.extension.uppercase(), style = MaterialTheme.typography.bodyMedium)
                post.fileSize?.let { Text(humanSize(it), style = MaterialTheme.typography.bodyMedium) }
            }
            post.createdAt?.let {
                Text(formatDate(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            post.source?.takeIf { it.isNotBlank() }?.let { src ->
                Surface(
                    onClick = { if (src.startsWith("http")) openUrl(context, src) else copyText(context, src) },
                    shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Link, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(src, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(
                "Tap a tag to search it. Long-press for more.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val groups: List<Pair<TagCategory?, List<String>>> =
                post.tagGroups?.map { it.key to it.value } ?: listOf(null to post.tags)
            groups.forEach { (cat, tags) ->
                if (cat != null) {
                    Text(cat.label, style = MaterialTheme.typography.titleSmall, color = tagColor(cat, dark), modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                } else Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag ->
                        TagChip(
                            tag = tag, color = tagColor(cat, dark),
                            onClick = { onSearch(tag) },
                            onAddToSearch = searchQuery?.let { q -> { onSearch(withToken(q, tag)) } },
                            onExclude = searchQuery?.let { q -> { onSearch(withToken(q, "-$tag")) } },
                            onBlacklist = { scope.launch { Graph.settings.addToBlacklist(tag); toast(context, "Blacklisted $tag") } },
                            onCopy = { copyText(context, tag) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TagChip(
    tag: String,
    color: Color,
    onClick: () -> Unit,
    onAddToSearch: (() -> Unit)?,
    onExclude: (() -> Unit)?,
    onBlacklist: () -> Unit,
    onCopy: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = color.copy(alpha = 0.14f),
            contentColor = color,
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        ) {
            Text(tag.replace('_', ' '), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Search “$tag”") }, onClick = { menu = false; onClick() })
            onAddToSearch?.let { f -> DropdownMenuItem(text = { Text("Add to current search") }, onClick = { menu = false; f() }) }
            onExclude?.let { f -> DropdownMenuItem(text = { Text("Exclude from current search") }, onClick = { menu = false; f() }) }
            DropdownMenuItem(text = { Text("Blacklist") }, onClick = { menu = false; onBlacklist() })
            DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; onCopy() })
        }
    }
}
