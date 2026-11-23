package dev.vis.booru.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import dev.vis.booru.BuildConfig
import dev.vis.booru.Graph
import dev.vis.booru.data.Accent
import dev.vis.booru.data.AppSettings
import dev.vis.booru.data.BackupIo
import dev.vis.booru.data.Rating
import dev.vis.booru.data.ThemeMode
import dev.vis.booru.data.UpdateManager
import dev.vis.booru.data.Updater
import dev.vis.booru.ui.Routes
import dev.vis.booru.ui.components.ConnectedChoice
import dev.vis.booru.ui.components.SettingsAction
import dev.vis.booru.ui.components.SettingsBlock
import dev.vis.booru.ui.components.SettingsGroup
import dev.vis.booru.ui.components.SettingsSwitch
import dev.vis.booru.ui.components.SubScreen
import dev.vis.booru.ui.openUrl
import dev.vis.booru.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(nav: NavHostController, s: AppSettings) {
    val scope = rememberCoroutineScope()
    val repo = Graph.settings
    val context = LocalContext.current
    val sources by Graph.sources.items.collectAsState()
    val updates by UpdateManager.state.collectAsState()
    var blacklist by remember(s.blacklist) { mutableStateOf(s.blacklist) }
    LaunchedEffect(blacklist) { delay(500); if (blacklist != s.blacklist) repo.setBlacklist(blacklist) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val json = BackupIo.export(Graph.favorites, Graph.searches, Graph.sources)
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) } }
            }
            toast(context, if (ok.isSuccess) "Backup saved" else "Couldn't save: ${ok.exceptionOrNull()?.message}")
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val r = runCatching {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
                BackupIo.import(text, Graph.favorites, Graph.searches, Graph.sources)
            }
            toast(context, r.fold({ "Imported $it new favorite${if (it == 1) "" else "s"}" }, { "Not a valid backup: ${it.message}" }))
        }
    }

    SubScreen("Settings", onBack = null) { m ->
        item {
            SettingsGroup("Sites", m) {
                SettingsAction(
                    "Sites & API keys",
                    "${sources.size} sites · browsing ${Graph.sources.resolve(s.activeSourceId)?.name ?: "none"}",
                    Icons.Rounded.Language, MaterialShapes.Flower, 0, 1, chevron = true,
                ) { nav.navigate(Routes.SOURCES) }
            }
        }
        item {
            SettingsGroup("Content", m) {
                SettingsBlock("Ratings", "Filtered on your device. Unrated posts only show when all four are on.", Icons.Rounded.Tune, MaterialShapes.Cookie9Sided, 0, 3) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Rating.GENERAL, Rating.SENSITIVE, Rating.QUESTIONABLE, Rating.EXPLICIT).forEach { r ->
                            FilterChip(
                                selected = r in s.ratings,
                                onClick = { scope.launch { repo.toggleRating(r) } },
                                label = { Text(r.label) },
                            )
                        }
                    }
                }
                SettingsBlock("Blacklist", "Hidden everywhere except Favorites", Icons.Rounded.Block, MaterialShapes.Cookie4Sided, 1, 3) {
                    OutlinedTextField(
                        value = blacklist, onValueChange = { blacklist = it },
                        placeholder = { Text("Tags, space or comma separated") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SettingsSwitch("Blur NSFW thumbnails", "Questionable and Explicit previews", Icons.Rounded.BlurOn, MaterialShapes.Cookie7Sided, 2, 3, s.blurNsfw) {
                    scope.launch { repo.setBlur(it) }
                }
            }
        }
        item {
            val dyn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val accents = Accent.entries.filter { dyn || it != Accent.WALLPAPER }
            val current = if (!dyn && s.accent == Accent.WALLPAPER) Accent.LILAC else s.accent
            SettingsGroup("Look & feel", m) {
                SettingsBlock("Theme", null, Icons.Rounded.Palette, MaterialShapes.Sunny, 0, 3) {
                    ConnectedChoice(ThemeMode.entries, s.themeMode, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, { v -> scope.launch { repo.setTheme(v) } }, Modifier.fillMaxWidth())
                }
                SettingsBlock("Accent colour", current.label, Icons.Rounded.ColorLens, MaterialShapes.Clover4Leaf, 1, 3) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        accents.forEach { a -> AccentSwatch(a, a == current) { scope.launch { repo.setAccent(a) } } }
                    }
                }
                SettingsSwitch("OLED black", "Pure black backgrounds in dark mode", Icons.Rounded.DarkMode, MaterialShapes.Cookie12Sided, 2, 3, s.oled) {
                    scope.launch { repo.setOled(it) }
                }
            }
        }
        item {
            SettingsGroup("Grid", m) {
                SettingsBlock("Columns", null, Icons.Rounded.GridView, MaterialShapes.Square, 0, 4) {
                    ConnectedChoice((1..5).toList(), s.gridColumns, { it.toString() }, { v -> scope.launch { repo.setColumns(v) } }, Modifier.fillMaxWidth())
                }
                SettingsSwitch("Staggered layout", "Keep each image's shape", Icons.Rounded.Dashboard, MaterialShapes.Arch, 1, 4, s.staggered) {
                    scope.launch { repo.setStaggered(it) }
                }
                SettingsSwitch("Sharper thumbnails", "Uses a lot more data", Icons.Rounded.HighQuality, MaterialShapes.Gem, 2, 4, s.hdThumbs) {
                    scope.launch { repo.setHdThumbs(it) }
                }
                SettingsSwitch("Rating badges", "G / S / Q / E on thumbnails", Icons.AutoMirrored.Rounded.Label, MaterialShapes.Cookie6Sided, 3, 4, s.showRating) {
                    scope.launch { repo.setShowRating(it) }
                }
            }
        }
        item {
            SettingsGroup("Viewer", m) {
                SettingsSwitch("Original images", "Full resolution instead of the resized sample", Icons.Rounded.Hd, MaterialShapes.Clover8Leaf, 0, 3, s.originalInViewer) {
                    scope.launch { repo.setOriginal(it) }
                }
                SettingsSwitch("Autoplay videos", null, Icons.Rounded.PlayCircle, MaterialShapes.Cookie7Sided, 1, 3, s.autoplay) {
                    scope.launch { repo.setAutoplay(it) }
                }
                SettingsSwitch("Start videos muted", null, Icons.AutoMirrored.Rounded.VolumeOff, MaterialShapes.Cookie4Sided, 2, 3, s.muted) {
                    scope.launch { repo.setMuted(it) }
                }
            }
        }
        item {
            SettingsGroup("Data", m) {
                SettingsSwitch("Search history", "Remember what you search for", Icons.Rounded.History, MaterialShapes.Cookie12Sided, 0, 5, s.saveHistory) { on ->
                    scope.launch { repo.setSaveHistory(on) }
                    if (!on) Graph.searches.history.update { emptyList() }
                }
                SettingsAction("Export backup", "Favorites, saved searches and custom sites. No API keys.", Icons.Rounded.Upload, MaterialShapes.Flower, 1, 5) {
                    exporter.launch("expressive-booru-backup.json")
                }
                SettingsAction("Import backup", "Merges with what's already here", Icons.Rounded.Download, MaterialShapes.Sunny, 2, 5) {
                    importer.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
                SettingsAction("Clear image cache", null, Icons.Rounded.CleaningServices, MaterialShapes.Gem, 3, 5) {
                    scope.launch {
                        withContext(Dispatchers.IO) { context.cacheDir.resolve("images").deleteRecursively() }
                        coil3.SingletonImageLoader.get(context).memoryCache?.clear()
                        toast(context, "Image cache cleared")
                    }
                }
                SettingsAction(
                    "Downloads", "Saved to Downloads/Expressive Booru as [artist] - hash_site.ext, with the post's tags written into the image",
                    Icons.Rounded.FolderOpen, MaterialShapes.Arch, 4, 5, onClick = null,
                )
            }
        }
        item {
            SettingsGroup("Updates", m) {
                SettingsSwitch(
                    "Check for updates automatically", "Looks for new releases on GitHub when the app starts",
                    Icons.Rounded.Update, MaterialShapes.Cookie9Sided, 0, 2, s.autoUpdate,
                ) { scope.launch { repo.setAutoUpdate(it) } }
                val r = updates.release
                val progress = updates.progress
                val status = when {
                    progress != null -> "Downloading ${(progress * 100).toInt()}%"
                    updates.checking -> "Checking…"
                    updates.message != null -> updates.message
                    else -> "Installed version ${Updater.currentVersion}"
                }
                SettingsAction(
                    if (r != null) "Install version ${r.version}" else "Check for updates", status,
                    Icons.Rounded.SystemUpdate, MaterialShapes.Flower, 1, 2,
                ) { if (r != null) UpdateManager.install(context) else UpdateManager.check(context) }
            }
        }
        item {
            SettingsGroup("About", m) {
                SettingsAction(
                    "Expressive Booru ${BuildConfig.VERSION_NAME}",
                    "Unofficial client for Gelbooru, Danbooru, e621 and Moebooru sites",
                    Icons.Rounded.Info, MaterialShapes.Circle, 0, 3, onClick = null,
                )
                SettingsAction("Source code", Updater.REPO_URL.removePrefix("https://"), Icons.Rounded.Code, MaterialShapes.Cookie6Sided, 1, 3) {
                    openUrl(context, Updater.REPO_URL)
                }
                SettingsAction("Report a problem", "Opens a new issue on GitHub", Icons.Rounded.BugReport, MaterialShapes.Gem, 2, 3) {
                    openUrl(context, Updater.REPO_URL + "/issues/new")
                }
            }
        }
    }
}

/** Round colour swatch; the wallpaper option is a split circle, the selected one gets a check. */
@Composable
private fun AccentSwatch(accent: Accent, selected: Boolean, onClick: () -> Unit) {
    val fill = if (accent == Accent.WALLPAPER) Brush.sweepGradient(listOf(Color(0xFFE0457B), Color(0xFFF0A824), Color(0xFF2E9E5B), Color(0xFF3F7FD9), Color(0xFF8E5BD9), Color(0xFFE0457B)))
    else SolidColor(Color(accent.seed))
    Box(
        Modifier
            .size(44.dp)
            .clip(MaterialShapes.Cookie9Sided.toShape())
            .background(fill)
            .clickable(onClickLabel = accent.label, onClick = onClick)
            .semantics { contentDescription = accent.label; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}
