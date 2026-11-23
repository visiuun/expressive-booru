package dev.vis.booru.ui.components

import androidx.compose.material.icons.rounded.AllInclusive
import dev.vis.booru.data.Everything
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Gif
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.vis.booru.data.AppSettings
import dev.vis.booru.data.Post
import dev.vis.booru.data.Rating
import dev.vis.booru.data.Source
import dev.vis.booru.ui.theme.ratingColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun LoadingView(modifier: Modifier = Modifier, label: String? = null) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        LoadingIndicator(modifier = Modifier.size(56.dp))
        if (label != null) {
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun InlineLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        LoadingIndicator(modifier = Modifier.size(40.dp))
    }
}

@Composable
fun ErrorView(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "Something went wrong",
    icon: ImageVector = Icons.Rounded.ErrorOutline,
    onRetry: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        extra?.invoke()
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRetry, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Retry")
            }
        }
    }
}

/** Shown when a site answers "missing authentication": the fix is adding a key, not retrying. */
@Composable
fun NeedsKeyView(source: Source, message: String, onAddKey: () -> Unit, onSwitch: () -> Unit) {
    ErrorView(
        title = "${source.name} needs an API key",
        message = message + "\n\nAdd your user ID and API key from your ${source.name} account settings, or switch to another site.",
        icon = Icons.Rounded.Key,
        extra = {
            Button(onClick = onAddKey, shapes = ButtonDefaults.shapes()) { Text("Add API key") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSwitch, shapes = ButtonDefaults.shapes()) { Text("Switch site") }
        },
    )
}

@Composable
fun EmptyView(message: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Rounded.Inbox) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun RatingBadge(rating: Rating, modifier: Modifier = Modifier) {
    Surface(color = ratingColor(rating), contentColor = Color.White, shape = MaterialTheme.shapes.extraSmall, modifier = modifier) {
        Text(rating.short, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

/** Round monogram for a site, tinted from its name so each source is recognisable. */
@Composable
fun SourceAvatar(source: Source?, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    val name = source?.name ?: "?"
    val hue = ((name.lowercase().hashCode() % 360) + 360) % 360
    val bg = Color.hsl(hue.toFloat(), 0.55f, 0.45f)
    if (source?.id == Everything.ID) {
        Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AllInclusive, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(size * 0.6f))
        }
        return
    }
    Box(modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(
            name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold,
            style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun CornerChip(icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White, shape = CircleShape, modifier = modifier) {
        Icon(icon, null, Modifier.padding(3.dp).size(16.dp))
    }
}

@Composable
fun PostThumb(
    post: Post,
    settings: AppSettings,
    favorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val url = if (settings.hdThumbs && !post.isVideo) post.sampleUrl ?: post.previewUrl else post.previewUrl ?: post.sampleUrl
    val ratio = if (settings.staggered) post.aspect else 1f
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(onClick = onClick, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() }),
    ) {
        AsyncImage(
            // Explicit cache key so the viewer can show this exact bitmap as its placeholder.
            model = remember(url) { ImageRequest.Builder(context).data(url).memoryCacheKey(url).build() },
            contentDescription = post.tags.take(6).joinToString(" "),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(if (settings.blurNsfw && post.rating.nsfw) Modifier.blur(20.dp) else Modifier),
        )
        Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (settings.showRating && post.rating != Rating.UNKNOWN) RatingBadge(post.rating)
        }
        Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                post.isVideo -> CornerChip(Icons.Rounded.PlayArrow)
                post.isGif -> CornerChip(Icons.Rounded.Gif)
            }
        }
        if (favorite) {
            Icon(
                Icons.Rounded.Favorite, "Favorite", tint = Color(0xFFFF5C8A),
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(18.dp),
            )
        }
    }
}

/**
 * Infinite staggered grid of posts. [onLoadMore] fires as the user nears the end; when the
 * client-side filter hides a whole page, it keeps fetching until something visible arrives.
 */
@Composable
fun PostGrid(
    posts: List<Post>,
    settings: AppSettings,
    favorites: Set<String>,
    loadingMore: Boolean,
    endReached: Boolean,
    onLoadMore: () -> Unit,
    onOpen: (Post) -> Unit,
    /** True while the last page failed; stops auto-loading until the user retries. */
    blocked: Boolean = false,
    onLongPress: (Post) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    contentPadding: PaddingValues = PaddingValues(10.dp),
    footer: (@Composable () -> Unit)? = null,
) {
    val columns = settings.gridColumns.coerceIn(1, 5)
    val count by rememberUpdatedState(posts.size)
    val end by rememberUpdatedState(endReached || blocked)
    val busy by rememberUpdatedState(loadingMore)
    val load by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state, columns) {
        snapshotFlow {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            !end && !busy && last >= count - columns * 6
        }.distinctUntilChanged().filter { it }.collect { load() }
    }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalItemSpacing = 6.dp,
        modifier = modifier.fillMaxSize(),
    ) {
        items(posts, key = { it.key }, contentType = { "post" }) { p ->
            PostThumb(p, settings, p.key in favorites, onClick = { onOpen(p) }, onLongClick = { onLongPress(p) })
        }
        if (loadingMore) item(span = StaggeredGridItemSpan.FullLine) { InlineLoading() }
        else if (footer != null) item(span = StaggeredGridItemSpan.FullLine) { footer() }
        else if (endReached && posts.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
            Text(
                "That's everything", Modifier.fillMaxWidth().padding(20.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}
