package dev.vis.booru.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import dev.vis.booru.Graph
import dev.vis.booru.data.ApiType
import dev.vis.booru.data.AppSettings
import dev.vis.booru.data.FeedRegistry
import dev.vis.booru.data.Presets
import dev.vis.booru.data.Source
import dev.vis.booru.data.friendlyError
import dev.vis.booru.ui.Routes
import dev.vis.booru.ui.components.SourceAvatar
import dev.vis.booru.ui.components.SettingsAction
import dev.vis.booru.ui.components.SettingsGroup
import dev.vis.booru.ui.components.SubScreen
import androidx.compose.material3.MaterialShapes
import dev.vis.booru.ui.openUrl
import kotlinx.coroutines.launch

@Composable
fun SourcesScreen(nav: NavHostController, settings: AppSettings) {
    val sources by Graph.sources.items.collectAsState()
    SubScreen(
        "Sites",
        onBack = { nav.popBackStack() },
        actions = {
            var menu by remember { mutableStateOf(false) }
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Restore built-in sites") }, onClick = { menu = false; Graph.sources.restorePresets() })
            }
        },
    ) { m ->
        item {
            SettingsGroup("Your sites", m) {
                sources.forEachIndexed { i, s ->
                    val auth = when {
                        s.hasAuth -> " · signed in"
                        s.needsAuth -> " · needs API key"
                        else -> ""
                    }
                    SettingsAction(
                        title = s.name + if (s.id == settings.activeSourceId) "  ·  browsing" else "",
                        subtitle = "${s.host} · ${s.type.label}$auth",
                        icon = null, shape = MaterialShapes.Circle, index = i, count = sources.size,
                        lead = { SourceAvatar(s, 40.dp) },
                        trailing = {
                            Row {
                                IconButton(onClick = { Graph.sources.move(s.id, -1) }, enabled = i > 0) { Icon(Icons.Rounded.ArrowUpward, "Move up") }
                                IconButton(onClick = { Graph.sources.move(s.id, 1) }, enabled = i < sources.lastIndex) { Icon(Icons.Rounded.ArrowDownward, "Move down") }
                            }
                        },
                        onClick = { nav.navigate(Routes.sourceEdit(s.id)) },
                    )
                }
            }
        }
        item {
            SettingsGroup(null, m) {
                SettingsAction(
                    "Add a site", "Any Gelbooru, Danbooru, e621 or Moebooru site",
                    Icons.Rounded.Add, MaterialShapes.Cookie9Sided, 0, 1, chevron = true,
                ) { nav.navigate(Routes.sourceEdit()) }
            }
        }
        item {
            Text(
                "Tap a site to edit it or add your API key. Switch the site you browse from the avatar in the search bar.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = m.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
fun SourceEditScreen(nav: NavHostController, id: String) {
    val existing = remember(id) { Graph.sources.get(id) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isPreset = existing != null && Presets.all.any { it.id == existing.id }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: ApiType.GELBOORU) }
    var apiUrl by remember { mutableStateOf(existing?.apiUrl ?: "https://") }
    var webUrl by remember { mutableStateOf(existing?.webUrl ?: "") }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var key by remember { mutableStateOf(existing?.apiKey ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun build(): Source? {
        val api = apiUrl.trim().trimEnd('/')
        if (name.isBlank() || !api.startsWith("http")) return null
        return Source(
            id = existing?.id ?: (name.lowercase().replace(Regex("[^a-z0-9]+"), "") + "-" + System.currentTimeMillis().toString(36)),
            name = name.trim(), type = type, apiUrl = api, webUrl = webUrl.trim().trimEnd('/'),
            user = user.trim(), apiKey = key.trim(), needsAuth = existing?.needsAuth ?: false,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add site" else existing.name) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Delete") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!isPreset) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("API type", style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ApiType.entries.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) }) }
                }
                OutlinedTextField(
                    apiUrl, { apiUrl = it }, label = { Text("API base URL") }, singleLine = true,
                    supportingText = { Text("e.g. https://safebooru.org") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    webUrl, { webUrl = it }, label = { Text("Website URL (optional)") }, singleLine = true,
                    supportingText = { Text("Only if the website differs from the API host") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("${existing!!.host} · ${existing.type.label}", style = MaterialTheme.typography.bodyLarge)
            }

            Text("Account", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            Text(
                when (type) {
                    ApiType.GELBOORU -> "Gelbooru-based sites (Rule34, Gelbooru) want your numeric user ID and API key from Account → Options. Rule34 and Gelbooru won't answer without them."
                    ApiType.DANBOORU -> "Optional. Your username and an API key from your profile. Lifts the 2-tag search limit if your account allows."
                    ApiType.E621 -> "Optional. Your username and API key (Account → Manage API Access). Needed for some posts that are hidden when logged out."
                    ApiType.MOEBOORU -> "Optional. Your username and password hash."
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val helpUrl = (build() ?: existing)?.keyHelpUrl
            if (helpUrl != null) {
                OutlinedButton(onClick = { openUrl(context, helpUrl) }, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Key, null); Spacer(Modifier.width(8.dp)); Text("Get my API key")
                }
            }
            OutlinedTextField(
                user, { user = it }, label = { Text(type.userLabel) }, singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                key, { key = it }, label = { Text(type.keyLabel) }, singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show key") }
                },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (type == ApiType.GELBOORU) {
                Text(
                    "Tip: on the options page you can copy the whole “&api_key=…&user_id=…” line and paste it into the API key field.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(
                    enabled = !testing && build() != null,
                    onClick = {
                        val s = normalize(build() ?: return@OutlinedButton)
                        testing = true; testResult = null
                        scope.launch {
                            testResult = runCatching { Graph.api.posts(s, "", 0, limit = 5) }.fold(
                                { "Works: got ${it.size} posts." },
                                { "Failed: ${friendlyError(it)}" },
                            )
                            testing = false
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(if (testing) "Testing…" else "Test") }
                Button(
                    enabled = build() != null,
                    onClick = {
                        val s = normalize(build() ?: return@Button)
                        Graph.sources.upsert(s)
                        FeedRegistry.invalidate(s.id)
                        nav.popBackStack()
                    },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
            testResult?.let {
                Text(it, color = if (it.startsWith("Works")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${existing.name}?") },
            text = { Text(if (isPreset) "You can bring it back with “Restore built-in sites”." else "Favorites from this site stay, but you can't open them in the browser anymore.") },
            confirmButton = {
                TextButton(onClick = {
                    Graph.sources.remove(existing.id)
                    FeedRegistry.invalidate(existing.id)
                    confirmDelete = false
                    nav.popBackStack()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Accept the "&api_key=…&user_id=…" string Gelbooru-style sites hand out, pasted into either field. */
private fun normalize(s: Source): Source {
    val blob = s.apiKey + "&" + s.user
    if (!blob.contains("api_key=")) return s
    val key = Regex("api_key=([^&\\s]+)").find(blob)?.groupValues?.get(1) ?: s.apiKey
    val user = Regex("user_id=([^&\\s]+)").find(blob)?.groupValues?.get(1) ?: s.user
    return s.copy(apiKey = key, user = user)
}
