package dev.vis.booru.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent

fun openUrl(context: Context, url: String) {
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
    } catch (_: Exception) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show() }
    }
}

fun shareText(context: Context, text: String, title: String = "Share") {
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    context.startActivity(Intent.createChooser(i, title))
}

fun copyText(context: Context, text: String, label: String = "Booru") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

/** "1girl solo -male" → tokens, keeping negations and meta tags intact. */
fun tokens(query: String): List<String> = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

fun withToken(query: String, token: String): String =
    (tokens(query).filterNot { it == token || it == "-$token" || "-$it" == token } + token).joinToString(" ")
