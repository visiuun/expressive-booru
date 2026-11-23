package dev.vis.booru.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon

/*
 * The Expressive Weather design language, shared by every screen: floating pill navigation,
 * grouped rows with connected corners, and icons set inside M3 Expressive shapes.
 */

/** Space tab screens leave at the bottom so content can scroll clear of the floating pill. */
val LocalBottomInset = compositionLocalOf { 0.dp }

val PillNavHeight: Dp = 64.dp

data class PillNavItem(val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

/** Floating capsule nav: the selected tab grows into a filled pill with its label. */
@Composable
fun PillNav(items: List<PillNavItem>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(PillNavHeight),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shadowElevation = 8.dp,
        tonalElevation = 3.dp,
    ) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { PillNavButton(it) }
        }
    }
}

@Composable
private fun PillNavButton(item: PillNavItem) {
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()
    val bg by animateColorAsState(
        if (item.selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "pillBg",
    )
    val fg by animateColorAsState(
        if (item.selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "pillFg",
    )
    Surface(
        onClick = item.onClick,
        shape = CircleShape,
        color = bg,
        contentColor = fg,
        modifier = Modifier.height(48.dp).semantics { role = Role.Tab; selected = item.selected },
    ) {
        Row(Modifier.padding(horizontal = if (item.selected) 18.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(item.icon, item.label, Modifier.size(24.dp))
            AnimatedVisibility(visible = item.selected, enter = expandHorizontally(spatial) + fadeIn(), exit = shrinkHorizontally(spatial) + fadeOut()) {
                Row {
                    Spacer(Modifier.width(8.dp))
                    Text(item.label, style = MaterialTheme.typography.labelLargeEmphasized, maxLines = 1)
                }
            }
        }
    }
}

/** A small icon set inside one of the M3 Expressive shapes. */
@Composable
fun ShapeBadge(
    icon: ImageVector,
    shape: RoundedPolygon,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Box(modifier.size(size).clip(shape.toShape()).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = content)
    }
}

/** Big outer corners, small inner ones, so a group of rows reads as one card. */
fun segmentedShape(index: Int, count: Int): RoundedCornerShape {
    val big = 24.dp
    val small = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) big else small,
        topEnd = if (index == 0) big else small,
        bottomStart = if (index == count - 1) big else small,
        bottomEnd = if (index == count - 1) big else small,
    )
}

/** Large emphasized title (with back arrow when [onBack] is set), then a centred, width-limited list. */
@Composable
fun SubScreen(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit = {},
    content: LazyListScope.(Modifier) -> Unit,
) {
    val bottom = LocalBottomInset.current.takeIf { it > 0.dp }
        ?: WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (onBack != null) 8.dp else 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Spacer(Modifier.width(4.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineMediumEmphasized, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottom),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            content(Modifier.widthIn(max = 720.dp).fillMaxWidth())
        }
    }
}

@Composable
fun SettingsGroup(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        }
        content()
    }
}

@Composable
fun SettingsItemSurface(index: Int, count: Int, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = segmentedShape(index, count)
    when {
        onLongClick != null -> Surface(
            modifier = Modifier.fillMaxWidth().clip(shape).combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick),
            shape = shape, color = MaterialTheme.colorScheme.surfaceContainer,
        ) { content() }
        onClick != null -> Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surfaceContainer) { content() }
        else -> Surface(modifier = Modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surfaceContainer) { content() }
    }
}

@Composable
private fun RowLead(icon: ImageVector?, shape: RoundedPolygon, lead: (@Composable () -> Unit)?) {
    when {
        lead != null -> { lead(); Spacer(Modifier.width(14.dp)) }
        icon != null -> { ShapeBadge(icon, shape); Spacer(Modifier.width(14.dp)) }
    }
}

@Composable
private fun RowText(title: String, subtitle: String?, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingsSwitch(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    shape: RoundedPolygon,
    index: Int,
    count: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingsItemSurface(index, count, onClick = { onChange(!checked) }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RowLead(icon, shape, null)
            RowText(title, subtitle, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                thumbContent = if (checked) ({ Icon(Icons.Rounded.Check, null, Modifier.size(SwitchDefaults.IconSize)) }) else null,
            )
        }
    }
}

/** A tappable row; [chevron] for rows that open another screen. [lead] replaces the shape icon. */
@Composable
fun SettingsAction(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    shape: RoundedPolygon,
    index: Int,
    count: Int,
    chevron: Boolean = false,
    lead: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    SettingsItemSurface(index, count, onClick = onClick, onLongClick = onLongClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RowLead(icon, shape, lead)
            RowText(title, subtitle, Modifier.weight(1f))
            if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
            if (chevron) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A row whose body is free-form content under a shaped icon and title. */
@Composable
fun SettingsBlock(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    shape: RoundedPolygon,
    index: Int,
    count: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsItemSurface(index, count) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowLead(icon, shape, null)
                RowText(title, subtitle, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** Connected button group for a single choice. */
@Composable
fun <T> ConnectedChoice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, o ->
            ToggleButton(
                checked = o == selected,
                onCheckedChange = { onSelect(o) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) { Text(label(o), maxLines = 1) }
        }
    }
}
