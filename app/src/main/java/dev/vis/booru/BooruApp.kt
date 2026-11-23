package dev.vis.booru

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.vis.booru.data.BooruApi
import dev.vis.booru.data.DownloadLog
import dev.vis.booru.data.SavedDownloads
import dev.vis.booru.data.FavoritesRepo
import dev.vis.booru.data.SearchesRepo
import dev.vis.booru.data.SettingsRepo
import dev.vis.booru.data.SourcesRepo

object Graph {
    lateinit var api: BooruApi
    lateinit var settings: SettingsRepo
    lateinit var sources: SourcesRepo
    lateinit var favorites: FavoritesRepo
    lateinit var searches: SearchesRepo
    lateinit var downloadLog: DownloadLog
}

class BooruApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Graph.api = BooruApi(cacheDir)
        Graph.settings = SettingsRepo(this)
        Graph.sources = SourcesRepo(this)
        Graph.favorites = FavoritesRepo(this)
        Graph.searches = SearchesRepo(this)
        Graph.downloadLog = DownloadLog(this)
        SavedDownloads.sweep(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { Graph.api.client }))
                if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(768L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
}
