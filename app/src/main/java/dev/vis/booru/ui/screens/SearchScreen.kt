package dev.vis.booru.ui.screens

import dev.vis.booru.data.Everything
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import dev.vis.booru.Graph
import dev.vis.booru.data.TagSuggestion
import dev.vis.booru.data.compactCount
import dev.vis.booru.ui.Routes
import dev.vis.booru.ui.components.SectionHeader
import dev.vis.booru.ui.components.SourceAvatar
import dev.vis.booru.ui.theme.isDarkScheme
import dev.vis.booru.ui.theme.tagColor
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(nav: NavHostController, sourceId: String, initial: String) {
    val source = Graph.sources.resolve(sourceId) ?: Graph.sources.items.value.firstOrNull() ?: run { nav.popBackStack(); return }
    val suggestFrom = if (source.id == Everything.ID) Everything.autocompleteSite() else source
    val start = if (initial.isBlank()) "" else initial.trimEnd() + " "
    var field by remember { mutableStateOf(TextFieldValue(start, TextRange(start.length))) }
    var suggestions by remember { mutableStateOf<List<TagSuggestion>>(emptyList()) }
    val focus = remember { FocusRequester() }
    val history by Graph.searches.history.items.collectAsState()
    val saved by Graph.searches.saved.items.collectAsState()
    val dark = isDarkScheme()

    // The token under edit is the last word; strip negation/OR prefixes before asking the site.
    val lastToken = field.text.substringAfterLast(' ')
    val prefix = lastToken.takeWhile { it == '-' || it == '~' }
    val term = lastToken.drop(prefix.length)

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(term, source.id) {
        val from = suggestFrom
        if (from == null || term.length < from.type.minAutocomplete || term.contains(':')) { suggestions = emptyList(); return@LaunchedEffect }
        delay(220)
        suggestions = runCatching { Graph.api.autocomplete(from, term) }.getOrDefault(emptyList())
    }

    fun submit(q: String = field.text) {
        val query = q.trim().replace(Regex("\\s+"), " ")
        nav.navigate(Routes.results(source.id, query)) { popUpTo(Routes.SEARCH_PATTERN) { inclusive = true } }
    }

    fun applySuggestion(name: String) {
        val head = field.text.substringBeforeLast(' ', "")
        val next = (if (head.isBlank()) "" else "$head ") + prefix + name + " "
        field = TextFieldValue(next, TextRange(next.length))
    }

    Box(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    TextField(
                        value = field,
                        onValueChange = { field = it },
                        placeholder = { Text("Tags, e.g. cat_ears -monochrome") },
                        leadingIcon = { SourceAvatar(source, 28.dp) },
                        trailingIcon = {
                            if (field.text.isNotEmpty()) IconButton(onClick = { field = TextFieldValue("") }) { Icon(Icons.Rounded.Close, "Clear") }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { submit() }) { Icon(Icons.Rounded.Search, "Search") }
                }
            }

            if (suggestions.isNotEmpty()) {
                items(suggestions, key = { "s:" + it.name }) { s ->
                    ListItem(
                        leadingContent = {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(tagColor(s.category, dark)))
                        },
                        headlineContent = { Text(s.name.replace('_', ' '), color = tagColor(s.category, dark), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = s.category?.let { c -> { Text(c.label) } },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(compactCount(s.count), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                IconButton(onClick = { applySuggestion(s.name) }) { Icon(Icons.Rounded.NorthWest, "Use tag") }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { applySuggestion(s.name) },
                    )
                }
            } else {
                item {
                    HintColumn {
                        Text(
                            if (source.id == Everything.ID) "Separate tags with spaces. Prefix a tag with - to exclude it. Searches every site at once; tag names that differ between sites only match where they exist."
                            else "Separate tags with spaces. Prefix a tag with - to exclude it. ${source.name} uses ${source.type.label} search syntax.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            AssistChip(onClick = { submit("${field.text} ${source.type.scoreSort}") }, label = { Text("Top score") })
                            AssistChip(onClick = { submit("${field.text} ${source.type.randomSort}") }, label = { Text("Random") })
                            if (source.type == dev.vis.booru.data.ApiType.GELBOORU) {
                                AssistChip(onClick = { submit("${field.text} video") }, label = { Text("Videos") })
                                AssistChip(onClick = { submit("${field.text} animated") }, label = { Text("Animated") })
                            } else {
                                AssistChip(onClick = { submit("${field.text} animated") }, label = { Text("Animated") })
                            }
                        }
                    }
                }
                val savedHere = saved.filter { it.sourceId == source.id }
                if (savedHere.isNotEmpty()) {
                    item { SectionHeader("Saved", Modifier.padding(horizontal = 12.dp)) }
                    items(savedHere, key = { "saved:" + it.query }) { s ->
                        ListItem(
                            leadingContent = { Icon(Icons.Rounded.Bookmark, null) },
                            headlineContent = { Text(s.query, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { submit(s.query) },
                        )
                    }
                }
                val recent = history.filter { it.sourceId == source.id }.take(15)
                if (recent.isNotEmpty()) {
                    item { SectionHeader("Recent", Modifier.padding(horizontal = 12.dp)) }
                    items(recent, key = { "h:" + it.query }) { s ->
                        ListItem(
                            leadingContent = { Icon(Icons.Rounded.History, null) },
                            headlineContent = { Text(s.query, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                IconButton(onClick = { field = TextFieldValue(s.query + " ", TextRange(s.query.length + 1)) }) { Icon(Icons.Rounded.NorthWest, "Edit") }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { submit(s.query) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HintColumn(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { content() }
}

