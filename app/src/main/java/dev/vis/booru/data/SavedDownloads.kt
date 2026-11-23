package dev.vis.booru.data

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.Executors

/** A download in flight: what to call it and what to write into it once it lands. */
@Serializable
data class PendingDownload(
    val name: String,
    val tags: List<String>,
    val artists: List<String>,
    val postUrl: String?,
)

/**
 * Downloads land in the app's own folder first (where the app may edit them), get the post's
 * tags written in, and are then moved to Downloads/Expressive Booru through MediaStore.
 */
object SavedDownloads {
    const val FOLDER = "Expressive Booru"
    private const val PREFS = "pending_downloads"
    private const val CHANNEL = "saved"
    private const val MAX_TAGGED_BYTES = 80L * 1024 * 1024

    private val worker = Executors.newSingleThreadExecutor()

    fun incomingDir(context: Context): File? = context.getExternalFilesDir("incoming")

    fun remember(context: Context, id: Long, p: PendingDownload) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(id.toString(), AppJson.encodeToString(PendingDownload.serializer(), p)).apply()
    }

    private fun take(context: Context, id: Long): PendingDownload? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(id.toString(), null) ?: return null
        prefs.edit().remove(id.toString()).apply()
        return runCatching { AppJson.decodeFromString(PendingDownload.serializer(), raw) }.getOrNull()
    }

    /** Finishes downloads whose completion broadcast was missed (e.g. the app was force-stopped). */
    fun sweep(context: Context) {
        val app = context.applicationContext
        worker.execute {
            val ids = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.keys.mapNotNull { it.toLongOrNull() }
            if (ids.isEmpty()) return@execute
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val finished = dm.query(DownloadManager.Query().setFilterById(*ids.toLongArray())).use { c ->
                buildMap {
                    while (c.moveToNext()) put(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)), c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)))
                }
            }
            for (id in ids) {
                val status = finished[id]
                // Gone from the download manager entirely, or done: either way it's time to settle it.
                if (status == null || status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                    runCatching { finish(app, id) }
                }
            }
        }
    }

    fun onComplete(context: Context, id: Long, done: () -> Unit) {
        val app = context.applicationContext
        worker.execute {
            try { finish(app, id) } catch (_: Exception) { } finally { done() }
        }
    }

    private fun finish(context: Context, id: Long) {
        val pending = take(context, id) ?: return // not ours, or a download from an older version
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val (status, local) = dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return
            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) to c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
        }
        val file = local?.let { Uri.parse(it).path }?.let(::File)
        if (status != DownloadManager.STATUS_SUCCESSFUL || file == null || !file.exists()) {
            dm.remove(id)
            return
        }
        val ext = pending.name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val tagged = if (ext in TAGGABLE && file.length() <= MAX_TAGGED_BYTES) {
            MetadataWriter.write(file.readBytes(), ImageMeta(pending.tags, pending.artists, pending.postUrl))
        } else null

        val saved = publish(context, pending.name, mime) { out ->
            if (tagged != null) out.write(tagged) else file.inputStream().use { it.copyTo(out) }
        }
        dm.remove(id) // drops the temporary copy and its notification
        if (saved != null) notifySaved(context, id, pending.name, saved, mime)
    }

    /** Writes into Downloads/Expressive Booru; returns a content URI to open it, when there is one. */
    private fun publish(context: Context, name: String, mime: String, write: (java.io.OutputStream) -> Unit): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)!!.use(write)
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
        // Android 8–9: plain file in the public Downloads folder (needs storage permission, asked on first download).
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
        var target = File(dir, name)
        var n = 1
        while (target.exists()) target = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.')}").also { n++ }
        target.outputStream().use(write)
        MediaScannerConnection.scanFile(context, arrayOf(target.path), null, null)
        return null
    }

    private fun notifySaved(context: Context, id: Long, name: String, uri: Uri, mime: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Saved downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(context, id.toInt(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Saved to Downloads")
            .setContentText(name)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setGroup("saved")
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id.toInt(), n) }
    }

    private val TAGGABLE = setOf("jpg", "jpeg", "png", "webp")
}

/** Called by the system download manager when one of our downloads finishes (or fails). */
class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id < 0) return
        val result = goAsync()
        SavedDownloads.onComplete(context, id) { result.finish() }
    }
}
